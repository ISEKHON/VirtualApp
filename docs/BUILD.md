# Build Guide

Instructions for building VirtualApp from source, including prerequisites, build variants, native code, and deployment.

---

## Prerequisites

| Tool | Version | Notes |
|------|---------|-------|
| **JDK** | 17 | Set in `gradle.properties` → `org.gradle.java.home` |
| **Android SDK** | API 34 (compileSdk) | Install via Android Studio SDK Manager |
| **Android NDK** | 21.4.7075529 | Must match `lib/build.gradle` → `ndkVersion` |
| **Gradle** | 7.5 (wrapper) | Bundled via `gradlew` / `gradlew.bat` |
| **Android Build Tools** | 30.0.0 | Set in `app/build.gradle` |
| **ADB** | Any recent | For deployment and testing |

### Install NDK

```
Android Studio → Settings → SDK Manager → SDK Tools → NDK (Side by side) → 21.4.7075529
```

Or via command line:
```bash
sdkmanager "ndk;21.4.7075529"
```

### Configure `local.properties`

Ensure `local.properties` in the project root contains:
```properties
sdk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
```

---

## Build Variants

| Variant | Command |
|---------|--------|
| Debug | `gradlew assembleDebug` |
| Release | `gradlew assembleRelease` |

### Split APK Output

The build produces per-ABI split APKs plus a universal APK:

```
app/build/outputs/apk/debug/
├── VirtualApp-1.0.0-arm64-v8a.apk
├── VirtualApp-1.0.0-armeabi-v7a.apk
└── VirtualApp-1.0.0-universal.apk
```

Naming format: `VirtualApp-<version>-<abi>.apk`

---

## Build Steps

### Full Build (Java + Native)

```powershell
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

Typical build time: **30-60 seconds** (first build with native code may be longer).

### Native Code Only

When you've only modified C/C++ files in `lib/src/main/jni/`:

```powershell
# 1. Clean native build cache (required for changes to take effect)
Remove-Item -Recurse -Force lib\build

# 2. Build native libraries
.\gradlew.bat :lib:externalNativeBuildRelease

# 3. Build full APK (will pick up new .so files)
.\gradlew.bat assembleDebug
```

The native build produces:
- `lib/build/intermediates/ndkBuild/release/obj/local/arm64-v8a/libva++.so`
- `lib/build/intermediates/ndkBuild/release/obj/local/armeabi-v7a/libva++.so`

**Important:** You MUST delete `lib/build` before rebuilding native code. Gradle's incremental build does not always detect `.cpp` changes correctly with ndkBuild.

### Java Only

When you've only modified `.java` files, a regular build is sufficient:

```powershell
.\gradlew.bat assembleDebug
```

---

## Deploy & Test

### Install on Device

```powershell
# Install the arm64 split APK (for most modern devices)
adb install -r app\build\outputs\apk\debug\VirtualApp-1.0.0-arm64-v8a.apk

# Or install the universal APK (works on all architectures)
adb install -r app\build\outputs\apk\debug\VirtualApp-1.0.0-universal.apk
```

### Launch

```powershell
adb shell monkey -p io.va.exposed64 -c android.intent.category.LAUNCHER 1
```

### View Logs

```powershell
# All VirtualApp logs
adb logcat -v brief -s "VA++:*" "NativeEngine:*" "VAppManagerService:*" "TransactionHandlerProxy:*"

# Native crash details
adb logcat -b crash -d

# Full logcat for a specific process
adb logcat -d | Select-String "PID_HERE"
```

### Force Stop & Clean Restart

```powershell
adb shell am force-stop io.va.exposed64
adb logcat -c
adb shell monkey -p io.va.exposed64 -c android.intent.category.LAUNCHER 1
```

---

## Project Structure

```
VirtualApp/
├── build.gradle              # Root build config (Gradle 7.4.2)
├── settings.gradle           # Includes :lib and :app
├── gradle.properties         # JVM args, JDK path, AndroidX disabled
├── local.properties          # SDK/NDK paths (git-ignored)
├── app/
│   ├── build.gradle          # compileSdk 34, targetSdk 33, appId io.va.exposed64
│   └── src/main/             # All code (flavors removed)
├── lib/
│   ├── build.gradle          # Library, compileSdk 30, targetSdk 22, NDK 21.4.7075529
│   ├── src/main/java/        # Java virtualization engine
│   ├── src/main/jni/         # C/C++ native code → libva++.so
│   │   ├── Android.mk        # NDK build script
│   │   ├── A64Inlinehook/    # ARM64 inline hooking (BTI-fixed)
│   │   ├── Foundation/       # VMPatch, IOUniformer
│   │   ├── Jni/              # VAJni bridge
│   │   └── Substrate/        # Cydia Substrate
│   └── libs/                 # Pre-built JARs
├── launcher/                 # Modified AOSP Launcher3
├── app-plugin/               # Plugin infrastructure
├── docs/                     # Documentation (you are here)
└── proguard/                 # ProGuard rules and mapping
```

---

## Native Build System

The native code uses **ndkBuild** (not CMake). The build script is at `lib/src/main/jni/Android.mk`.

### Supported ABIs

| ABI | Status | Notes |
|-----|--------|-------|
| `arm64-v8a` | Primary | All testing done on this |
| `armeabi-v7a` | Supported | 32-bit ARM devices |

x86 and x86_64 are not built. The app supports ARM 32-bit and 64-bit.

### Key Build Flags

Set in `Android.mk` or `Application.mk`:
- `-std=c++14` — C++ standard
- `-fno-rtti` — No RTTI (fbjni requirement)
- `-fno-exceptions` — No C++ exceptions in some modules

---

## Troubleshooting

### "Unable to find the jni function" in logcat

`measureNativeOffset()` in `VMPatch.cpp` couldn't find the mark function address in the ArtMethod structure. The search range is 200 bytes. If this appears, ART's method structure layout may have changed again.

### Native build doesn't pick up changes

Always delete `lib/build` before rebuilding:
```powershell
Remove-Item -Recurse -Force lib\build
```

### Gradle OOM

The project uses 4GB heap (`-Xmx4096m` in `gradle.properties`). If you run out of memory, increase it:
```properties
org.gradle.jvmargs=-Xmx6144m -XX:+HeapDumpOnOutOfMemoryError -Dfile.encoding=UTF-8
```

### "SDK location not found"

Create or verify `local.properties` has the correct `sdk.dir` path.

### Build fails with "could not find NDK"

Verify NDK version 21.4.7075529 is installed:
```powershell
ls "$env:LOCALAPPDATA\Android\Sdk\ndk\21.4.7075529"
```
