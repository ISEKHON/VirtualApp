# Project Architecture

This document describes the high-level architecture of VirtualApp, the module structure, and how the key subsystems interact.

---

## Overview

VirtualApp is an Android virtualization engine that creates a sandboxed environment ("virtual space") within a single host APK. Cloned apps run inside this sandbox with their own isolated data, permissions, and identity. It also integrates Xposed module support via the `exposed-core` library.

```
┌──────────────────────────────────────────────────────────┐
│                    Host Process (main)                    │
│  ┌──────────┐  ┌──────────────┐  ┌────────────────────┐ │
│  │  Splash  │  │  NewHome     │  │  VAppManagerService │ │
│  │ Activity │→ │  Activity    │  │  (Package Manager)  │ │
│  └──────────┘  └──────┬───────┘  └────────┬───────────┘ │
│                       │                    │              │
│                ┌──────▼────────────────────▼──────┐      │
│                │     VirtualCore (Singleton)       │      │
│                │  - App installation/uninstall     │      │
│                │  - Process management             │      │
│                │  - Service registry               │      │
│                └──────────────┬───────────────────┘      │
│                               │ IPC (Binder)             │
├───────────────────────────────┼──────────────────────────┤
│              Virtual Process (:p0, :p1, ...)             │
│  ┌────────────────────────────▼──────────────────────┐   │
│  │              VClientImpl                          │   │
│  │  - bindApplication (fake identity)                │   │
│  │  - ClassLoader injection                          │   │
│  │  - Xposed module loading                          │   │
│  ├───────────────────────────────────────────────────┤   │
│  │         Hook Layer (Java + Native)                │   │
│  │  ┌──────────────┐  ┌──────────────────────────┐   │   │
│  │  │ MethodProxy  │  │  TransactionHandlerProxy │   │   │
│  │  │  Stubs (AMS, │  │  (Activity lifecycle     │   │   │
│  │  │  PMS, Mount) │  │   interception)          │   │   │
│  │  └──────┬───────┘  └──────────┬───────────────┘   │   │
│  │         │ Java hooks          │ Transaction hooks  │   │
│  ├─────────┼─────────────────────┼───────────────────┤   │
│  │         │    Native Layer (libva++.so)             │   │
│  │  ┌──────▼─────────────────────▼───────────────┐   │   │
│  │  │  VMPatch    │  IOUniformer  │  And64Inline  │   │   │
│  │  │  (JNI hook) │  (path redir) │  (ARM64 hook) │   │   │
│  │  └─────────────┴──────────────┴───────────────┘   │   │
│  └───────────────────────────────────────────────────┘   │
│                      Cloned App                          │
│              (runs with virtual identity)                 │
└──────────────────────────────────────────────────────────┘
```

---

## Module Structure

### `app/` — Host Application

The user-facing Android application.

| Path | Purpose |
|------|---------|
| `src/main/` | All code: `SplashActivity`, `NewHomeActivity`, settings, UI |
| `build.gradle` | compileSdk 34, targetSdk 33, applicationId `io.va.exposed64` |

**Key classes:**
- `XApp` — Application entry point. Auto-disables Xposed on API 34+.
- `NewHomeActivity` — Main screen showing cloned apps (Material Design, clean grid).
- `ListAppActivity` — Clone app selection with search/filter.
- `SettingsActivity` — Settings including Xposed toggle.
- `SplashActivity` — Launch screen with app icon and branding.

### `lib/` — Virtual Engine Library

The core virtualization engine, compiled as an Android library.

| Path | Purpose |
|------|---------|
| `src/main/java/com/lody/virtual/` | Java virtualization engine |
| `src/main/java/android/app/` | AOSP class stubs/proxies (ClientTransactionHandler, etc.) |
| `src/main/java/mirror/` | Reflection mirror classes for hidden APIs |
| `src/main/jni/` | Native C/C++ code → `libva++.so` |
| `build.gradle` | compileSdk 30, targetSdk 22, NDK 21.4.7075529 |

### `lib/src/main/jni/` — Native Code

| Directory | Purpose |
|-----------|---------|
| `A64Inlinehook/` | ARM64 inline function hooking (modified for BTI) |
| `Foundation/` | `VMPatch.cpp` (JNI hooks), `IOUniformer.cpp` (path redirect) |
| `Jni/` | `VAJni.cpp` (JNI bridge, native method registration) |
| `Substrate/` | Cydia Substrate (x86/ARM32 hooking) |
| `fb/` | Facebook JNI (fbjni) helper library |

### `launcher/` — Custom Launcher

A modified AOSP Launcher3 used as the home screen inside the virtual space.

### `app-plugin/` — Plugin Support

Plugin APK loading infrastructure for extending VirtualApp.

