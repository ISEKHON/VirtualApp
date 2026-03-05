package io.virtualapp.dev;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.remote.InstalledAppInfo;

import java.util.ArrayList;
import java.util.List;

import io.virtualapp.BuildConfig;
import io.virtualapp.home.LoadingActivity;

/**
 * ADB broadcast receiver for controlling VirtualApp via shell commands.
 *
 * Usage:
 *   adb shell am broadcast -a io.va.exposed64.CMD --es cmd <command> [--es pkg <package>] [--es uid <userId>]
 *
 * Commands:
 *   clone   <pkg>  — Clone an app from host into VirtualApp
 *   launch  <pkg>  — Launch an already-cloned app (optional --es uid <userId>)
 *   run     <pkg>  — Clone (if needed) then launch
 *   update  <pkg>  — Re-install/update a cloned app from host
 *   list           — List all cloned packages
 *   kill    <pkg>  — Force-stop a cloned app
 *   killall        — Force-stop all cloned apps
 *   reboot         — Kill all virtual apps (same as killall)
 *   uninstall <pkg>— Remove a cloned app and its data
 *   check   <pkg>  — Check if a package is cloned
 *   clear   <pkg>  — Clear data for a cloned app
 *
 * Examples:
 *   adb shell am broadcast -a io.va.exposed64.CMD --es cmd clone --es pkg com.instagram.android
 *   adb shell am broadcast -a io.va.exposed64.CMD --es cmd run --es pkg com.twitter.android
 *   adb shell am broadcast -a io.va.exposed64.CMD --es cmd list
 *   adb shell am broadcast -a io.va.exposed64.CMD --es cmd kill --es pkg com.facebook.katana
 *   adb shell am broadcast -a io.va.exposed64.CMD --es cmd uninstall --es pkg com.cpuid.cpu_z
 */
public class CmdReceiver extends BroadcastReceiver {

