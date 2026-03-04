# Known Issues & Limitations

This document lists known issues, limitations, and potential problems with the current Android 14 (API 34) port of VirtualApp.

---

## Critical

### 1. Xposed Framework Incompatible on Android 14+

**Status:** Known, mitigated (auto-disabled)

Epic and SandHook, the runtime engines for Xposed support, rely on inline-hooking ART methods. Android 14 changed ART method structures, breaking these hooks. Xposed is auto-disabled on API 34+ at first launch.

**Impact:** Xposed modules will not work on Android 14 devices unless the user manually re-enables (at their own risk) via Settings.

**Workaround:** Users can toggle "Disable Xposed" in Settings. If it crashes, clear app data to reset.

**Future fix:** Requires porting to LSPosed's hooking engine or updating Epic to support Android 14 ART internals.

---

### 2. IPCThreadState Symbols Not Resolved

**Status:** Known, mitigated (falls back to getuid)

On some Android 14 builds (including tested Xiaomi MIUI), `dlsym(RTLD_DEFAULT, "_ZN7android14IPCThreadState4selfEv")` returns null. This means the IPCThreadState fallback in `getCallingUid_hook` is unavailable.

**Impact:** When ArtMethod pointer swap works (which it does on our test device), this is not an issue. If the swap ever fails, the third fallback (`getuid()`) returns the host app's UID rather than the real calling UID, which may cause permission issues for some operations.

**Diagnostic:** Check `logcat` for: `hookAndroidVM: IPCThreadState_self=0x0, native_getCallingUid=0x0`

---

## Moderate

### 3. Fake Signature on Certificate Collection Failure

**Status:** Known, mitigated

If `PackageParser.collectCertificates()` fails on a particular ROM, VirtualApp falls back to a fake signature constant. Apps that verify their own signature (e.g., Google Play Services, banking apps) will detect a mismatch.

**Impact:** Some apps may refuse to run or show "tampered" warnings.

**Workaround:** Use apps that don't perform strict signature verification, or install a Xposed module for signature spoofing (requires Xposed to be working).

---

### 4. SELinux Denials for Cloned Apps

**Status:** Known, no fix

Cloned apps run under the host app's SELinux context (`untrusted_app_30`), not their own. This causes `avc: denied` logs for:
- `vendor_display_prop` reads
- `cache_file` directory access
- `mnt_pass_through_file`, `mnt_media_rw_file`, `mnt_product_file`, `mnt_vendor_file` access

**Impact:** Some functionality (display properties, certain storage paths) may be unavailable. Most apps work fine despite these denials.

**Workaround:** Root + `setenforce 0` or Magisk SELinux policy patches. Not recommended for production.

---

### 5. Storage Path Limitations

**Status:** Known, partial fix

`VEnvironment` warns `Unable to create the directory: /storage/emulated/0/VirtualApp/vsdcard` on Android 14 due to scoped storage restrictions.

**Impact:** Apps relying on external storage in the virtual SD card path may not have write access. Internal storage works fine.

**Workaround:** Grant `MANAGE_EXTERNAL_STORAGE` permission to the host app or use apps that work with internal storage.

---

### 6. BinderInvocationStub Build Failures

**Status:** Known, non-fatal

Three `MethodInvocationStub` stubs fail to build with `Unable to build HookDelegate: BinderInvocationStub`. These appear early during virtual process initialization.

**Impact:** Some Binder service hooks may be missing. Most critical services still work.

**Diagnostic:** Check `logcat` for: `MethodInvocationStub: Unable to build HookDelegate: BinderInvocationStub`

---

## Minor

### 7. AutoFillManager Injection Error (MIUI)

**Status:** Vendor-specific, non-fatal

`AutoFillManagerStub: AutoFillManagerStub inject error` appears on Xiaomi/MIUI devices. MIUI's custom AutoFillManager class structure differs from AOSP.

**Impact:** Autofill in cloned apps may not work on MIUI devices. No crash.

---

### 8. Slow First Launch of Cloned Apps

**Status:** Known, expected

First launch of a cloned app takes 2-4 seconds longer than subsequent launches due to:
- APK re-parsing if cache was invalid
- DEX optimization (dex2oat) on first run
- Native hook installation

**Impact:** User-visible delay on first launch only. Subsequent launches are faster.

---

### 9. compileSdkVersion 34 with AGP 7.4.2

**Status:** Known, cosmetic warning

The project compiles against SDK 34 (Android 14) but uses AGP 7.4.2 which was tested up to compileSdk 33. A warning is shown during build but everything works correctly.

**Impact:** A non-fatal warning during build. No functional impact.

**Future fix:** Upgrade AGP to 8.x when Gradle wrapper is updated.

---

### 10. 32-bit (armeabi-v7a) Untested on Real Hardware

**Status:** Builds successfully, untested

The native code builds for both `arm64-v8a` and `armeabi-v7a`. 32-bit syscall numbers (`__NR_fstatat64`) are conditionally compiled. All primary testing has been on ARM64.

**Impact:** 32-bit ARM devices may work but are unverified. The And64InlineHook engine is ARM64-only; 32-bit hooking uses Cydia Substrate.

---

## Device-Specific Notes

### Xiaomi Redmi (sunstone) — Android 14, MIUI OS 2.0.6.0

This is the primary test device. Known behaviors:
- `IPCThreadState` dlsym returns null (expected)
- `AutoFillManagerStub` injection fails (MIUI-specific)
- `QXPerformance.jar` is loaded as a system framework jar (Qualcomm-specific)
- `mi_exception_log` write errors appear but are cosmetic

### Other devices

No other devices have been tested. Fixes are designed to be broadly compatible:
- BTI fix: `BTI jc` is a `NOP` on non-BTI hardware (safe on all ARM64)
- `@CriticalNative` fix: Uses raw JNI signature which works on all ART versions
- ArraySet fix: Type-checks at runtime, handles both `ArrayList` and `ArraySet`
- PackageParser fallback: Auto-detects mirror breakage, works on any ROM

---

## Reporting New Issues

When reporting issues, include:

1. **Device:** Model, Android version, ROM version
2. **Logs:** `adb logcat -v brief -s "VA++:*" "NativeEngine:*" "AndroidRuntime:*" "DEBUG:*"`
3. **Crash buffer:** `adb logcat -b crash -d`
4. **Steps:** What app was cloned, what action triggered the issue
5. **Build:** Whether using debug or release APK
