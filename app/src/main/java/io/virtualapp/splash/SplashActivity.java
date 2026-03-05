package io.virtualapp.splash;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;

import com.lody.virtual.client.core.VirtualCore;

import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.home.NewHomeActivity;

public class SplashActivity extends VActivity {

    private static final int REQUEST_MANAGE_STORAGE = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView needed — WelcomeTheme shows splash.xml as window background

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // Request MANAGE_EXTERNAL_STORAGE for virtual storage redirect
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                return;
            } catch (Throwable e) {
                // Some devices may not support this intent
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivityForResult(intent, REQUEST_MANAGE_STORAGE);
                    return;
                } catch (Throwable ignored) {
                }
            }
        }

        proceedToHome();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_STORAGE) {
            // Continue regardless of whether permission was granted
            // VEnvironment will use fallback storage if needed
            proceedToHome();
        }
    }

    private void proceedToHome() {
        VUiKit.defer().when(() -> {
            long time = System.currentTimeMillis();
            if (!VirtualCore.get().isEngineLaunched()) {
                VirtualCore.get().waitForEngine();
            }
            long delta = 800L - (System.currentTimeMillis() - time);
            if (delta > 0) {
                VUiKit.sleep(delta);
            }
        }).done((res) -> {
            NewHomeActivity.goHome(this);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
        });
    }
}
