package io.virtualapp.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.support.design.widget.FloatingActionButton;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.lody.virtual.client.core.VirtualCore;

import java.util.concurrent.atomic.AtomicBoolean;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.home.adapters.HomeAppAdapter;
import io.virtualapp.home.models.AppData;
import io.virtualapp.home.models.PackageAppData;
import io.virtualapp.home.repo.AppRepository;
import io.virtualapp.settings.SettingsActivity;

/**
 * Main home screen — shows cloned apps in a clean grid.
 */
public class NewHomeActivity extends AppCompatActivity {

    private static final String SHOW_DOZE_ALERT_KEY = "SHOW_DOZE_ALERT_KEY";
    public static final String SHARED_PREFERENCES_KEY = "com.android.launcher3.prefs";
    private static final String TAG = NewHomeActivity.class.getSimpleName();
    private Handler mUiHandler;
    private boolean mDirectlyBack = false;

    private RecyclerView mRecyclerView;
    private ProgressBar mProgressBar;
    private View mEmptyView;
    private HomeAppAdapter mAdapter;
    private AppRepository mRepository;

    public static void goHome(Context context) {
        Intent intent = new Intent(context, NewHomeActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);
        mUiHandler = new Handler(getMainLooper());

        // Toolbar
        Toolbar toolbar = findViewById(R.id.home_toolbar);
        setSupportActionBar(toolbar);

        // RecyclerView
        mRecyclerView = findViewById(R.id.home_recycler_view);
        mProgressBar = findViewById(R.id.home_progress_bar);
        mEmptyView = findViewById(R.id.home_empty_view);

        mRecyclerView.setLayoutManager(new GridLayoutManager(this, 4));
        mRecyclerView.setHasFixedSize(true);
        mAdapter = new HomeAppAdapter(this);
        mRecyclerView.setAdapter(mAdapter);
        mAdapter.setOnAppClickListener(new HomeAppAdapter.OnAppClickListener() {
            @Override
            public void onAppClick(AppData data, int position) {
                if (data instanceof PackageAppData) {
                    PackageAppData appData = (PackageAppData) data;
                    if (!appData.isLoading) {
                        appData.isLoading = true;
                        mAdapter.notifyItemChanged(position);
                        try {
                            LoadingActivity.launch(NewHomeActivity.this, appData.packageName, 0);
                        } catch (Throwable e) {
                            e.printStackTrace();
                        }
                        mUiHandler.postDelayed(() -> {
                            appData.isLoading = false;
                            mAdapter.notifyItemChanged(position);
                        }, 3000);
                    }
                }
            }

            @Override
            public void onAppLongClick(AppData data, int position) {
                if (data instanceof PackageAppData) {
                    showAppManageDialog((PackageAppData) data, position);
                }
            }
        });

        // FAB
        FloatingActionButton fab = findViewById(R.id.home_fab_add);
        fab.setOnClickListener(v -> ListAppActivity.gotoListApp(this));

        mRepository = new AppRepository(this);

        SharedPreferences prefs = getSharedPreferences(SHARED_PREFERENCES_KEY, Context.MODE_PRIVATE);
        mDirectlyBack = prefs.getBoolean(SettingsActivity.DIRECTLY_BACK_KEY, false);
    }

    private void showAppManageDialog(PackageAppData appData, int position) {
        String[] items = {
                getString(R.string.delete),
                getString(R.string.create_shortcut)
        };
        new AlertDialog.Builder(this)
                .setTitle(appData.name)
                .setItems(items, (dialog, which) -> {
                    if (which == 0) {
                        new AlertDialog.Builder(this)
                                .setTitle(R.string.delete)
                                .setMessage("Uninstall " + appData.name + "?")
                                .setPositiveButton(android.R.string.yes, (d, w) -> {
                                    try {
                                        VirtualCore.get().uninstallPackage(appData.packageName);
                                        mAdapter.getData().remove(position);
                                        mAdapter.notifyItemRemoved(position);
                                        updateEmptyView();
                                    } catch (Throwable e) {
                                        Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(android.R.string.no, null)
                                .show();
                    } else if (which == 1) {
                        Toast.makeText(this, R.string.create_shortcut_success, Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }

    private void loadApps() {
        mProgressBar.setVisibility(View.VISIBLE);
        mEmptyView.setVisibility(View.GONE);
        mRepository.getVirtualApps().done(apps -> runOnUiThread(() -> {
            mProgressBar.setVisibility(View.GONE);
            mAdapter.setData(apps);
            updateEmptyView();
        })).fail(err -> runOnUiThread(() -> {
            mProgressBar.setVisibility(View.GONE);
            Log.e(TAG, "Failed to load virtual apps", (Throwable) err);
            updateEmptyView();
        }));
    }

    private void updateEmptyView() {
        if (mAdapter.getItemCount() == 0) {
            mEmptyView.setVisibility(View.VISIBLE);
            mRecyclerView.setVisibility(View.GONE);
        } else {
            mEmptyView.setVisibility(View.GONE);
            mRecyclerView.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadApps();
        alertForDoze();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, 1, 0, R.string.clone_apps);
        menu.add(0, 2, 0, R.string.menu_reboot);
        menu.add(0, 3, 0, "Settings");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                ListAppActivity.gotoListApp(this);
                return true;
            case 2:
                VirtualCore.get().killAllApps();
                Toast.makeText(this, R.string.reboot_tips_1, Toast.LENGTH_SHORT).show();
                return true;
            case 3:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VCommends.REQUEST_SELECT_APP) {
            loadApps();
        }
    }

    public void startVirtualActivity(Intent intent, Bundle options, int usedId) {
        String packageName = intent.getPackage();
        if (TextUtils.isEmpty(packageName)) {
            ComponentName component = intent.getComponent();
            if (component != null) {
                packageName = component.getPackageName();
            }
        }
        if (packageName == null) {
            try {
                startActivity(intent);
                return;
            } catch (Throwable ignored) {
            }
        }
        boolean result = LoadingActivity.launch(this, packageName, usedId);
        if (!result) {
            throw new ActivityNotFoundException("can not launch activity for: " + intent);
        }
        if (mDirectlyBack) {
            finish();
        }
    }

    private void alertForDoze() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        boolean showAlert = PreferenceManager.getDefaultSharedPreferences(this).getBoolean(SHOW_DOZE_ALERT_KEY, true);
        if (!showAlert) {
            return;
        }
        if (!powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            mUiHandler.postDelayed(() -> {
                try {
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.alert_for_doze_mode_title)
                            .setMessage(R.string.alert_for_doze_mode_content)
                            .setPositiveButton(R.string.alert_for_doze_mode_yes, (dialog, which) -> {
                                try {
                                    startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                            Uri.parse("package:" + getPackageName())));
                                } catch (Throwable e) {
                                    try {
                                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                                    } catch (Throwable ex) {
                                        PreferenceManager.getDefaultSharedPreferences(this)
                                                .edit().putBoolean(SHOW_DOZE_ALERT_KEY, false).apply();
                                    }
                                }
                            })
                            .setNegativeButton(R.string.alert_for_doze_mode_no, (dialog, which) ->
                                    PreferenceManager.getDefaultSharedPreferences(this)
                                            .edit().putBoolean(SHOW_DOZE_ALERT_KEY, false).apply())
                            .show();
                } catch (Throwable ignored) {
                }
            }, 1500);
        }
    }
}
