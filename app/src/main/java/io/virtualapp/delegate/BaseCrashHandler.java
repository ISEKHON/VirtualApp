package io.virtualapp.delegate;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.pm.ApplicationInfo;
import android.os.Looper;
import android.util.Log;

import com.lody.virtual.client.VClientImpl;
import com.lody.virtual.client.core.CrashHandler;

/**
 * author: weishu on 18/3/10.
 */
public class BaseCrashHandler implements CrashHandler {

    protected static final String TAG = "XApp";

    @SuppressLint("ApplySharedPref")
    @Override
    public void handleUncaughtException(Thread t, Throwable e) {
        // Debug: log state at crash time for WebView/Chromium issues
        try {
            if (e instanceof ExceptionInInitializerError || e.getCause() instanceof NullPointerException) {
                Application app = VClientImpl.get().getCurrentApplication();
                Log.e(TAG, "Crash on thread: " + t.getName() + ", exception: " + e.getClass().getSimpleName());
                if (app != null) {
                    ApplicationInfo ai = app.getApplicationInfo();
                    Log.e(TAG, "ActivityThread.currentApplication().getApplicationInfo() = "
                            + (ai != null ? ai.packageName : "NULL!!"));
                    android.content.Context appCtx = app.getApplicationContext();
                    if (appCtx != null) {
                        ApplicationInfo ctxAi = appCtx.getApplicationInfo();
                        Log.e(TAG, "getApplicationContext().getApplicationInfo() = "
                                + (ctxAi != null ? ctxAi.packageName : "NULL!!"));
                    } else {
                        Log.e(TAG, "getApplicationContext() = NULL!!");
                    }
                } else {
                    Log.e(TAG, "ActivityThread.currentApplication() = NULL!!");
                }
            }
        } catch (Throwable t2) {
            Log.e(TAG, "Error during crash debug logging", t2);
        }

        if (t == Looper.getMainLooper().getThread()) {
            System.exit(0);
        } else {
            Log.e(TAG, "ignore uncaught exception of sub thread: " + t);
        }
    }
}
