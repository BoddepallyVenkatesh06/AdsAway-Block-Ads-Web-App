/*
 * Derived from dns66:
 * Copyright (C) 2016-2019 Julian Andres Klode <jak@jak-linux.org>
 *
 * Derived from AdBuster:
 * Copyright (C) 2016 Daniel Brodie <dbrodie@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * Contributions shall also be provided under any later versions of the
 * GPL.
 */
package org.adaway.vpn.worker;

import static android.system.OsConstants.ENETUNREACH;
import static android.system.OsConstants.EPERM;
import static android.system.OsConstants.POLLIN;
import static android.system.OsConstants.POLLOUT;
import static org.adaway.vpn.VpnStatus.RECONNECTING_NETWORK_ERROR;
import static org.adaway.vpn.VpnStatus.RUNNING;
import static org.adaway.vpn.VpnStatus.STARTING;
import static org.adaway.vpn.VpnStatus.STOPPED;
import static org.adaway.vpn.VpnStatus.STOPPING;
import static org.adaway.vpn.worker.VpnBuilder.establish;

import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructPollfd;

import org.adaway.helper.PreferenceHelper;
import org.adaway.vpn.VpnService;
import org.adaway.vpn.dns.DnsPacketProxy;
import org.adaway.vpn.dns.DnsQueryQueue;
import org.adaway.vpn.dns.DnsServerMapper;
import org.pcap4j.packet.IpPacket;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import timber.log.Timber;

// TODO Write document
// TODO It is thread safe
// TODO Rework status notification
// TODO Improve exception handling in work()
public class VpnWorker implements DnsPacketProxy.EventLoop {
    /**
     * Maximum packet size is constrained by the MTU, which is given as a signed short.
     */
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE;

    /**
     * The VPN service, also used as {@link android.content.Context}.
     */
    private final VpnService vpnService;
    /**
     * The queue of packets to send to the device.
     */
    private final Queue<byte[]> deviceWrites;
    /**
     * The queue of DNS queries.
     */
    private final DnsQueryQueue dnsQueryQueue;
    // The mapping between fake and real dns addresses
    private final DnsServerMapper dnsServerMapper;
    // The object where we actually handle packets.
    private final DnsPacketProxy dnsPacketProxy;

    // TODO Comment
    private final VpnConnectionThrottler connectionThrottler;
    private final VpnConnectionMonitor connectionMonitor;

    // Watch dog that checks our connection is alive.
    private final VpnWatchdog vpnWatchDog;

    /**
     * The VPN worker executor (<code>null</code> if not started).
     */
    private final AtomicReference<ExecutorService> executor;
    /**
     * The VPN network interface, (<code>null</code> if not established).
     */
    private final AtomicReference<ParcelFileDescriptor> vpnNetworkInterface;

    /**
     * Constructor.
     *
     * @param vpnService The VPN service, also used as {@link android.content.Context}.
     */
    public VpnWorker(VpnService vpnService) {
        this.vpnService = vpnService;
        this.deviceWrites = new LinkedList<>();
        this.dnsQueryQueue = new DnsQueryQueue();
        this.dnsServerMapper = new DnsServerMapper();
        this.dnsPacketProxy = new DnsPacketProxy(this, this.dnsServerMapper);
        this.connectionThrottler = new VpnConnectionThrottler();
        this.connectionMonitor = new VpnConnectionMonitor(this.vpnService);
        this.vpnWatchDog = new VpnWatchdog();
        this.executor = new AtomicReference<>(null);
        this.vpnNetworkInterface = new AtomicReference<>(null);
    }

    /**
     * Start the VPN worker.
     * Kill the current worker and restart it if already running.
     */
    public void start() {
        Timber.d("Starting VPN thread…");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        executor.submit(this::work);
        executor.submit(this.connectionMonitor::monitor);
        setExecutor(executor);
        Timber.i("VPN thread started.");
    }

