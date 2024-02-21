
package org.adaway.vpn.dns;

import android.content.Context;

import org.adaway.AdAwayApplication;
import org.adaway.db.entity.HostEntry;
import org.adaway.db.entity.ListType;
import org.adaway.model.vpn.VpnModel;
import org.adaway.util.AppExecutors;
import org.adaway.vpn.dns.DnsPacketProxy.EventLoop;
import org.pcap4j.packet.IpPacket;
import org.pcap4j.packet.IpSelector;
import org.pcap4j.packet.IpV4Packet;
import org.pcap4j.packet.IpV6Packet;
import org.pcap4j.packet.Packet;
import org.pcap4j.packet.UdpPacket;
import org.pcap4j.packet.UnknownPacket;
import org.pcap4j.packet.namednumber.IpNumber;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;

import okhttp3.Cache;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.dnsoverhttps.DnsOverHttps;
import timber.log.Timber;

/**
 * Creates and parses packets, and sends packets to a remote socket or the device using VpnWorker.
 */
public class DohPacketProxy {
    // Choose a value that is smaller than the time needed to unblock a host.
    private static final int NEGATIVE_CACHE_TTL_SECONDS = 5;
    private static final SOARecord NEGATIVE_CACHE_SOA_RECORD;

    private static final Executor EXECUTOR = AppExecutors.getInstance().networkIO();

    static {
        try {
            // Let's use a guaranteed invalid hostname here, clients are not supposed to use
            // our fake values, the whole thing just exists for negative caching.
            Name name = new Name("adaway.vpn.invalid.");
            NEGATIVE_CACHE_SOA_RECORD = new SOARecord(name, DClass.IN, NEGATIVE_CACHE_TTL_SECONDS,
                    name, name, 0, 0, 0, 0, NEGATIVE_CACHE_TTL_SECONDS);
        } catch (TextParseException e) {
            throw new RuntimeException(e);
        }
    }

    private final EventLoop eventLoop;
    private final DnsServerMapper dnsServerMapper;
    private VpnModel vpnModel;
    private DnsOverHttps dnsOverHttps;

    public DohPacketProxy(EventLoop eventLoop, DnsServerMapper dnsServerMapper) {
        this.eventLoop = eventLoop;
        this.dnsServerMapper = dnsServerMapper;
    }

    private static InetAddress getByIp(String host) {
        try {
            return InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            // unlikely
            throw new RuntimeException(e);
        }
    }

    /**
     * Initializes the rules database and the list of upstream servers.
     *
     * @param context The context we are operating in (for the database).
     */
    public void initialize(Context context) {
        this.vpnModel = (VpnModel) ((AdAwayApplication) context.getApplicationContext()).getAdBlockModel();
        this.dnsOverHttps = createDnsOverHttps(context);
    }

    private DnsOverHttps createDnsOverHttps(Context context) {
        Cache dnsClientCache = new Cache(context.getCacheDir(), 10 * 1024 * 1024L);
        OkHttpClient dnsClient = new OkHttpClient.Builder().cache(dnsClientCache).build();
        return new DnsOverHttps.Builder()
                .client(dnsClient)
                .url(HttpUrl.get("https://cloudflare-dns.com/dns-query"))
                .bootstrapDnsHosts(getByIp("1.1.1.1"), getByIp("1.0.0.1"))
                .includeIPv6(false)
                .post(true)
                .build();
    }

