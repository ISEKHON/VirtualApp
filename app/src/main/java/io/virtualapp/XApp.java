package io.virtualapp;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.lody.virtual.client.NativeEngine;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.stub.VASettings;

import java.io.File;
import java.io.IOException;

import io.virtualapp.delegate.MyVirtualInitializer;

/**
 * @author Lody
 */
public class XApp extends Application {

    private static final String TAG = "XApp";

    public static final String XPOSED_INSTALLER_PACKAGE = "de.robv.android.xposed.installer";

    private static XApp gApp;

    public static XApp getApp() {
        return gApp;
    }

    @Override
    protected void attachBaseContext(Context base) {
        gApp = this;
        super.attachBaseContext(base);
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            NativeEngine.disableJit(Build.VERSION.SDK_INT);
//        }
        VASettings.ENABLE_IO_REDIRECT = true;
        VASettings.ENABLE_INNER_SHORTCUT = false;
        try {
            VirtualCore.get().startup(base);
        } catch (Throwable e) {
            e.printStackTrace();
        }
        // On Android 14+ (API 34+), auto-disable Xposed on first launch since
        // Epic/SandHook may be incompatible. Users can re-enable from Settings.
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                File disableXposedFile = base.getFileStreamPath(".disable_xposed");
                File xposedApiChecked = base.getFileStreamPath(".xposed_api34_checked");
                if (!xposedApiChecked.exists()) {
                    // First time on API 34+: disable Xposed by default
                    if (!disableXposedFile.exists()) {
                        disableXposedFile.createNewFile();
                        Log.i(TAG, "Auto-disabled Xposed on API " + Build.VERSION.SDK_INT + " (can re-enable from Settings)");
                    }
                    xposedApiChecked.createNewFile();
                }
            } catch (IOException e) {
                Log.w(TAG, "Failed to auto-configure Xposed settings", e);
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        VirtualCore virtualCore = VirtualCore.get();
        virtualCore.initialize(new MyVirtualInitializer(this, virtualCore));
    }

}