    /**
     * Stop the VPN worker.
     */
    public void stop() {
        Timber.d("Stopping VPN thread.");
        this.connectionMonitor.reset();
        forceCloseTunnel();
        setExecutor(null);
        Timber.i("VPN thread stopped.");
    }

    /**
     * Keep track of the worker executor.<br>
     * Shut the previous one down in exists.
     *
     * @param executor The new worker executor, <code>null</code> if no executor any more.
     */
    private void setExecutor(ExecutorService executor) {
        ExecutorService oldExecutor = this.executor.getAndSet(executor);
        if (oldExecutor != null) {
            Timber.d("Shutting down VPN executor…");
            oldExecutor.shutdownNow();
            Timber.d("VPN executor shut down.");
        }
    }

    /**
     * Force close the tunnel connection.
     */
    private void forceCloseTunnel() {
        ParcelFileDescriptor networkInterface = this.vpnNetworkInterface.get();
        if (networkInterface != null) {
            try {
                networkInterface.close();
            } catch (IOException e) {
                Timber.tag("Failed to close VPN network interface.").w(e);
            }
        }
    }

    private void work() {
        Timber.d("Starting work…");
        // Initialize context
        this.dnsPacketProxy.initialize(this.vpnService);
        // Initialize the watchdog
        this.vpnWatchDog.initialize(PreferenceHelper.getVpnWatchdogEnabled(this.vpnService));
        // Try connecting the vpn continuously
        while (true) {
            try {
                this.connectionThrottler.throttle();
                this.vpnService.notifyVpnStatus(STARTING);
                runVpn();
                Timber.i("Told to stop");
                this.vpnService.notifyVpnStatus(STOPPING);
                break;
            } catch (InterruptedException e) {
                Timber.d(e, "Failed to wait for connexion throttling.");
                Thread.currentThread().interrupt();
                break;
            } catch (VpnNetworkException | IOException e) {
                Timber.w(e, "Network exception in vpn thread, reconnecting…");
                // If an exception was thrown, notify status and try again
                this.vpnService.notifyVpnStatus(RECONNECTING_NETWORK_ERROR);
            }
        }
        this.vpnService.notifyVpnStatus(STOPPED);
        Timber.d("Exiting work.");
    }

    private void runVpn() throws IOException, VpnNetworkException {
        // Allocate the buffer for a single packet.
        byte[] packet = new byte[MAX_PACKET_SIZE];

        // Authenticate and configure the virtual network interface.
        try (ParcelFileDescriptor pfd = establish(this.vpnService, this.dnsServerMapper);
             // Read and write views of the tunnel device
             FileInputStream inputStream = new FileInputStream(pfd.getFileDescriptor());
             FileOutputStream outputStream = new FileOutputStream(pfd.getFileDescriptor())) {
            // Store reference to network interface to close it externally on demand
            this.vpnNetworkInterface.set(pfd);
            // Initialize connection monitor
            this.connectionMonitor.initialize();

            // Update address to ping with default DNS server
            this.vpnWatchDog.setTarget(this.dnsServerMapper.getDefaultDnsServerAddress());

            // Now we are connected. Set the flag and show the message.
            this.vpnService.notifyVpnStatus(RUNNING);

            // We keep forwarding packets till something goes wrong.
            boolean deviceOpened = true;
            while (deviceOpened) {
                deviceOpened = doOne(inputStream, outputStream, packet);
            }
        }
    }