    private static final String TAG = "VA.CmdReceiver";
    private static final String ACTION = BuildConfig.APPLICATION_ID + ".CMD";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!ACTION.equalsIgnoreCase(action)) return;

        String cmd = intent.getStringExtra("cmd");
        if (TextUtils.isEmpty(cmd)) {
            report(context, "ERROR: No 'cmd' specified. Available: clone|launch|run|update|list|kill|killall|reboot|uninstall|check|clear");
            return;
        }
        cmd = cmd.trim().toLowerCase();

        String pkg = intent.getStringExtra("pkg");
        if (pkg != null) pkg = pkg.trim();

        int userId = 0;
        String uid = intent.getStringExtra("uid");
        if (!TextUtils.isEmpty(uid)) {
            try { userId = Integer.parseInt(uid); } catch (NumberFormatException ignored) {}
        }

        Log.i(TAG, "CMD=" + cmd + " PKG=" + pkg + " UID=" + userId);

        switch (cmd) {
            case "clone":
                cmdClone(context, pkg);
                break;
            case "launch":
                cmdLaunch(context, pkg, userId);
                break;
            case "run":
                cmdRun(context, pkg, userId);
                break;
            case "update":
                cmdUpdate(context, pkg);
                break;
            case "list":
                cmdList(context);
                break;
            case "kill":
                cmdKill(context, pkg);
                break;
            case "killall":
            case "reboot":
                cmdReboot(context);
                break;
            case "uninstall":
                cmdUninstall(context, pkg);
                break;
            case "check":
                cmdCheck(context, pkg);
                break;
            case "clear":
                cmdClear(context, pkg);
                break;
            default:
                report(context, "ERROR: Unknown command '" + cmd + "'");
                break;
        }
    }

    // ── clone ──────────────────────────────────────────────────
    private void cmdClone(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: clone requires --es pkg <package>");
            return;
        }
        if (VirtualCore.get().isAppInstalled(pkg)) {
            report(context, "ALREADY_CLONED: " + pkg);
            return;
        }
        try {
            ApplicationInfo hostInfo = context.getPackageManager().getApplicationInfo(pkg, 0);
            InstallResult result = VirtualCore.get().installPackage(
                    hostInfo.sourceDir,
                    InstallStrategy.COMPARE_VERSION | InstallStrategy.SKIP_DEX_OPT);
            if (result.isSuccess) {
                installSplitApks(context, pkg);
                report(context, "CLONED: " + pkg);
            } else {
                report(context, "CLONE_FAILED: " + pkg + " error=" + result.error);
            }
        } catch (PackageManager.NameNotFoundException e) {
            report(context, "NOT_FOUND: " + pkg + " is not installed on host");
        }
    }

    // ── launch ─────────────────────────────────────────────────
    private void cmdLaunch(Context context, String pkg, int userId) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: launch requires --es pkg <package>");
            return;
        }
        if (!VirtualCore.get().isAppInstalled(pkg)) {
            report(context, "NOT_CLONED: " + pkg);
            return;
        }
        LoadingActivity.launch(context, pkg, userId);
        report(context, "LAUNCHING: " + pkg + " (user=" + userId + ")");
    }

    // ── run (clone if needed + launch) ─────────────────────────
    private void cmdRun(Context context, String pkg, int userId) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: run requires --es pkg <package>");
            return;
        }
        if (!VirtualCore.get().isAppInstalled(pkg)) {
            cmdClone(context, pkg);
            if (!VirtualCore.get().isAppInstalled(pkg)) {
                return; // clone already reported the error
            }
        }
        cmdLaunch(context, pkg, userId);
    }

    // ── update ─────────────────────────────────────────────────
    private void cmdUpdate(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: update requires --es pkg <package>");
            return;
        }
        try {
            ApplicationInfo hostInfo = context.getPackageManager().getApplicationInfo(pkg, 0);
            InstallResult result = VirtualCore.get().installPackage(
                    hostInfo.sourceDir,
                    InstallStrategy.UPDATE_IF_EXIST | InstallStrategy.SKIP_DEX_OPT);
            if (result.isSuccess) {
                installSplitApks(context, pkg);
                report(context, "UPDATED: " + pkg);
            } else {
                report(context, "UPDATE_FAILED: " + pkg + " error=" + result.error);
            }
        } catch (PackageManager.NameNotFoundException e) {
            report(context, "NOT_FOUND: " + pkg + " is not installed on host");
        }
    }

    // ── list ───────────────────────────────────────────────────
    private void cmdList(Context context) {
        List<InstalledAppInfo> apps = VirtualCore.get().getInstalledApps(0);
        if (apps == null || apps.isEmpty()) {
            report(context, "EMPTY: No cloned apps");
            return;
        }
        PackageManager pm = context.getPackageManager();
        List<String> entries = new ArrayList<>();
        for (InstalledAppInfo app : apps) {
            String label = app.packageName;
            try {
                ApplicationInfo ai = pm.getApplicationInfo(app.packageName, 0);
                CharSequence cs = pm.getApplicationLabel(ai);
                if (cs != null) label = cs + " (" + app.packageName + ")";
            } catch (Exception ignored) {}
            entries.add(label);
        }
        report(context, "APPS[" + apps.size() + "]: " + TextUtils.join(", ", entries));
    }

    // ── kill ───────────────────────────────────────────────────
    private void cmdKill(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: kill requires --es pkg <package>");
            return;
        }
        if (!VirtualCore.get().isAppInstalled(pkg)) {
            report(context, "NOT_CLONED: " + pkg);
            return;
        }
        VirtualCore.get().killApp(pkg, 0);
        report(context, "KILLED: " + pkg);
    }

    // ── reboot / killall ───────────────────────────────────────
    private void cmdReboot(Context context) {
        VirtualCore.get().killAllApps();
        report(context, "REBOOTED: All virtual apps killed");
    }

    // ── uninstall ──────────────────────────────────────────────
    private void cmdUninstall(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: uninstall requires --es pkg <package>");
            return;
        }
        if (!VirtualCore.get().isAppInstalled(pkg)) {
            report(context, "NOT_CLONED: " + pkg);
            return;
        }
        boolean ok = VirtualCore.get().uninstallPackage(pkg);
        report(context, ok ? "UNINSTALLED: " + pkg : "UNINSTALL_FAILED: " + pkg);
    }

    // ── check ──────────────────────────────────────────────────
    private void cmdCheck(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: check requires --es pkg <package>");
            return;
        }
        boolean installed = VirtualCore.get().isAppInstalled(pkg);
        report(context, installed ? "INSTALLED: " + pkg : "NOT_INSTALLED: " + pkg);
    }

    // ── clear ──────────────────────────────────────────────────
    private void cmdClear(Context context, String pkg) {
        if (TextUtils.isEmpty(pkg)) {
            report(context, "ERROR: clear requires --es pkg <package>");
            return;
        }
        if (!VirtualCore.get().isAppInstalled(pkg)) {
            report(context, "NOT_CLONED: " + pkg);
            return;
        }
        boolean ok = VirtualCore.get().clearPackage(pkg);
        report(context, ok ? "CLEARED: " + pkg : "CLEAR_FAILED: " + pkg);
    }

    // ── Helpers ────────────────────────────────────────────────

    private void installSplitApks(Context context, String pkg) {
        try {
            PackageInfo pi = context.getPackageManager().getPackageInfo(pkg, 0);
            ApplicationInfo ai = pi.applicationInfo;
            if (ai.splitSourceDirs != null) {
                for (String splitPath : ai.splitSourceDirs) {
                    VirtualCore.get().installPackage(splitPath,
                            InstallStrategy.COMPARE_VERSION | InstallStrategy.SKIP_DEX_OPT);
                }
                Log.i(TAG, "Installed " + ai.splitSourceDirs.length + " split APKs for " + pkg);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to install splits for " + pkg, e);
        }
    }

    private void report(Context context, String message) {
        Log.i(TAG, message);
        try {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {}
        setResultCode(0);
        setResultData(message);
    }
}
