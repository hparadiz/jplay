package in.akuj.jplay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

/**
 * Keeps the remote API reachable while Just Play is not on screen. Without it the process is
 * reclaimed soon after the user leaves, and a "play this" would find nothing listening.
 */
public final class RemoteService extends Service {
    private static final String CHANNEL = "remote";

    static void start(Context context) {
        RemoteApi.start(context);
        try {
            context.startForegroundService(new Intent(context, RemoteService.class));
        } catch (RuntimeException e) {
            Log.w("JPlay", "remote service not started; the API lives only as long as the app", e);
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Remote control", NotificationManager.IMPORTANCE_MIN));
        startForeground(0x5e4d, new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Just Play remote").setContentText("Ready for your computer").setOngoing(true).build());
        RemoteApi.start(this);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) { return START_STICKY; }
    @Override public IBinder onBind(Intent intent) { return null; }

    /** Brings the API back after the device restarts or the app is updated, before anyone opens it. */
    public static final class Boot extends BroadcastReceiver {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) start(context);
        }
    }
}
