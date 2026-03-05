package com.lody.virtual.client.hiddenapibypass;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Bypass hidden API restrictions on Android 9+ (including Android 14-16+).
 * <p>
 * Uses a 3-tier approach:
 * <ol>
 *   <li>LSPosed HiddenApiBypass (Unsafe + setHiddenApiExemptions) — works Android 9-16+, stable</li>
 *   <li>Meta-reflection (getDeclaredMethod → setHiddenApiExemptions) — works Android 9-11</li>
 *   <li>ADB settings global (policy override) — works when device is in debug/dev mode</li>
 * </ol>
 * <p>
 * Note: For Android 16+ where symbols may be stripped from libart.so, the LSPosed
 * library internally uses Unsafe-based approach that doesn't rely on native symbols,
 * making it reliable even when VMRuntime_setHiddenApiExemptions is stripped from ART.
 */
public final class HiddenApiBypass {

    private static final String TAG = "HiddenApiBypass";
    private static boolean sExempted = false;

    private HiddenApiBypass() {}

    /**
     * Exempt all hidden APIs. Call as early as possible (Application.attachBaseContext).
     * Safe to call multiple times - will short-circuit after first success.
     *
     * @return true if successful, false otherwise
     */
    public static boolean exemptAll() {
        if (sExempted) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 28) {
            // No restriction before Android P
            sExempted = true;
            return true;
        }

        // Tier 1: LSPosed HiddenApiBypass (uses Unsafe + MethodHandle, works reliably on Android 9-16+)
        // This is the most reliable approach as it doesn't rely on ART internal symbols
        try {
            boolean result = org.lsposed.hiddenapibypass.HiddenApiBypass
                    .addHiddenApiExemptions("L");
            if (result) {
                Log.i(TAG, "Tier 1: LSPosed HiddenApiBypass successful (Unsafe + setHiddenApiExemptions)");
                sExempted = true;
                return true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "Tier 1: LSPosed HiddenApiBypass failed", e);
        }

        // Tier 2: Meta-reflection (works on Android 9-11, may be blocked on 12+)
        try {
            Method getDeclaredMethod = Class.class.getDeclaredMethod(
                    "getDeclaredMethod", String.class, Class[].class);
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = (Method) getDeclaredMethod.invoke(vmRuntimeClass,
                    "getRuntime", null);
            Method setHiddenApiExemptions = (Method) getDeclaredMethod.invoke(vmRuntimeClass,
                    "setHiddenApiExemptions", new Class[]{String[].class});
            Object vmRuntime = getRuntime.invoke(null);
            setHiddenApiExemptions.invoke(vmRuntime, new Object[]{new String[]{"L"}});
            Log.i(TAG, "Tier 2: Meta-reflection bypass successful");
            sExempted = true;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Tier 2: Meta-reflection bypass failed", e);
        }

        // Tier 3: Direct VMRuntime via known reflection path
        // On some builds, direct Class.forName + getMethod still works for hidden APIs
        // that have been exempted at the process level via ADB or settings
        try {
            Class<?> vmRuntimeClass = Class.forName("dalvik.system.VMRuntime");
            java.lang.reflect.Method getRuntime = vmRuntimeClass.getMethod("getRuntime");
            Object runtime = getRuntime.invoke(null);
            java.lang.reflect.Method setExemptions = vmRuntimeClass.getMethod(
                    "setHiddenApiExemptions", String[].class);
            setExemptions.invoke(runtime, new Object[]{new String[]{"L"}});
            Log.i(TAG, "Tier 3: Direct VMRuntime bypass successful");
            sExempted = true;
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Tier 3: Direct VMRuntime bypass failed", e);
        }

        Log.e(TAG, "All hidden API bypass tiers failed on API " + Build.VERSION.SDK_INT);
        return false;
    }

    /**
     * Check if hidden API exemption was already applied successfully.
     */
    public static boolean isExempted() {
        return sExempted;
    }
}
