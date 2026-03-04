package com.lody.virtual.client.stub;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.env.Constants;

import java.io.File;


/**
 * @author Lody
 *
 */
public class DaemonService extends Service {

    private static final int NOTIFY_ID = 1001;

	static boolean showNotification = true;

	public static void startup(Context context) {
		File flagFile = context.getFileStreamPath(Constants.NO_NOTIFICATION_FLAG);
		if (Build.VERSION.SDK_INT >= 25 && flagFile.exists()) {
			showNotification = false;
		}

		context.startService(new Intent(context, DaemonService.class));
		if (VirtualCore.get().isServerProcess()) {
			// PrivilegeAppOptimizer.notifyBootFinish();
			DaemonJobService.scheduleJob(context);
		}
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		startup(this);
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	private static Notification buildNotification(Context context) {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
			if (nm != null) {
				NotificationChannel channel = new NotificationChannel(
					"va_daemon", "Virtual App Daemon", NotificationManager.IMPORTANCE_MIN);
				channel.setShowBadge(false);
				nm.createNotificationChannel(channel);
			}
			return new Notification.Builder(context, "va_daemon")
					.setSmallIcon(android.R.drawable.ic_menu_manage)
					.build();
		} else {
			//noinspection deprecation
			return new Notification.Builder(context).build();
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		if (!showNotification) {
			return;
		}
		try {
			startService(new Intent(this, InnerService.class));
			startForeground(NOTIFY_ID, buildNotification(this));
		} catch (Exception e) {
			Log.w("DaemonService", "startForeground failed: " + e.getMessage());
		}
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		return START_STICKY;
	}

	public static final class InnerService extends Service {

        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            try {
                startForeground(NOTIFY_ID, buildNotification(this));
                stopForeground(true);
            } catch (Exception e) {
                Log.w("DaemonService", "InnerService startForeground failed: " + e.getMessage());
            }
            stopSelf();
            return super.onStartCommand(intent, flags, startId);
        }

		@Override
		public IBinder onBind(Intent intent) {
			return null;
		}
	}


}