    /**
     * Handles a responsePayload from an upstream DNS server
     *
     * @param requestPacket   The original request packet
     * @param responsePayload The payload of the response
     */
    public void handleDnsResponse(IpPacket requestPacket, byte[] responsePayload) {
        UdpPacket udpOutPacket = (UdpPacket) requestPacket.getPayload();
        UdpPacket.Builder payLoadBuilder = new UdpPacket.Builder(udpOutPacket)
                .srcPort(udpOutPacket.getHeader().getDstPort())
                .dstPort(udpOutPacket.getHeader().getSrcPort())
                .srcAddr(requestPacket.getHeader().getDstAddr())
                .dstAddr(requestPacket.getHeader().getSrcAddr())
                .correctChecksumAtBuild(true)
                .correctLengthAtBuild(true)
                .payloadBuilder(
                        new UnknownPacket.Builder().rawData(responsePayload)
                );

        IpPacket ipOutPacket;
        if (requestPacket instanceof IpV4Packet) {
            ipOutPacket = new IpV4Packet.Builder((IpV4Packet) requestPacket)
                    .srcAddr((Inet4Address) requestPacket.getHeader().getDstAddr())
                    .dstAddr((Inet4Address) requestPacket.getHeader().getSrcAddr())
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(payLoadBuilder)
                    .build();

        } else {
            ipOutPacket = new IpV6Packet.Builder((IpV6Packet) requestPacket)
                    .srcAddr((Inet6Address) requestPacket.getHeader().getDstAddr())
                    .dstAddr((Inet6Address) requestPacket.getHeader().getSrcAddr())
                    .correctLengthAtBuild(true)
                    .payloadBuilder(payLoadBuilder)
                    .build();
        }

        this.eventLoop.queueDeviceWrite(ipOutPacket);
    }

    /**
     * Handles a DNS request, by either blocking it or forwarding it to the remote location.
     *
     * @param packetData The packet data to read
     * @throws IOException If some network error occurred
     */
    public void handleDnsRequest(byte[] packetData) throws IOException {
        IpPacket ipPacket;
        try {
            ipPacket = (IpPacket) IpSelector.newPacket(packetData, 0, packetData.length);
        } catch (Exception e) {
            Timber.i(e, "handleDnsRequest: Discarding invalid IP packet");
            return;
        }

        // Check UDP protocol
        if (ipPacket.getHeader().getProtocol() != IpNumber.UDP) {
            return;
        }

        UdpPacket updPacket;
        Packet udpPayload;

        try {
            updPacket = (UdpPacket) ipPacket.getPayload();
            udpPayload = updPacket.getPayload();
        } catch (Exception e) {
            Timber.i(e, "handleDnsRequest: Discarding unknown packet type %s", ipPacket.getHeader());
            return;
        }

        InetAddress packetAddress = ipPacket.getHeader().getDstAddr();
        int packetPort = updPacket.getHeader().getDstPort().valueAsInt();
        Optional<InetAddress> dnsAddressOptional = this.dnsServerMapper.getDnsServerFromFakeAddress(packetAddress);
        if (!dnsAddressOptional.isPresent()) {
            Timber.w("Cannot find mapped DNS for %s.", packetAddress.getHostAddress());
            return;
        }
        InetAddress dnsAddress = dnsAddressOptional.get();

        if (udpPayload == null) {
            Timber.i("handleDnsRequest: Sending UDP packet without payload: %s", updPacket);

            // Let's be nice to Firefox. Firefox uses an empty UDP packet to
            // the gateway to reduce the RTT. For further details, please see
            // https://bugzilla.mozilla.org/show_bug.cgi?id=888268
            DatagramPacket outPacket = new DatagramPacket(new byte[0], 0, 0 /* length */, dnsAddress, packetPort);
            eventLoop.forwardPacket(outPacket);
            return;
        }

        byte[] dnsRawData = udpPayload.getRawData();
        Message dnsMsg;
        try {
            dnsMsg = new Message(dnsRawData);
        } catch (IOException e) {
            Timber.i(e, "handleDnsRequest: Discarding non-DNS or invalid packet");
            return;
        }
        if (dnsMsg.getQuestion() == null) {
            Timber.i("handleDnsRequest: Discarding DNS packet with no query %s", dnsMsg);
            return;
        }
        Name name = dnsMsg.getQuestion().getName();
        String dnsQueryName = name.toString(true);
        HostEntry entry = getHostEntry(dnsQueryName);
        switch (entry.getType()) {
            case BLOCKED:
                Timber.i("handleDnsRequest: DNS Name %s blocked!", dnsQueryName);
                dnsMsg.getHeader().setFlag(Flags.QR);
                dnsMsg.getHeader().setRcode(Rcode.NOERROR);
                dnsMsg.addRecord(NEGATIVE_CACHE_SOA_RECORD, Section.AUTHORITY);
                handleDnsResponse(ipPacket, dnsMsg.toWire());
                break;
            case ALLOWED:
                Timber.i("handleDnsRequest: DNS Name %s allowed, sending to %s.", dnsQueryName, dnsAddress);
                EXECUTOR.execute(() -> queryDohServer(ipPacket, dnsMsg, name));
                break;
            case REDIRECTED:
                Timber.i("handleDnsRequest: DNS Name %s redirected to %s.", dnsQueryName, entry.getRedirection());
                dnsMsg.getHeader().setFlag(Flags.QR);
                dnsMsg.getHeader().setFlag(Flags.AA);
                dnsMsg.getHeader().unsetFlag(Flags.RD);
                dnsMsg.getHeader().setRcode(Rcode.NOERROR);
                try {
                    InetAddress address = InetAddress.getByName(entry.getRedirection());
                    Record dnsRecord;
                    if (address instanceof Inet6Address) {
                        dnsRecord = new AAAARecord(name, DClass.IN, NEGATIVE_CACHE_TTL_SECONDS, address);
                    } else {
                        dnsRecord = new ARecord(name, DClass.IN, NEGATIVE_CACHE_TTL_SECONDS, address);
                    }
                    dnsMsg.addRecord(dnsRecord, Section.ANSWER);
                } catch (UnknownHostException e) {
                    Timber.w(e, "Failed to get inet address for host %s.", dnsQueryName);
                }
                handleDnsResponse(ipPacket, dnsMsg.toWire());
                break;
        }
    }

