package com.lody.virtual.client.hiddenapibypass;

import android.os.Build;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * Bypass hidden API restrictions on Android 9+ (including Android 14+).
 * <p>
 * Uses LSPosed's HiddenApiBypass library as the primary approach,
 * which works reliably on Android 14 (API 34) using Unsafe + MethodHandle.
 * Falls back to meta-reflection for older Android versions.
 */
public final class HiddenApiBypass {

    private static final String TAG = "HiddenApiBypass";

    private HiddenApiBypass() {}

    /**
     * Exempt all hidden APIs. Call as early as possible (Application.attachBaseContext).
     *
     * @return true if successful, false otherwise
     */
    public static boolean exemptAll() {
        if (Build.VERSION.SDK_INT < 28) {
            // No restriction before Android P
            return true;
        }

        // Primary: LSPosed HiddenApiBypass (works on Android 14+)
        try {
            boolean result = org.lsposed.hiddenapibypass.HiddenApiBypass
                    .addHiddenApiExemptions("L");
            if (result) {
                Log.i(TAG, "LSPosed HiddenApiBypass successful");
                return true;
            }
        } catch (Throwable e) {
            Log.w(TAG, "LSPosed HiddenApiBypass failed", e);
        }

        // Fallback: meta-reflection (works on Android 9-11)
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
            Log.i(TAG, "Meta-reflection bypass successful");
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Meta-reflection bypass also failed", e);
        }

        return false;
    }
}
