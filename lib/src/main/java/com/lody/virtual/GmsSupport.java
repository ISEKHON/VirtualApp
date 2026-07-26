package com.lody.virtual;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import com.lody.virtual.client.core.InstallStrategy;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;

import java.util.Arrays;
import java.util.List;

/**
 * @author Lody
 */
public class GmsSupport {

    private static final String TAG = "GmsSupport";

    public static final List<String> GOOGLE_APP = Arrays.asList(
            "com.android.vending",
            "com.google.android.play.games",
            "com.google.android.wearable.app",
            "com.google.android.wearable.app.cn"
    );

    public static final List<String> GOOGLE_SERVICE = Arrays.asList(
            "com.google.android.gsf",
            "com.google.android.gms",
            "com.google.android.gsf.login",
            "com.google.android.backuptransport",
            "com.google.android.backup",
            "com.google.android.configupdater",
            "com.google.android.syncadapters.contacts",
            "com.google.android.feedback",
            "com.google.android.onetimeinitializer",
            "com.google.android.partnersetup",
            "com.google.android.setupwizard",
            "com.google.android.syncadapters.calendar"
    );

    public static boolean isGmsFamilyPackage(String packageName) {
        return packageName.equals("com.android.vending")
                || packageName.equals("com.google.android.gms");
    }

    public static boolean isGoogleFrameworkInstalled() {
        return VirtualCore.get().isAppInstalled("com.google.android.gms");
    }

    public static boolean isOutsideGoogleFrameworkExist() {
        return VirtualCore.get().isOutsideInstalled("com.google.android.gms");
    }

    /**
     * Whether the host device actually has Google Play Services installed to copy
     * from. GMS support inside the sandbox works by cloning the host's Google
     * packages, so callers should check this first and tell the user to install
     * GApps on the host if it returns false.
     */
    public static boolean isHostGmsAvailable() {
        return VirtualCore.get().isOutsideInstalled("com.google.android.gms");
    }

    private static void installPackages(List<String> list, int userId) {
        VirtualCore core = VirtualCore.get();
        for (String packageName : list) {
            if (core.isAppInstalledAsUser(userId, packageName)) {
                VLog.i(TAG, "GApp already installed, skipping: " + packageName);
                continue;
            }
            ApplicationInfo info = null;
            try {
                info = VirtualCore.get().getUnHookPackageManager().getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException e) {
                // Not present on the host — nothing to copy.
            }
            if (info == null || info.sourceDir == null) {
                VLog.w(TAG, "GApp not available on host, skipping: " + packageName);
                continue;
            }
            VLog.i(TAG, "Installing GApp into sandbox (user " + userId + "): " + packageName);
            if (userId == 0) {
                core.installPackage(info.sourceDir, InstallStrategy.DEPEND_SYSTEM_IF_EXIST);
            } else {
                core.installPackageAsUser(userId, packageName);
            }
        }
    }

    /**
     * Install the full Google stack in dependency order: GSF and GMS (the framework
     * + services the auth broker needs) before the user-facing Google apps such as
     * the Play Store. Idempotent — already-installed packages are skipped.
     */
    public static void installGApps(int userId) {
        if (!isHostGmsAvailable()) {
            VLog.w(TAG, "Host has no Google Play Services to copy; install GApps on the host first.");
        }
        installPackages(GOOGLE_SERVICE, userId);
        installPackages(GOOGLE_APP, userId);
    }

    public static void installGoogleService(int userId) {
        installPackages(GOOGLE_SERVICE, userId);
    }

    public static void installGoogleApp(int userId) {
        installPackages(GOOGLE_APP, userId);
    }
}