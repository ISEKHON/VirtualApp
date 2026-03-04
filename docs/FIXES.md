# Android 14 (API 34) Compatibility Fixes

This document details every fix applied to make VirtualApp work on Android 14, organized by crash type and subsystem.

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
