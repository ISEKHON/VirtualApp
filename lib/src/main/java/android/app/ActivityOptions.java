package android.app;

/**
 * Stub for android.app.ActivityOptions to provide inner classes not present in compileSdk 30.
 * At runtime, the real framework ActivityOptions is used instead.
 * This must be public to shadow the SDK's ActivityOptions in this package.
 *
 * @author weishu
 * @date 2021/2/24.
 */

public class ActivityOptions {
    // Android 15 added SceneTransitionInfo; stub needed for compile-time compatibility.
    public static class SceneTransitionInfo {
    }
}
