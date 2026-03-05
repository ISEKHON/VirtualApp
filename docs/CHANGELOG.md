# Changelog

All notable changes to VirtualApp are documented in this file.

---

## [Unreleased] — Android 11-16 (API 30-36) Comprehensive Compatibility

### Summary

Major compatibility overhaul addressing Android 11 through 16. Fixes critical crashes (EditText/IME ANR, directory creation failure), adds comprehensive Android 12-16 manifest attributes, foreground service compliance, broadcast receiver safety, and improved GMS/32-bit support.

### Added

- **Android 16 `ClientTransactionHandler` support** — Reflection-based delegation for `handleLaunchActivity`, `handleResumeActivity`, `handlePauseActivity`, `handleStartActivity`, `handleStopActivity`, `handleDestroyActivity` with Android 16's new parameter signatures
- **Android 16 `HCallbackStub` EXECUTE_TRANSACTION** — Handles both `getCallbacks()` (pre-16) and `getTransactionItems()` (Android 16) for `ClientTransaction` processing
- **Android 16 `HiddenApiBypass`** — 3-tier approach with LSPosed `hiddenapibypass:6.1` (Unsafe), meta-reflection, and VMRuntime fallback with caching
- **`BuildCompat` version helpers** — Added `isSv2()` (API 32), `isT()` (API 33), `isU()` (API 34), `isV()` (API 35), `isW()` (API 36) for version-gated compatibility checks
- **`POST_NOTIFICATIONS` permission** — Required for Android 13+ notification runtime permission
- **Foreground service type permissions** — `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION` for Android 14+ per-type requirements
- **`CmdReceiver` `gms` command** — Install Google Mobile Services from host device via ADB: `am broadcast -n ... --es cmd gms`
- **x86_64 ABI support** — Native libraries now built for `arm64-v8a`, `armeabi-v7a`, and `x86_64` (emulator support)

### Changed

- **`AutoFillManagerStub`** — Complete rewrite using `SafeAutofillSessionProxy`:
  - Intercepts `startSession`/`updateOrRestartSession` without calling through to system_server
  - Finds `IResultReceiver` argument and signals `NO_SESSION` (Integer.MIN_VALUE) to prevent 5-second `SyncResultReceiver` timeout
  - Returns safe default values for different method return types
  - **Root cause:** System_server rejects autofill sessions because host UID doesn't match virtual app's `ComponentName` → `SecurityException` → `IResultReceiver` never called → `SyncResultReceiver.getIntResult()` blocks main thread 5 seconds → ANR
- **`VEnvironment.getVirtualPrivateStorageDir()`** — 3-tier fallback for scoped storage:
  1. Traditional path: `/storage/emulated/0/Android/data/<host>/virtual/<userId>` 
  2. Fallback: `getExternalFilesDir(null)` → `/storage/emulated/0/Android/data/<host>/files/virtual/<userId>`
  3. Last resort: `getFilesDir()` → internal storage
- **`DaemonService.startup()`** — Uses `startForegroundService()` on Android 8+ (was `startService()` which fails on Android 12+ background start restrictions)
- **`BroadcastSystem.registerReceiverCompat()`** — Uses `RECEIVER_NOT_EXPORTED` flag (0x4) on Android 13+ to prevent `SecurityException` from unspecified receiver export flags
- **`Application.mk`** — APP_PLATFORM updated from `android-14` to `android-21` matching minSdkVersion; builds all three ABIs
- **`CmdReceiver` docs** — Updated with explicit component targeting requirement for Android 14+, added `gms` command

### Fixed

