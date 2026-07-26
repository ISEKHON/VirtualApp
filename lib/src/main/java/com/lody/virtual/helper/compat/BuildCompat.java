package com.lody.virtual.helper.compat;

import android.os.Build;

/**
 * @author Lody
 */

public class BuildCompat {

    /**
     * Highest stable API level this engine has been explicitly adapted for.
     * Future previews (API &gt; this) are treated as "at least latest known" so
     * version-gated code takes the newest branch instead of an obsolete one.
     */
    public static final int LATEST_KNOWN_API = 36; // Android 16

    /**
     * Forward-compatible "running on at least {@code api}" check. Prefer this over
     * hand-written {@code SDK_INT >= X} so behavior is consistent and preview builds
     * of {@code api} are handled via {@link #getPreviewSDKInt()}.
     */
    public static boolean isAtLeast(int api) {
        return isAndroidLevelPreview(api);
    }

    /**
     * True when the device runs the latest API this engine knows about, or newer
     * (including a not-yet-released preview). Use this to gate "newest Android"
     * handling so an unknown future release does not fall back to legacy behavior.
     */
    public static boolean isAtLeastLatestKnown() {
        return isAndroidLevel(LATEST_KNOWN_API);
    }

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

    /** Android 15 VanillaIceCream (API 35), including previews. */
    public static boolean isV() {
        return isAtLeast(35);
    }

    /** Android 16 (API 36), including previews. */
    public static boolean isW() {
        return isAtLeast(36);
    }

    private static boolean isAndroidLevelPreview(int level) {
        return (Build.VERSION.SDK_INT == level && getPreviewSDKInt() > 0)
                || Build.VERSION.SDK_INT > level;
    }

    private static boolean isAndroidLevel(int level) {
        return Build.VERSION.SDK_INT >= level;
    }
}