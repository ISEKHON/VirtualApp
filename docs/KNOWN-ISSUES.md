# Known Issues & Limitations

This document lists known issues, limitations, and potential problems with the Android 11-16 (API 30-36) port of VirtualApp.

---

## Critical

### 1. Xposed Framework Incompatible on Android 14+

**Status:** Known, mitigated (auto-disabled)

Epic and SandHook, the runtime engines for Xposed support, rely on inline-hooking ART methods. Android 14 changed ART method structures, breaking these hooks. Xposed is auto-disabled on API 34+ at first launch.

**Impact:** Xposed modules will not work on Android 14 devices unless the user manually re-enables (at their own risk) via Settings.

**Workaround:** Users can toggle "Disable Xposed" in Settings. If it crashes, clear app data to reset.

**Future fix:** Requires porting to LSPosed's hooking engine or updating Epic to support Android 14 ART internals.

---

### 2. Hidden API Bypass May Fail on Android 15-16

**Status:** Known, partially mitigated (3-tier bypass)

Google is progressively adding `VMRuntime.setHiddenApiExemptions` to the blocklist. The current 3-tier bypass (LSPosed Unsafe, meta-reflection, VMRuntime) may stop working on future Android releases.

**Impact:** If all three bypass tiers fail, all mirror class initialization fails → cascade crash. This would break VirtualApp entirely.

**Workaround:** On rooted devices, use Magisk modules that set hidden API enforcement policy to 0.

**Diagnostic:** Check `logcat` for: `HiddenApiBypass: All bypass attempts failed`

---

### 3. Background Activity Launch Restrictions (Android 14+)

**Status:** Known, partially mitigated

Android 14+ increasingly restricts launching activities from the background. VirtualApp's `ActivityStack.startActivityInNewTaskLocked()` may be blocked by the system when the host app is in the background.

**Impact:** Some app launches may fail silently if VirtualApp's host process isn't in the foreground.

**Workaround:** Keep VirtualApp's UI visible when launching apps, or use ADB commands.

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

### 5. BinderInvocationStub Build Failures

**Status:** Known, non-fatal

Some `MethodInvocationStub` stubs fail to build with `Unable to build HookDelegate: BinderInvocationStub`. These appear early during virtual process initialization.

**Impact:** Some Binder service hooks may be missing. Most critical services still work.

---

### 6. Dynamic Code Loading Restrictions (Android 14+)

**Status:** Known, partial mitigation

Android 14+ restricts execution of DEX files from writable paths. `ArtDexOptimizer.compileDex2Oat()` runs `dex2oat` directly which may be blocked by SELinux. The `DexFile` class is deprecated and may be removed.

**Impact:** First-time DEX optimization may fail. VirtualApp has fallback paths but performance may be degraded.

---

## Minor

### 7. AutoFillManager Injection Warning (MIUI)

**Status:** Vendor-specific, non-fatal

`AutoFillManagerStub inject error` may appear on MIUI/custom ROM devices where AutoFillManager class structure differs. The `SafeAutofillSessionProxy` still intercepts autofill via the binder hook path even when mService replacement fails.

**Impact:** No crash. Autofill sessions return NO_SESSION cleanly.

---

### 8. Slow First Launch of Cloned Apps

**Status:** Known, expected

First launch of a cloned app takes 2-4 seconds longer than subsequent launches due to DEX optimization and native hook installation.

---

### 9. compileSdkVersion 34 with AGP 7.4.2

**Status:** Known, cosmetic warning

Build warning about compileSdk 34 vs AGP 7.4.2 (tested up to 33). No functional impact.

**Future fix:** Upgrade AGP to 8.x when Gradle wrapper is updated.

---

### 10. 32-bit (armeabi-v7a) Untested on Real Hardware

**Status:** Builds successfully, untested

Native code builds for `arm64-v8a`, `armeabi-v7a`, and `x86_64`. 32-bit syscall numbers are conditionally compiled. 32-bit hooking uses Cydia Substrate (Arm64 uses And64InlineHook). Primary testing is on x86_64 emulator and ARM64.

---

### 11. GMS (Google Play Services) Limitations

**Status:** Known, partial support

GMS support copies Google packages from the host device. The following limitations exist:
- **Push notifications (GCM/FCM):** Registration token relay between virtual and real GMS not implemented
- **Google Maps:** No Maps service proxy; apps using Google Maps may show blank
- **SafetyNet/Play Integrity:** No attestation bypass; apps checking device integrity will fail
- **FakeGms download URL:** The microG auto-download URL (`vaexposed.weishu.me`) is defunct; use host device GMS or install microG manually

**Workaround:** Install GMS from the host device via Settings → "Install Google Services" or ADB: `am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd gms`

---

## Resolved Issues

### ~~EditText/IME Crash (ANR)~~

**Status:** FIXED

Apps like Twitter and Facebook would freeze (ANR) when typing in EditText fields. Root cause: AutofillManager's `startSession` passed the virtual app's `ComponentName` to system_server, which rejected it due to UID mismatch. The `IResultReceiver` was never signalled, causing `SyncResultReceiver` to block the main thread for 5 seconds.

**Fix:** `SafeAutofillSessionProxy` intercepts the call, signals the `IResultReceiver` with `NO_SESSION` immediately, preventing any timeout.

### ~~Storage Directory Creation Failure~~

**Status:** FIXED

`Unable to create the directory: /storage/emulated/0/Android/data/io.va.exposed64/virtual/0` on Android 11+.

**Fix:** `getVirtualPrivateStorageDir()` now has 3-tier fallback: direct path → `getExternalFilesDir()` → internal storage.

---

## Device-Specific Notes

### Android 15 x86_64 Emulator (API 35)

Primary test environment. All 5 test apps (ZArchiver, Instagram, MT Manager, X/Twitter, Facebook) verified working:
- App cloning, launching, EditText interaction all functional
- TransactionHandlerProxy active for Android 15/16 compatibility
- No ANRs or crashes during testing

### Xiaomi Redmi (sunstone) — Android 14, MIUI OS 2.0.6.0

Previous test device. Known behaviors:
- `IPCThreadState` dlsym returns null (mitigated with fallbacks)
- `AutoFillManagerStub` mService injection fails (MIUI-specific, binder hook path still works)
- SELinux denials are cosmetic on this device

---

## ADB Testing Quick Reference

All commands require explicit component targeting on Android 14+:

```bash
# Base command format
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd <CMD> [--es pkg <PKG>]

# List cloned apps
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd list

# Clone an app
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd clone --es pkg com.twitter.android

# Launch a cloned app  
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd launch --es pkg com.twitter.android

# Clone + launch in one step
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd run --es pkg com.twitter.android

# Install GMS from host device
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd gms

# Kill a cloned app
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd kill --es pkg com.twitter.android

# Kill all cloned apps
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd killall

# Uninstall a cloned app
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd uninstall --es pkg com.twitter.android

# Clear data for a cloned app
adb shell am broadcast -n io.va.exposed64/io.virtualapp.dev.CmdReceiver -a io.va.exposed64.CMD --es cmd clear --es pkg com.twitter.android
```

---

## Reporting New Issues

When reporting issues, include:

1. **Device:** Model, Android version, ROM version
2. **Logs:** `adb logcat -v brief -s "VA++:*" "NativeEngine:*" "AndroidRuntime:*" "DEBUG:*"`
3. **Crash buffer:** `adb logcat -b crash -d`
4. **Steps:** What app was cloned, what action triggered the issue
5. **Build:** Whether using debug or release APK
