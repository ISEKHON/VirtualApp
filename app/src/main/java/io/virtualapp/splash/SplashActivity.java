package io.virtualapp.splash;

import android.os.Bundle;

import com.lody.virtual.client.core.VirtualCore;

import io.virtualapp.abs.ui.VActivity;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.home.NewHomeActivity;

public class SplashActivity extends VActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // No setContentView needed — WelcomeTheme shows splash.xml as window background

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