    private boolean doOne(FileInputStream inputStream, FileOutputStream fileOutputStream, byte[] packet)
            throws IOException, VpnNetworkException {
        // Create poll FD on tunnel
        StructPollfd deviceFd = new StructPollfd();
        deviceFd.fd = inputStream.getFD();
        deviceFd.events = (short) POLLIN;
        if (!this.deviceWrites.isEmpty()) {
            deviceFd.events |= (short) POLLOUT;
        }
        // Create poll FD on each DNS query socket
        StructPollfd[] queryFds = this.dnsQueryQueue.getQueryFds();
        StructPollfd[] polls = new StructPollfd[1 + queryFds.length];
        polls[0] = deviceFd;
        System.arraycopy(queryFds, 0, polls, 1, queryFds.length);
        boolean deviceReadyToWrite;
        boolean deviceReadyToRead;
        try {
            Timber.d("doOne: Polling %d file descriptors.", polls.length);
            int numberOfEvents = Os.poll(polls, this.vpnWatchDog.getPollTimeout());
            // TODO BUG - There is a bug where the watchdog keeps doing timeout if there is no network activity
            // TODO BUG - 0 Might be a valid value if no current DNS query and everything was already sent back to device
            if (numberOfEvents == 0) {
                this.vpnWatchDog.handleTimeout();
                return true;
            }
            deviceReadyToWrite = (deviceFd.revents & POLLOUT) != 0;
            deviceReadyToRead = (deviceFd.revents & POLLIN) != 0;
        } catch (ErrnoException e) {
            throw new IOException("Failed to wait for event on file descriptors. Error number: " + e.errno, e);
        }

        // Need to do this before reading from the device, otherwise a new insertion there could
        // invalidate one of the sockets we want to read from either due to size or time out
        // constraints
        this.dnsQueryQueue.handleResponses();
        if (deviceReadyToWrite) {
            writeToDevice(fileOutputStream);
        }
        if (deviceReadyToRead) {
            return readPacketFromDevice(inputStream, packet) != -1;
        }
        return true;
    }

    private void writeToDevice(FileOutputStream fileOutputStream) throws IOException {
        Timber.d("Write to device %d packets.", this.deviceWrites.size());
        try {
            while (!this.deviceWrites.isEmpty()) {
                byte[] ipPacketData = this.deviceWrites.poll();
                fileOutputStream.write(ipPacketData);
            }
        } catch (IOException e) {
            throw new IOException("Failed to write to tunnel output stream.", e);
        }
    }

    private int readPacketFromDevice(FileInputStream inputStream, byte[] packet) throws IOException {
        Timber.d("Read a packet from device.");
        // Read the outgoing packet from the input stream.
        int length = inputStream.read(packet);
        if (length < 0) {
            // TODO Stream closed. Is there anything else to do?
            Timber.d("Tunnel input stream closed.");
        } else if (length == 0) {
            Timber.d("Read empty packet from tunnel.");
        } else {
            byte[] readPacket = Arrays.copyOf(packet, length);
            vpnWatchDog.handlePacket(readPacket);
            dnsPacketProxy.handleDnsRequest(readPacket);
        }
        return length;
    }

    @Override
    public void forwardPacket(DatagramPacket packet) throws IOException {
        try (DatagramSocket dnsSocket = new DatagramSocket()) {
            this.vpnService.protect(dnsSocket);
            dnsSocket.send(packet);
        } catch (IOException e) {
            throw new IOException("Failed to forward packet.", e);
        }
    }

    @Override
    public void forwardPacket(DatagramPacket outPacket, Consumer<byte[]> callback) throws IOException {
        DatagramSocket dnsSocket = null;
        try {
            dnsSocket = new DatagramSocket();
            // Packets to be sent to the real DNS server will need to be protected from the VPN
            this.vpnService.protect(dnsSocket);
            dnsSocket.send(outPacket);
            // Enqueue DNS query
            this.dnsQueryQueue.addQuery(dnsSocket, callback);
        } catch (IOException e) {
            if (dnsSocket != null) {
                dnsSocket.close();
            }
            if (e.getCause() instanceof ErrnoException) {
                ErrnoException errnoExc = (ErrnoException) e.getCause();
                if ((errnoExc.errno == ENETUNREACH) || (errnoExc.errno == EPERM)) {
                    throw new IOException("Cannot send message:", e);
                }
            }
            Timber.w(e, "handleDnsRequest: Could not send packet to upstream");
        }
    }

    @Override
    public void queueDeviceWrite(IpPacket ipOutPacket) {
        byte[] rawData = ipOutPacket.getRawData();
        // TODO Check why data could be null
        if (rawData != null) {
            this.deviceWrites.add(rawData);
        }
    }
}