- **EditText/IME crash (ANR)** — Twitter, Facebook, and other apps no longer freeze when typing in text fields. Fixed the AutofillManager UID mismatch that caused a 5-second main-thread timeout.
- **`Unable to create the directory` error** — `VEnvironment.getVirtualPrivateStorageDir()` now gracefully falls back when scoped storage prevents `mkdirs()` under `/storage/emulated/0/Android/data/`
- **`android:exported` missing** — Added explicit `exported` attributes to:
  - `EmptyActivity` in app-ext and app-plugin manifests (`exported="true"` — has intent-filters)
  - `DaemonService`, `DaemonService$InnerService` (`exported="false"`)
  - `StubPendingActivity`, `StubPendingService`, `StubPendingReceiver` (`exported="false"`)
  - `StubJob` (`exported="true"` — needed for system JobScheduler)
- **Background service crash on Android 12+** — `DaemonService` now uses `startForegroundService()` instead of `startService()` from background
- **`SecurityException` on receiver registration** — `BroadcastSystem` now specifies `RECEIVER_NOT_EXPORTED` on API 33+

---

## [Previous] — Android 14 (API 34) Compatibility Port

### Summary

Comprehensive port to make VirtualApp fully functional on Android 14. Prior to these changes, the app could not install or launch any cloned apps on API 34 devices. After all patches, cloned apps install persistently and launch successfully.

### Added

- **ABI split APKs** — Builds separate APKs per ABI (`arm64-v8a`, `armeabi-v7a`) plus a universal APK, named `VirtualApp-<version>-<abi>.apk`
- **32-bit device support** — `armeabi-v7a` ABI added; 32-bit blocking check removed from `ListAppFragment`
- **`IOUniformer.cpp` 32-bit fix** — Conditional `__NR_fstatat64` (32-bit) vs `__NR_newfstatat` (64-bit) syscall numbers
- **`PackageParserCompat34.java`** — New pure-reflection fallback class for Android 14+ package parsing when mirror stubs are broken
- **Android 14 `ClientTransactionHandler` methods:**
  - `handleResumeActivity(ActivityClientRecord, boolean, boolean, boolean, String)` — new `shouldSendCompatFakeFocus` parameter
  - `handlePauseActivity(ActivityClientRecord, boolean, boolean, int, boolean, PendingTransactionActions, String)` — new `autoEnteringPip` parameter
  - `handleLaunchActivity(ActivityClientRecord, PendingTransactionActions, int, Intent)` — new `deviceId` parameter
  - `handleStartActivity(ActivityClientRecord, PendingTransactionActions, int, ActivityOptions)` — new `deviceId` parameter
  - `getPackageInfoNoCheck(ApplicationInfo)` — single-param overload
- **Xposed auto-disable** on API 34+ with Settings toggle to re-enable
- **IPCThreadState resolution for ART** — `dlsym` now runs unconditionally (was Dalvik-only)
- **Diagnostic logging** in `measureNativeOffset`, `replaceGetCallingUid`, `hookAndroidVM`
- **Clone app search** — Search/filter by app name or package name in the clone app list
- **Redesigned home screen** — Clean Material Design with white background, blue toolbar, larger icons
- **Redesigned splash screen** — Branded splash with app icon, name, tagline on primary color background
- **App renamed** — From "VirtualXposed" to "VirtualApp"
- **Product flavors removed** — Single `main` source set (no more `aosp`/`fdroid`)
- **compileSdk upgraded** to 34 (from 30)
- **`docs/` directory** with comprehensive project documentation

### Changed

- **`And64InlineHook.cpp`** — ARM64 BTI (Branch Target Identification) fix:
  - `A64_MAX_INSTRUCTIONS` increased from 5 to 6
  - All long-range hooks now prepend `BTI jc` (`0xd50324df`) as a landing pad
  - Added trampoline cache flush after hook installation
  - Alignment check corrected for new instruction layout

- **`VMPatch.cpp`** — `@CriticalNative` compatibility:
  - `getCallingUid` changed from fbjni `alias_ref<jclass>` to raw `(JNIEnv*, jclass)` — never dereferences potentially-garbage params
  - Triple fallback: ArtMethod swap → IPCThreadState direct call → `getuid()`
  - `replaceGetCallingUid` uses `ToReflectedMethod` + `Executable.artMethod` on API 30+
  - Falls back to `RegisterNatives` if ArtMethod swap fails
  - `measureNativeOffset` search range increased from 100 to 200 bytes

