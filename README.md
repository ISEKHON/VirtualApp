# VirtualApp

An Android virtualization engine that runs cloned apps in an isolated sandbox with Xposed module support. Fork with **full Android 14 (API 34) compatibility**.

> **⚠️ Warning:** This project is highly unstable and under active development. Expect crashes, breaking changes, and incomplete features. Use at your own risk — not recommended for production use.

---

## Features

- **App Cloning** — Run multiple instances of any app simultaneously
- **Android 5.0–14 Support** — Tested and working on Android 14 (API 34)
- **Xposed Modules** — Load Xposed modules without root (auto-disabled on Android 14 due to ART changes)
- **32-bit & 64-bit** — Builds for `arm64-v8a` and `armeabi-v7a`
- **No Root Required** — Everything runs within a single host APK
- **IO Redirection** — Transparent file path remapping for cloned apps
- **UID Virtualization** — Each cloned app gets its own virtual identity
- **Search** — Find apps quickly when cloning with built-in search
- **Modern UI** — Clean Material Design home screen

## Android 14 Highlights

This fork includes 15+ patches making VirtualApp work on Android 14:

| Category | Fix |
|----------|-----|
| Native | ARM64 BTI landing pad for inline hooks (fixes SIGILL) |
| Native | `@CriticalNative` safe `getCallingUid` hook (fixes SIGSEGV) |
| Native | 32-bit ARM `__NR_fstatat64` syscall fix for `armeabi-v7a` |
| Java | Android 14 `ClientTransactionHandler` method overloads (fixes AbstractMethodError) |
| Java | `ArraySet` handling for `IntentFilter.mActions` and `protectedBroadcasts` |
| Java | `getVolumeList` userId fix for API 33+ |
| Parsing | Pure-reflection `PackageParser` fallback for broken mirrors |
| Parsing | APK re-parse on cache corruption (fixes disappearing apps) |
| Xposed | Auto-disable on API 34+ with Settings toggle |
| UI | Redesigned home screen, splash screen, clone app search |

See [docs/FIXES.md](docs/FIXES.md) for complete technical details.

---

## Quick Start

### Prerequisites

| Tool | Version |
|------|---------|
| JDK | 17 |
| Android SDK | API 34 |
| Android NDK | 21.4.7075529 |
| Gradle | 7.5 (wrapper included) |

### Build

```bash
# Clone the repo
git clone <repo-url> VirtualApp
cd VirtualApp

# Build debug APK (produces per-ABI splits + universal)
./gradlew assembleDebug
```

Output in `app/build/outputs/apk/debug/`:
```
VirtualApp-1.0.0-arm64-v8a.apk
VirtualApp-1.0.0-armeabi-v7a.apk
VirtualApp-1.0.0-universal.apk
```

### Install & Run

```bash
# Install arm64 split (recommended for modern devices)
adb install -r app/build/outputs/apk/debug/VirtualApp-1.0.0-arm64-v8a.apk

# Install armeabi-v7a for 32-bit devices
adb install -r app/build/outputs/apk/debug/VirtualApp-1.0.0-armeabi-v7a.apk

# Or universal (works on all architectures)
adb install -r app/build/outputs/apk/debug/VirtualApp-1.0.0-universal.apk

adb shell monkey -p io.va.exposed64 -c android.intent.category.LAUNCHER 1
```

See [docs/BUILD.md](docs/BUILD.md) for detailed build instructions.

---

## Project Structure

```
VirtualApp/
├── app/           # Host application (UI, Activities, XApp)
├── lib/           # Core virtualization engine
│   ├── src/main/java/   # Java engine (hooks, services, proxies)
│   └── src/main/jni/    # Native code (libva++.so)
│       ├── A64Inlinehook/  # ARM64 inline hooking (BTI-fixed)
│       ├── Foundation/     # VMPatch, IOUniformer
│       └── Jni/            # JNI bridge
├── launcher/      # Modified AOSP Launcher3
├── app-plugin/    # Plugin infrastructure
├── docs/          # Documentation
│   ├── FIXES.md        # Detailed fix documentation
│   ├── ARCHITECTURE.md # System architecture
│   ├── BUILD.md        # Build instructions
│   ├── CHANGELOG.md    # Version history
│   └── KNOWN-ISSUES.md # Known issues & limitations
└── proguard/      # ProGuard configuration
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for system design details.

---

## Documentation

| Document | Description |
|----------|-------------|
| [docs/FIXES.md](docs/FIXES.md) | Every Android 14 fix with root cause analysis, code changes, and verification |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | System architecture, module structure, data flow diagrams |
| [docs/BUILD.md](docs/BUILD.md) | Build prerequisites, steps, variants, deployment, troubleshooting |
| [docs/CHANGELOG.md](docs/CHANGELOG.md) | Version history and change log |
| [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md) | Known issues, limitations, workarounds |

---

## Tested Configuration

| Property | Value |
|----------|-------|
| Device | Xiaomi Redmi (sunstone) |
| SoC | Snapdragon 695 |
| Android | 14 (API 34) |
| ROM | MIUI OS 2.0.6.0 (UMQINXM) |
| Architecture | ARM64 (arm64-v8a) |

### Tested Apps

| App | Status |
|-----|--------|
| MT Manager (ma.dexter) | Working |
| CPU-Z (com.cpuid.cpu_z) | Working |
| Twitter (com.twitter.android) | Installed |
| SnapTube (com.snaptube.premium) | Installed |

---

## Known Issues

- **Xposed modules** do not work on Android 14 (Epic/SandHook incompatible with ART changes)
- **Fake signatures** used when certificate parsing fails (some apps may detect tampering)
- **SELinux denials** for certain vendor properties (non-fatal)
- **External storage** creation may fail under scoped storage restrictions

See [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md) for the full list.

---

## Tech Stack

| Component | Technology |
|-----------|-----------|
| Language | Java 8, C++14 |
| Build System | Gradle 7.5, ndkBuild |
| Native Hooking | And64InlineHook (ARM64), Cydia Substrate (ARM32) |
| JNI | Facebook JNI (fbjni) |
| Xposed Runtime | Epic 0.11.1, exposed-core 0.8.1 |
| Hidden API Bypass | free_reflection 3.0.1, hiddenapibypass 6.1 |

---

## Contributing

1. Read [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) to understand the codebase
2. Read [docs/BUILD.md](docs/BUILD.md) to set up your environment
3. Check [docs/KNOWN-ISSUES.md](docs/KNOWN-ISSUES.md) for things that need fixing
4. When modifying native code, always delete `lib/build` before rebuilding
5. Test on a real Android 14 device — emulators may not reproduce all issues

---

## Credits

- Based on [VirtualApp](https://github.com/asLody/VirtualApp) by asLody
- Xposed integration from [VirtualXposed](https://github.com/nicehash/VirtualXposed) by weishu
- [And64InlineHook](https://github.com/nicehash/And64InlineHook) for ARM64 inline hooking
- [fbjni](https://github.com/nicehash/fbjni) — Facebook JNI helpers
- [Epic](https://github.com/nicehash/epic) — ART method hooking for Xposed

## License

[Apache License 2.0](LICENSE)
