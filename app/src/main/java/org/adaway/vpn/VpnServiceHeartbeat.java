package org.adaway.vpn;

import static android.content.Context.ACTIVITY_SERVICE;
import static androidx.work.ExistingPeriodicWorkPolicy.KEEP;
import static androidx.work.ListenableWorker.Result.success;
import static java.lang.Integer.MAX_VALUE;
import static java.util.concurrent.TimeUnit.MINUTES;

import android.app.ActivityManager;
import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import timber.log.Timber;

/**
 * This class is a worker to monitor the {@link VpnService} is still running.
 *
 * @author Bruce BUJON (bruce.bujon(at)gmail(dot)com)
 */
public class VpnServiceHeartbeat extends Worker {
    /**
     * The VPN service heartbeat unique worker name.
     */
    private static final String WORK_NAME = "vpnHeartbeat";

    /**
     * Constructor.
     *
     * @param context      The application context.
     * @param workerParams The worker parameters.
     */
    public VpnServiceHeartbeat(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (VpnServiceControls.isStarted(context) && !isVpnServiceRunning()) {
            Timber.i("VPN service is not running. Starting VPN service…");
            VpnServiceControls.start(context);
            Timber.i("VPN service started.");
        }
        return success();
    }

    // TODO Use VpnServiceControls.isVpnServiceRunning instead?
    private boolean isVpnServiceRunning() {
        String serviceName = VpnService.class.getName();
        ActivityManager manager = (ActivityManager) getApplicationContext().getSystemService(ACTIVITY_SERVICE);
        // Deprecated as it only return application service. It is fine for this use case.
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(MAX_VALUE)) {
            if (serviceName.equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Start the VPN service monitor.
     *
     * @param context The application context.
     */
    public static void start(Context context) {
        PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(VpnServiceHeartbeat.class, 15, MINUTES)
                .addTag("VPN-heartbeat")
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, KEEP, workRequest);
    }

    /**
     * Stop the VPN service monitor.
     *
     * @param context The application context.
     */
    public static void stop(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME);
    }
}