---

## Key Subsystems

### 1. Package Management

**Server side:** `VAppManagerService` → `PackageParserEx` → `PackageParserCompat`

- Apps are installed by copying APKs to `/data/user/0/<host>/virtual/data/app/<pkg>/`
- `PackageParserCompat` calls Android's hidden `PackageParser` via reflection/mirrors
- Parsed package info is serialized to a cache file (Parcel format)
- On Android 14+, mirrors may fail → `PackageParserCompat34` does pure-reflection fallback
- Cache corruption triggers re-parse from the original APK

### 2. Process Virtualization

**`VClientImpl`** manages virtual process lifecycle:

1. `bindApplication()` — fakes `ApplicationInfo`, package name, UID for the cloned app
2. Loads the app's ClassLoader and replaces the context
3. Injects hook stubs (see below)
4. Optionally loads Xposed modules via `ExposedBridge`

### 3. Hook Layer (Java)

**Method Proxy Stubs:** Located in `com.lody.virtual.client.hook.proxies.*`

Each system service (`ActivityManager`, `PackageManager`, `StorageManager`, etc.) has a `MethodInvocationStub` that intercepts Binder calls and redirects them through VirtualApp's service implementations. Key stubs:

| Stub | Service | Purpose |
|------|---------|---------|
| `ActivityManagerStub` | `IActivityManager` | Activity launch, task management |
| `PackageManagerStub` | `IPackageManager` | Package queries, permissions |
| `MountServiceStub` | `IStorageManager` | Volume list, storage paths |
| `AccountManagerStub` | `IAccountManager` | Account isolation |

**TransactionHandlerProxy:** Intercepts `ClientTransaction` lifecycle callbacks (`handleLaunchActivity`, `handleResumeActivity`, etc.) to transform stub activities into real cloned app activities.

### 4. Hook Layer (Native)

**`libva++.so`** — loaded into every virtual process via `System.loadLibrary`:

| Component | What it hooks | How |
|-----------|---------------|-----|
| `IOUniformer` | 15 libc functions (`faccessat`, `fstatat`, `mkdirat`, etc.) | `And64InlineHook` — patches function prologues to redirect to custom implementations that rewrite file paths |
| `VMPatch` | `Binder.getCallingUid()`, `DexFile.openDexFileNative()`, `Camera.native_setup()`, `AudioRecord.native_checkPermission()` | ArtMethod pointer swap — replaces the JNI function pointer inside the ArtMethod structure |
| `And64InlineHook` | (Hooking engine) | Writes `BTI jc; LDR X17; BR X17; <addr>` over function prologues, saves originals to trampoline pool |

### 5. IO Redirection

`IOUniformer.cpp` redirects file system paths so that cloned apps see:
- Their own data directory instead of the host's
- Virtual SD card paths
- Correct `/proc/self/` information

Uses direct syscalls (`syscall(__NR_newfstatat, ...)` on 64-bit, `syscall(__NR_fstatat64, ...)` on 32-bit) in hook implementations to avoid recursive hooking.

### 6. Xposed Integration

Built on `me.weishu.exposed:exposed-core:0.8.1` with Epic for ART method hooking:

1. `XApp` checks `.disable_xposed` file at startup
2. `VClientImpl.bindApplication()` loads Xposed modules if enabled
3. `ExposedBridge.initOnce()` initializes the bridge
4. Each installed module's APK is loaded and its entry class instantiated

On Android 14+, this is auto-disabled due to Epic/SandHook incompatibility with ART changes.

---

## Process Model

VirtualApp uses a multi-process architecture:

| Process | Role |
|---------|------|
| Main process | Host UI, `VAppManagerService`, system services |
| `:x` | Server process for IPC handling |
| `:p0` through `:p9` | Virtual app processes (one per cloned app) |

Each `:pN` process:
1. Is forked from the main app process (not Zygote)
2. Has `VClientImpl` injected early in `Application.attachBaseContext()`
3. Gets all Binder proxies replaced with VirtualApp stubs
4. Runs the cloned app's code with a faked identity

---

## Data Layout

```
/data/user/0/io.va.exposed64/
├── virtual/
│   ├── data/
│   │   ├── app/                    # Installed APKs
│   │   │   ├── ma.dexter/
│   │   │   │   └── base.apk
│   │   │   └── com.cpuid.cpu_z/
│   │   │       └── base.apk
│   │   ├── data/                   # App private data
│   │   │   ├── ma.dexter/
│   │   │   └── com.cpuid.cpu_z/
│   │   └── user/                   # Multi-user data
│   └── setting/                    # Package settings cache
└── files/
    ├── .disable_xposed             # Xposed disable flag
    └── .xposed_api34_checked       # One-time API 34 check flag
```
