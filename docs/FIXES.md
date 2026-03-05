# Android 14-16 (API 34-36) Compatibility Fixes

This document details every fix applied to make VirtualApp work on Android 14-16, organized by crash type and subsystem.

---

## Table of Contents

1. [Native Crashes (C/C++)](#1-native-crashes-cc)
   - [1.1 SIGILL — BTI (Branch Target Identification)](#11-sigill--bti-branch-target-identification)
   - [1.2 SIGSEGV — @CriticalNative getCallingUid](#12-sigsegv--criticalnative-getcallinguid)
2. [Java Crashes](#2-java-crashes)
   - [2.1 AbstractMethodError — ClientTransactionHandler](#21-abstractmethoderror--clienttransactionhandler)
   - [2.2 ArraySet ClassCastException](#22-arrayset-classcastexception)
   - [2.3 SecurityException — getVolumeList](#23-securityexception--getvolumelist)
3. [Package Parsing Failures](#3-package-parsing-failures)
   - [3.1 PackageParser Mirror Breakage](#31-packageparser-mirror-breakage)
   - [3.2 Certificate Collection Failure](#32-certificate-collection-failure)
   - [3.3 SigningInfo Constructor Missing](#33-signinginfo-constructor-missing)
   - [3.4 Cloned App Persistence (Cache Corruption)](#34-cloned-app-persistence-cache-corruption)
4. [Xposed/Hook Framework](#4-xposedhook-framework)
   - [4.1 Epic/SandHook ART Incompatibility](#41-epicsandhook-art-incompatibility)
5. [Miscellaneous](#5-miscellaneous)
   - [5.1 DexFile Native Method Lookup](#51-dexfile-native-method-lookup)
   - [5.2 Native Engine Launch Safety](#52-native-engine-launch-safety)

---

## 1. Native Crashes (C/C++)

### 1.1 SIGILL — BTI (Branch Target Identification)

**File:** `lib/src/main/jni/A64Inlinehook/And64InlineHook.cpp`

**Symptom:** Every cloned app crashed instantly with `SIGILL` (signal 4, `ILL_ILLOPC`) approximately 350ms after launch. The crash occurred in worker threads within hooked libc functions.

**Root Cause:** Android 14 compiles system libraries (`libc.so`, `libm.so`, etc.) with ARM BTI (Branch Target Identification) enabled — an ARMv8.5 security feature. BTI requires that every indirect branch (via `BR`/`BLR` through PLT stubs) lands on a valid `BTI` instruction. The `And64InlineHook` library (circa 2018) overwrites function prologues with `LDR X17, #8; BR X17; <addr>` — none of which are BTI instructions. When libc calls a hooked function through its PLT, the CPU faults because it lands on a non-BTI instruction.

**Fix:** Prepend `BTI jc` (`0xd50324df`) to every long-range hook. `BTI jc` is:
- A valid landing pad for both `BR` and `BLR` callers on BTI-enabled hardware
- Decoded as `NOP` on non-BTI hardware (backward compatible)

```cpp
#define A64_MAX_INSTRUCTIONS 6  // was 5 — extra slot for BTI
#define A64_BTI_JC 0xd50324dfu

// Hook layout:
//   BTI jc              ← landing pad
//   [NOP]               ← only if alignment needed for 64-bit literal
//   LDR X17, #8         ← load target address
//   BR  X17             ← jump to hook function
//   <64-bit address>    ← hook function pointer
```

Also added trampoline cache flush (`__builtin___clear_cache`) after writing hook instructions.

**Verification:** After this fix, `SIGILL` crashes were completely eliminated.

---

### 1.2 SIGSEGV — @CriticalNative getCallingUid

**File:** `lib/src/main/jni/Foundation/VMPatch.cpp`

**Symptom:** After fixing SIGILL, cloned apps crashed with `SIGSEGV` (signal 11, null pointer dereference at `0x0`) in the `getCallingUid` function of `libva++.so`, called from `Settings$NameValueCache.isCallerExemptFromReadableRestriction` (a new Android 14 method).

**Root Cause:** On Android 14, `Binder.getCallingUid()` is annotated `@CriticalNative`. This is an ART optimization where the JNI bridge does **not** pass `JNIEnv*` or `jclass` parameters — the function is called with zero arguments. The original hook used fbjni's `alias_ref<jclass>` parameter which was actually null/garbage. When fbjni dereferenced it (to call `.get()`), it caused a null pointer dereference.

**Fix (multi-layered):**

1. **Changed function signature** from fbjni `alias_ref<jclass>` to raw `(JNIEnv* env, jclass clazz)` — never dereferences either parameter since they're garbage on `@CriticalNative`:

```cpp
static jint getCallingUid_hook(JNIEnv *env, jclass clazz) {
    // env and clazz may be garbage — never dereference them
    // Use Environment::ensureCurrentThreadIsAttached() for real JNIEnv
}
```

2. **Triple fallback for getting the real UID:**
   - Primary: Forward call to original `jni_orig_getCallingUid` (garbage params are safe — original also ignores them)
   - Secondary: Call `IPCThreadState::self()->getCallingUid()` directly via dlsym
   - Tertiary: Fall back to `getuid()`

3. **ArtMethod address on API 30+:** On API 30+, `jmethodID` may not equal the real `ArtMethod` address. Now uses `Executable.artMethod` field via reflection (`ToReflectedMethod` + `GetLongField`).

4. **registerNatives fallback:** If ArtMethod pointer swap fails (wrong offset, null pointer), falls back to `RegisterNatives` API which is always safe.

5. **IPCThreadState resolved for ART too:** Previously, `IPCThreadState_self` and `native_getCallingUid` were only resolved for Dalvik. Now resolved unconditionally via `dlsym(RTLD_DEFAULT, ...)` for use as ART fallback.

6. **Increased `measureNativeOffset` search range** from 100 to 200 bytes with detailed diagnostic logging.

**Verification:** SIGSEGV in `getCallingUid` completely eliminated. Diagnostic logs confirm: `measureNativeOffset: found at offset 16`, `replaceGetCallingUid: ArtMethod swap OK`.

---

## 2. Java Crashes

### 2.1 AbstractMethodError — ClientTransactionHandler

**Files:**
- `lib/src/main/java/android/app/ClientTransactionHandler.java`
- `lib/src/main/java/android/app/TransactionHandlerProxy.java`

**Symptom:** After fixing native crashes, cloned apps crashed with `AbstractMethodError` on `handleResumeActivity` and `handlePauseActivity` when Android 14's `ResumeActivityItem.execute()` or `PauseActivityItem.execute()` called these methods on our `TransactionHandlerProxy`.

**Root Cause:** Android 14 added new parameters to several `ClientTransactionHandler` methods:
- `handleResumeActivity` gained `boolean shouldSendCompatFakeFocus`
- `handlePauseActivity` gained `boolean autoEnteringPip`
- `handleLaunchActivity` gained `int deviceId`
- `handleStartActivity` gained `int deviceId`
- `getPackageInfoNoCheck` gained a single-param overload

Since `TransactionHandlerProxy` extends `ClientTransactionHandler` but was missing these overloads, ART threw `AbstractMethodError` when the framework called them.

**Fix:** Added abstract declarations in `ClientTransactionHandler.java` and concrete override implementations in `TransactionHandlerProxy.java` for all new Android 14 method signatures. Each override delegates to `originalHandler`.

```java
// Android 14
public abstract void handleResumeActivity(ActivityClientRecord record,
    boolean finalStateRequest, boolean isForward,
    boolean shouldSendCompatFakeFocus, String reason);

// Android 14
public abstract void handlePauseActivity(ActivityClientRecord r,
    boolean finished, boolean userLeaving, int configChanges,
    boolean autoEnteringPip, PendingTransactionActions pendingActions, String reason);
```

The `handleLaunchActivity(r, pendingActions, deviceId, customIntent)` 4-param version also includes the full `StubActivityRecord` extraction and process restart logic.

---

### 2.2 ArraySet ClassCastException

**Files:**
- `lib/src/main/java/com/lody/virtual/server/pm/parser/PackageParserEx.java`
- `lib/src/main/java/com/lody/virtual/client/env/SpecialComponentList.java`

**Symptom:** `ClassCastException: android.util.ArraySet cannot be cast to java.util.ArrayList` during app startup when iterating `protectedBroadcasts` or `IntentFilter.mActions`.

**Root Cause:** Android 14 changed internal collection types from `ArrayList` to `ArraySet` in:
- `Package.protectedBroadcasts` — was `ArrayList<String>`, now `ArraySet<String>`
- `IntentFilter.mActions` — was `ArrayList<String>`, now `ArraySet<String>`

**Fix:** Both files now access these fields as raw `Object`, check the runtime type, and handle both `List` and `Collection` (ArraySet) paths:

```java
Object rawBroadcasts = Package.protectedBroadcasts.get(p);
if (rawBroadcasts instanceof java.util.Collection) {
    cache.protectedBroadcasts = new ArrayList<>((Collection<String>) rawBroadcasts);
}
```

For `IntentFilter.mActions`, when it's an `ArraySet` (no `ListIterator`), a new `ArrayList` is built with the filtered/transformed actions, then the field is replaced via reflection.

---

### 2.3 SecurityException — getVolumeList

**File:** `lib/src/main/java/com/lody/virtual/client/hook/proxies/mount/MethodProxies.java`

**Symptom:** `SecurityException` from `IStorageManager.getVolumeList()` causing app crashes during storage access.

**Root Cause:** On Android 13+ (API 33), `getVolumeList()` takes a **userId** (e.g., 0) rather than a **uid** (e.g., 10150). Passing a raw uid caused the system to reject the request.

**Fix:**
1. On API 33+, pass `VUserHandle.getUserId(getRealUid())` instead of raw uid
2. Added `SecurityException` catch with fallback to `StorageManager.getStorageVolumes()` using reflection
3. Uses a `ThreadLocal<Boolean>` bypass flag to prevent recursive hook invocation during fallback

---

## 3. Package Parsing Failures

### 3.1 PackageParser Mirror Breakage

**Files:**
- `lib/src/main/java/com/lody/virtual/helper/compat/PackageParserCompat.java`
- `lib/src/main/java/com/lody/virtual/helper/compat/PackageParserCompat34.java` **(new file)**

**Symptom:** All mirror-based `PackageParser` methods (`generateApplicationInfo`, `generateActivityInfo`, etc.) returned null or threw exceptions on Android 14, causing all cloned apps to appear as "not installed."

**Root Cause:** Android 14 refactored `PackageParser` internals — `PackageUserState` gained new constructors, inner classes were removed, and method signatures changed. The compiled mirror stubs (targeting Android 10-12) hit `NoSuchMethodError` at class init time.

**Fix:** Created `PackageParserCompat34` — a pure-reflection fallback that discovers all needed methods at runtime. `PackageParserCompat` auto-detects broken mirrors via `sUseFallback34` flag and routes all calls through the API 34 path. Also added robust `PackageUserState` creation with three fallback strategies:
1. Static `DEFAULT` field (Android 14+ pattern)
2. `Unsafe.allocateInstance()` (works when no constructor matches)
3. `PackageParserCompat34` helper method

### 3.2 Certificate Collection Failure

**File:** `lib/src/main/java/com/lody/virtual/server/pm/parser/PackageParserEx.java`

**Symptom:** `collectCertificates` threw exceptions on some Android 14 ROMs, preventing app installation.

**Fix:** Wrapped in try/catch with fallback to a fake signature. The app can still be installed and run; only signature verification is bypassed.

### 3.3 SigningInfo Constructor Missing

**File:** `lib/src/main/java/com/lody/virtual/server/pm/parser/PackageParserEx.java`

**Symptom:** Mirror for `SigningInfo` constructor was null on Android 14.

**Fix:** Falls back to iterating all `SigningInfo` constructors via reflection, finding one that accepts the available `signingDetails` object type.

### 3.4 Cloned App Persistence (Cache Corruption)

**File:** `lib/src/main/java/com/lody/virtual/server/pm/VAppManagerService.java`

**Symptom:** Cloned apps disappeared after restart — the serialized package cache was invalid due to parser changes.

**Fix:** Instead of deleting the "corrupt" app, re-parses from the original APK file (`base.apk`) and saves a fresh cache. The APK is located first at `ps.apkPath`, falling back to the standard data directory.

---

## 4. Xposed/Hook Framework

### 4.1 Epic/SandHook ART Incompatibility

**Files:**
- `app/src/main/java/io/virtualapp/XApp.java` (formerly `app/src/aosp/java/...`)
- `lib/src/main/java/com/lody/virtual/client/VClientImpl.java`
- `app/src/main/java/io/virtualapp/settings/SettingsActivity.java`

**Symptom:** Epic and SandHook (the Xposed runtime engines) crash on Android 14 due to ART method structure changes that broke their inline hooking.

**Fix:**
1. **Auto-disable on API 34+:** On first launch, `XApp` creates `.disable_xposed` file. Uses `.xposed_api34_checked` one-time flag so re-enabling from Settings is respected.
2. **Non-fatal init:** `VClientImpl` wraps Xposed initialization in try/catch so crashes don't kill the virtual process.
3. **Settings warning:** SettingsActivity shows "Xposed may be unstable on Android 14+" when the toggle is visible.

---

## 5. Miscellaneous

### 5.1 DexFile Native Method Lookup

**File:** `lib/src/main/java/com/lody/virtual/client/natives/NativeMethods.java`

On API 34+, `DexFile` internals changed. Instead of throwing `RuntimeException` when the native method isn't found, it logs a warning and continues. The native engine skips dex override if the method reference is null.

### 5.2 Native Engine Launch Safety

**File:** `lib/src/main/java/com/lody/virtual/client/NativeEngine.java`

`launchEngine()` is wrapped in try/catch so that if `nativeLaunchEngine` throws (due to reflection failures or ART changes), the process survives and the virtual app can still attempt to run.

---

## Testing Results

| Test | Before | After |
|------|--------|-------|
| App startup | Crashes | Clean startup, all packages loaded |
| Cloned app list | Apps disappeared on restart | Persistent across restarts |
| Launch `ma.dexter` (MT Manager) | SIGILL → SIGSEGV → AbstractMethodError | Launches successfully |
| Launch `com.cpuid.cpu_z` (CPU-Z) | Same crash chain | Launches successfully |
| Native IO redirect | Disabled (API 34 guard) | Enabled, working |
| Xposed | Crashed the process | Auto-disabled, optional via Settings |

**Test device:** Xiaomi Redmi (sunstone), Snapdragon 695, Android 14 (API 34), MIUI OS 2.0.6.0

---

## 6. Android 11-16 Compatibility Fixes (Session 2)

These fixes address issues discovered across Android 11 (API 30) through Android 16 (API 36).

### 6.1 EditText/IME ANR — AutofillManager UID Mismatch

**File:** `lib/src/main/java/com/lody/virtual/client/hook/proxies/view/AutoFillManagerStub.java`

**Symptom:** Typing in any `EditText` in cloned apps (Twitter login, Facebook search, etc.) caused a 5-second freeze followed by ANR. The entire VirtualApp process became unresponsive.

**Root Cause:** When a virtual app's `EditText` receives focus, Android's `AutofillManager` calls `startSession()` on the system `IAutoFillManager` service, passing the virtual app's `ComponentName` (e.g., `com.twitter.android/.LoginActivity`). The system_server validates that the calling UID owns that package — but the calling UID is VirtualApp's host UID (e.g., 10222), not Twitter's UID (e.g., 10215). This triggers a `SecurityException` **server-side**. The exception is caught server-side, but the `IResultReceiver` callback is **never signalled**. Client-side, `SyncResultReceiver.getIntResult(5_000)` blocks the main thread for 5 seconds waiting for a response that never comes, causing the ANR.

The previous `ReplacePkgAndComponentProxy` approach (replacing the ComponentName's package with the host package) was insufficient because the system still validated the ActivityRecord association.

**Fix:** Complete rewrite with `SafeAutofillSessionProxy`:

1. **Does NOT call the real service** — overrides `call()` to prevent the binder RPC entirely
2. **Finds the `IResultReceiver`** argument via reflection by scanning method parameters for an object with a `send(int, Bundle)` method
3. **Sends `NO_SESSION`** (`Integer.MIN_VALUE`) to unblock the client immediately
4. **Returns safe defaults** based on the method's return type (false for boolean, 0 for int, null for objects)
5. **Fixed `inject()` method** — no longer returns early on mService replacement failure; still registers method proxies via the binder hook path

```java
// Key logic in SafeAutofillSessionProxy.call():
for (Object arg : args) {
    // Find IResultReceiver by looking for send(int, Bundle)
    Method sendMethod = arg.getClass().getMethod("send", int.class, Bundle.class);
    sendMethod.invoke(arg, Integer.MIN_VALUE, null); // NO_SESSION
}
```

**Verification:** Twitter login form — tapped EditText, typed `test123` — returned immediately with no ANR. Logcat confirmed: `startSession: sent NO_SESSION to receiver for virtual app`.

---

### 6.2 VEnvironment Storage Directory Failure (Android 11+)

**File:** `lib/src/main/java/com/lody/virtual/os/VEnvironment.java`

**Symptom:** `Unable to create the directory: /storage/emulated/0/Android/data/io.va.exposed64/virtual/0` — logged as error on Android 11+, preventing virtual apps from accessing their storage.

**Root Cause:** Android 11 (API 30) introduced scoped storage enforcement. Direct `File.mkdirs()` calls to paths under `/storage/emulated/0/Android/data/` fail because the app doesn't have the `MANAGE_EXTERNAL_STORAGE` permission and the MediaStore doesn't grant directory creation there.

**Fix:** 3-tier fallback in `getVirtualPrivateStorageDir(int userId)`:

1. **Tier 1:** Traditional path via direct `mkdirs()` (works on Android 10 and below)
2. **Tier 2:** `VirtualCore.get().getContext().getExternalFilesDir(null)` → `.../files/virtual/<userId>` (always writable, no special permissions)
3. **Tier 3:** `VirtualCore.get().getContext().getFilesDir()` → internal storage fallback
4. **Final:** Returns original path with warning log if all tiers fail

---

### 6.3 Missing `android:exported` Attributes (Android 12+)

**Files:**
- `app/app-ext/src/main/AndroidManifest.xml`
- `app-plugin/src/main/AndroidManifest.xml`
- `lib/src/main/AndroidManifest.xml`

**Symptom:** APK installation crash on Android 12+ (API 31) — `INSTALL_FAILED_VERIFICATION_FAILURE` because activities/services/receivers with intent-filters lacked explicit `android:exported` attribute.

**Fix:**
- `EmptyActivity` (app-ext, app-plugin): Added `android:exported="true"` (has intent-filters for launcher)
- `DaemonService`, `DaemonService$InnerService`: Added `android:exported="false"` (internal only)
- `StubPendingActivity`, `StubPendingService`, `StubPendingReceiver`: Added `android:exported="false"`
- `StubJob`: Added `android:exported="true"` (must be visible to system `JobScheduler`)

---

### 6.4 Background Service Start Restrictions (Android 8+/12+)

**File:** `lib/src/main/java/com/lody/virtual/client/stub/DaemonService.java`

**Symptom:** `IllegalStateException: Not allowed to start service Intent {...}: app is in background` on Android 12+ when `DaemonService.startup()` called `context.startService()` from background.

**Fix:**
- `startup()`: Uses `context.startForegroundService()` on API 26+ with try-catch fallback
- `onCreate()`: Uses `startForegroundService()` for `InnerService` on API 26+

---

### 6.5 Broadcast Receiver Export Flag (Android 14+)

**File:** `lib/src/main/java/com/lody/virtual/server/am/BroadcastSystem.java`

**Symptom:** `SecurityException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified` when calling `registerReceiver()` on Android 14+ (API 34).

**Fix:** Added `registerReceiverCompat()` helper method that passes raw `RECEIVER_NOT_EXPORTED` flag (int `0x4`) on API 33+. Uses raw constant because `lib/` targets compileSdk 30 where `Context.RECEIVER_NOT_EXPORTED` doesn't exist.

---

### 6.6 Missing Foreground Service Type Permissions (Android 14+)

**File:** `app/src/main/AndroidManifest.xml`

**Symptom:** `SecurityException: Starting FGS with type ... requires permission` on Android 14+ when virtual apps start foreground services.

**Fix:** Added 7 foreground service type permissions:
- `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_LOCATION`, `FOREGROUND_SERVICE_MICROPHONE`
- `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_PHONE_CALL`
- `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MEDIA_PROJECTION`
- Also added `POST_NOTIFICATIONS` for Android 13+

---

### 6.7 BuildCompat Version Helpers

**File:** `lib/src/main/java/com/lody/virtual/helper/compat/BuildCompat.java`

Added version check helpers: `isSv2()` (32), `isT()` (33), `isU()` (34), `isV()` (35), `isW()` (36).

---

### 6.8 32-bit ABI Mismatch Fix

**Files:**
- `lib/src/main/jni/Application.mk`
- `app/build.gradle`
- `lib/build.gradle`

**Symptom:** 32-bit APK split contained no `libva++.so` because `Application.mk` built `x86_64 arm64-v8a` while Gradle expected `arm64-v8a armeabi-v7a`.

**Fix:**
- `Application.mk`: Changed to `arm64-v8a armeabi-v7a x86_64`; updated `APP_PLATFORM` from `android-14` to `android-21`
- `app/build.gradle` and `lib/build.gradle`: Updated abiFilters to include all three ABIs; app splits updated

---

### 6.9 Android 15/16 Transaction Handler Compatibility

**Files (prior session):**
- `lib/src/main/java/com/lody/virtual/client/hook/proxies/am/HCallbackStub.java`
- `SceneTransitionInfo.java` (AOSP stub)

Android 15 renamed `ActivityOptions` to `SceneTransitionInfo` in `ClientTransaction` items. Added AOSP stub class and updated `HCallbackStub` to handle both old and new class names.

---

## Testing Results (Updated)

| Test | Before | After |
|------|--------|-------|
| App startup | Crashes | Clean startup, all packages loaded |
| Cloned app list | Apps disappeared on restart | Persistent across restarts |
| Launch MT Manager | SIGILL → SIGSEGV → AbstractMethodError | Launches successfully |
| Launch CPU-Z | Same crash chain | Launches successfully |
| **EditText typing (Twitter)** | **5s ANR → process unresponsive** | **Instant response, text entered** |
| **EditText typing (password)** | **ANR** | **Working** |
| **Storage directory creation** | **Failed on Android 11+** | **3-tier fallback working** |
| Native IO redirect | Disabled (API 34 guard) | Enabled, working |
| Xposed | Crashed the process | Auto-disabled, optional via Settings |
| **APK install on Android 12+** | **Blocked (missing exported)** | **Installs cleanly** |
| **32-bit APK split** | **Missing libva++.so** | **All 3 ABIs present** |

**Test environments:**
- Android 15 x86_64 emulator (API 35) — primary
- Xiaomi Redmi (sunstone), Snapdragon 695, Android 14 (API 34), MIUI OS 2.0.6.0
