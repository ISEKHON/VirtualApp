package com.lody.virtual.helper.compat;

import android.os.Build;

/**
 * @author Lody
 */

public class BuildCompat {

    public static int getPreviewSDKInt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return Build.VERSION.PREVIEW_SDK_INT;
            } catch (Throwable e) {
                // ignore
            }
        }
        return 0;
    }

    public static boolean isOreo() {
        return isAndroidLevel(Build.VERSION_CODES.O);
    }

    public static boolean isPie() {
        return isAndroidLevel(Build.VERSION_CODES.P);
    }

    public static boolean isQ() {
        return isAndroidLevel(29);
    }

    public static boolean isR() {
        return isAndroidLevel(30);
    }

    public static boolean isS() {
        return isAndroidLevel(31);
    }

    /** Android 12L (API 32) */
    public static boolean isSv2() {
        return isAndroidLevel(32);
    }

    /** Android 13 Tiramisu (API 33) */
    public static boolean isT() {
        return isAndroidLevel(33);
    }

    /** Android 14 UpsideDownCake (API 34) */
    public static boolean isU() {
        return isAndroidLevel(34);
    }

    /** Android 15 VanillaIceCream (API 35) */
    public static boolean isV() {
        return isAndroidLevel(35);
    }

    /** Android 16 (API 36) */
    public static boolean isW() {
        return isAndroidLevel(36);
    }

    private static boolean isAndroidLevelPreview(int level) {
        return (Build.VERSION.SDK_INT == level && getPreviewSDKInt() > 0)
                || Build.VERSION.SDK_INT > level;
    }

    private static boolean isAndroidLevel(int level) {
        return Build.VERSION.SDK_INT >= level;
    }
}