    private void queryDohServer(IpPacket ipPacket, Message dnsMsg, Name name) {
        String dnsQueryName = name.toString(true);
        InetAddress address = null;
        try {
            List<InetAddress> addresses = this.dnsOverHttps.lookup(dnsQueryName);
            if (!addresses.isEmpty()) {
                address = addresses.get(0);
            }
        } catch (UnknownHostException e) {
            Timber.i(e, "Failed to query DNS Name %s.", dnsQueryName);
        }

        if (address == null) {
            Timber.i("No address was found for DNS Name %s.", dnsQueryName);
            return;
        }

        Timber.i("handleDnsRequest: DNS Name %s redirected to %s.", dnsQueryName, address);
        dnsMsg.getHeader().setFlag(Flags.QR);
        dnsMsg.getHeader().setFlag(Flags.AA);
        dnsMsg.getHeader().unsetFlag(Flags.RD);
        dnsMsg.getHeader().setRcode(Rcode.NOERROR);
        Record dnsRecord;
        if (address instanceof Inet6Address) {
            dnsRecord = new AAAARecord(name, DClass.IN, NEGATIVE_CACHE_TTL_SECONDS, address);
        } else {
            dnsRecord = new ARecord(name, DClass.IN, NEGATIVE_CACHE_TTL_SECONDS, address);
        }
        dnsMsg.addRecord(dnsRecord, Section.ANSWER);
        handleDnsResponse(ipPacket, dnsMsg.toWire());
    }

    private HostEntry getHostEntry(String dnsQueryName) {
        String hostname = dnsQueryName.toLowerCase(Locale.ENGLISH);
        HostEntry entry = null;
        if (this.vpnModel != null) {
            entry = this.vpnModel.getEntry(hostname);
        }
        if (entry == null) {
            entry = new HostEntry();
            entry.setHost(hostname);
            entry.setType(ListType.ALLOWED);
        }
        return entry;
    }
}