- **`PackageParserCompat.java`** — Auto-detection of broken mirrors via `sUseFallback34` flag; routes to `PackageParserCompat34` when mirrors are incompatible

- **`PackageParserEx.java`**:
  - Certificate collection wrapped in try/catch with fake signature fallback
  - `protectedBroadcasts` accessed as raw `Object` to handle both `ArrayList` and `ArraySet`
  - `SigningInfo` constructor fallback via reflection iteration

- **`VAppManagerService.java`** — Invalid package cache triggers APK re-parse instead of app deletion

- **`SpecialComponentList.java`** — `IntentFilter.mActions` handling for `ArraySet` (Android 14 type change)

- **`MethodProxies.java` (mount)** — `getVolumeList` passes userId (not uid) on API 33+; `SecurityException` catch with `StorageManager.getStorageVolumes()` fallback

- **`NativeMethods.java`** — DexFile method lookup failure is non-fatal on API 34+

- **`NativeEngine.java`** — `launchEngine()` wrapped in try/catch for resilience

- **`VClientImpl.java`** — Xposed initialization wrapped in try/catch

- **`XApp.java`** — Creates `.disable_xposed` on first API 34+ launch

- **`SettingsActivity.java`** — Shows Xposed instability warning on API 34+

- **`NewHomeActivity.java`** — Complete rewrite:
  - Removed wallpaper system, donate dialogs, meizu alerts, xposed installer auto-install, update checker
  - Clean Material Design with white background, blue toolbar, 56dp app icons
  - Simplified FAB and toolbar menu (Clone / Reboot / Settings)

- **`ListAppActivity.java`** — Rewritten with search `EditText` and `TextWatcher` for real-time app filtering

- **`CloneAppListAdapter.java`** — Added `filter()` method for case-insensitive search by app name or package name

- **`SplashActivity.java`** — Redesigned: branded splash with centered app icon, name, and tagline; fade transition to home

- **`activity_home.xml`** — White background, blue Material toolbar with elevation, 12dp padding grid, 96dp empty state icon

- **`activity_clone_app.xml`** — Added rounded pill-shape search field with search icon

- **`colors.xml`** — Updated to Material blue palette: `#1976D2` primary, `#0D47A1` dark, `#2196F3` accent

- **`styles.xml`** — Changed to `Theme.AppCompat.Light.NoActionBar` with white window background

### Fixed

- **SIGILL crash** when launching any cloned app (BTI landing pad missing)
- **SIGSEGV null pointer** in `getCallingUid` (`@CriticalNative` passes no JNIEnv/jclass)
- **AbstractMethodError** on `handleResumeActivity` and `handlePauseActivity` (missing Android 14 overloads)
- **ClassCastException** on `ArraySet` vs `ArrayList` for `IntentFilter.mActions` and `Package.protectedBroadcasts`
- **SecurityException** in `getVolumeList` (userId vs uid on API 33+)
- **Cloned apps disappearing** after restart (cache corruption → re-parse)
- **Package parsing returning null** on Android 14 ROMs (mirror breakage → reflection fallback)
- **32-bit ARM build failure** — `__NR_newfstatat` doesn't exist on 32-bit ARM; conditionally uses `__NR_fstatat64`
- **32-bit apps rejected** — Removed `isApk64()` check that blocked 32-bit APK installation

### Technical Details

See [docs/FIXES.md](FIXES.md) for in-depth analysis of each fix with root cause, diagnosis, and code changes.

---

## [1.0.0] — Baseline

Original VirtualApp codebase supporting Android 5.0–13.0. This version was the starting point for the Android 14 port.
