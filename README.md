# Cariad MetadataService Workspace

Monorepo-style workspace containing the MetadataService Android application, the legacy ConnectedRadio client library, the shared AIDL client interface, and all supporting build scripts and documentation.

**Maintainer:** Christian Winter (T2-CH), Volkswagen Group / Cariad SE  
**App version:** `1.90-release`  
**Package ID:** `technology.cariad.entertainment.metadataservice`  
**License:** See `metadataservice/LICENSE`

---

## Table of Contents

1. [Repository Overview](#1-repository-overview)
2. [Prerequisites](#2-prerequisites)
3. [One-Time Setup](#3-one-time-setup)
4. [Build Instructions](#4-build-instructions)
5. [Deployment to Device](#5-deployment-to-device)
6. [Component Reference](#6-component-reference)
7. [Version and Identity Notes](#7-version-and-identity-notes)

---

## 1. Repository Overview

| Component | Path | Purpose |
|---|---|---|
| **Main Android app** | `metadataservice/` | Core MDS app — Kotlin service, native Gracenote JNI, AIDL server, SQLite cache, dev UI |
| **ConnectedRadio client library** | `metadataservice_conradlib/` | Fetches station logos and metadata from the ConnectedRadio backend |
| **Client interface library** | `metadataservice_clientinterface/` | AIDL definitions and Parcelable data classes for all MDS clients (current + legacy e.solutions namespace) |
| **Build and helper scripts** | `scripts/` | APK build, target device install, ADB log capture, and backup utilities |
| **External prebuilt deps** | `gnsdk-auto-2.6.19.14264-20250729/`, `libs_from_Cariad/`, `external/`, `lib/` | Gracenote SDK `.so` libs, MagicAdapter JARs, native ARM libs, Android signing keys |

---

## 2. Prerequisites

### 2.1 Development Machine

| Tool | Required version | How to obtain |
|---|---|---|
| **JDK** | 17 or 21 (JDK 8 source/target compatibility is configured inside Gradle but the **Gradle daemon itself requires JDK 17+**) | Bundled with Android Studio, or install separately and set `JAVA_HOME` |
| **Android SDK** | API level 34 (`compileSdk 34`, `minSdk 33`, `targetSdk 34`) | Android Studio SDK Manager → SDK Platforms → Android 14 |
| **Android NDK** | `26.1.10909125` (side-by-side) | Android Studio SDK Manager → SDK Tools → NDK (Side by side) |
| **CMake** | 3.22.1 or newer | Android Studio SDK Manager → SDK Tools → CMake |
| **Gradle** | 8.9 (via wrapper — do not install separately) | Pulled automatically by `./gradlew` on first run |
| **Android Gradle Plugin** | 8.2.0 (declared in `metadataservice/build.gradle`) | Downloaded automatically by Gradle |
| **Kotlin** | 1.9.21 (declared in `metadataservice/build.gradle`) | Downloaded automatically by Gradle |

> **NDK override via environment variable:** The NDK version can be overridden at build time by setting `NDK_VERSION=<version>` in the environment before invoking Gradle.`

### 2.2 Required Files Not in the Repository

The following files are **gitignored** and must be created locally before building:

| File | Purpose | Required for |
|---|---|---|
| `metadataservice/local.properties` | Tells Gradle where your Android SDK is; created automatically by Android Studio | All builds |
| `testtoken.env` | Dev token file read by build scripts to populate `token/testtoken.txt` | Dev builds only (emu, icc, no-gn profiles) |

---

## 3. One-Time Setup

### Step 1 — Configure `local.properties`

If you build from the command line without Android Studio, create
`metadataservice/local.properties`:

For example - 

```properties
sdk.dir=C:/AndroidSDK
```

Replace the path with the actual location of your Android SDK.

### Step 2 — Provide the ConnectedRadio token (for development builds only)

**Required for dev builds (emu, icc, no-gn profiles). Not required for production builds.**

Create `testtoken.env` at the **workspace root** (next to this README):

```
#conradlibV2
<paste your Dev ConnectedRadio JWT token here>
```

When you run `./scripts/build_all_variants.sh`, it automatically:
1. Reads `testtoken.env`
2. Extracts the token from the `#conradlibV2` section
3. Writes it to `token/testtoken.txt` (which Gradle reads at build time)

### Step 3 — Install NDK 26.1.10909125

Open Android Studio → **SDK Manager** → **SDK Tools** → check **NDK (Side by side)** →
select version `26.1.10909125` → Apply.

Alternatively, the NDK can be downloaded from the Android NDK Archive and placed under
`<sdk.dir>/ndk/26.1.10909125/`.

### Step 4 Build Instructions

## 4. Build Instructions

### 4.1 Development Build (Recommended)

**Development builds require `testtoken.txt`** (populated from `testtoken.env`). Use the `build_all_variants.sh` script from the **workspace root**, which automatically handles token population and builds all three runtime profiles.

#### Runtime Profiles

Three runtime profiles are supported for development builds:

| Profile | `mds.profile` | Gracenote | ConnectedRadio backend | Dev mode | Use case |
|---|---|---|---|---|---|
| **EMU** | `emu` | enabled | approval (mock data) | **on** | Vanilla emulator, out-of-the-box dev build |
| **ICC** | `icc` | enabled | approval (real data) | off | Real testbench or Cariad Magic integration |
| **No-GN** | `no-gn` | disabled | approval | off | VW no-license fallback (same behavior as ICC, GN disabled) |

#### Build all dev variants at once

From the **workspace root**, use `build_all_variants.sh` to interactively select and build one or more profiles:

```bash
# Interactive profile selection
./scripts/build_all_variants.sh

# Build all three profiles (emu + icc + no-gn)
./scripts/build_all_variants.sh 0

# Build only ICC (profile 2)
./scripts/build_all_variants.sh 2
```

The script automatically:
1. Reads `testtoken.env` from the workspace root
2. Extracts the token from the `#conradlibV2` section
3. Writes it to `token/testtoken.txt`
4. Runs Gradle builds (one per selected profile)
5. Copies APKs to the `output/` directory

> **More details:** See the `scripts/build_all_variants.sh` script header (comments at the top) for complete usage and profile descriptions.

**Output APK locations:**

```
output/MetadataService-EmuGeneric-tech-cariad-dev-row-debug.apk
output/MetadataService-ICC-tech-cariad-dev-row-debug.apk
output/MetadataService-NoGN-tech-cariad-dev-row-debug.apk
```

### 4.2 Production Build

Production builds do **not** require `testtoken.env` or a ConnectedRadio token. Run these commands from the **`metadataservice/`** subdirectory:

```powershell
cd metadataservice/

# prodRowDebug
.\gradlew.bat :app:assembleProdRowDebug

# prodRowRelease (minified, signed with testkey)
.\gradlew.bat :app:assembleProdRowRelease
```

**Output APK locations:**

```
metadataservice/app/build/outputs/apk/prodRow/debug/
    MetadataService-prod-row-debug.apk

metadataservice/app/build/outputs/apk/prodRow/release/
    MetadataService-prod-row-release.apk
```

### 4.3 Other Build Tasks

#### Clean build

```powershell
cd metadataservice/
.\gradlew.bat clean :app:assembleDevRowDebug
```

#### Run unit tests

```powershell
cd metadataservice/
.\gradlew.bat :app:testDevRowDebugUnitTest
```

#### Build the client interface library only

The client interface library (AIDL definitions and Parcelable classes) is automatically built as part of the main app build, but you can build it independently for distribution or testing:

```powershell
cd metadataservice/
.\gradlew.bat :clientinterface:assembleRelease
# AAR output: metadataservice_clientinterface/lib/build/outputs/aar/
```

> **Note:** ConRad library (`conradlib`) is only built when building the main app; it is not available as a standalone library. Gracenote SDK (`gmdlib`) is a precompiled `.so` included from `gnsdk-auto-*/`.

---

## 5. Deployment to Device

### 5.1 Privileged-app install (standard flow)

MDS must be installed as a **privileged system app** under `/product/priv-app/`.

Use the install script from `scripts/`:

**Windows (PowerShell):**

```powershell
cd scripts/
.\install_target.ps1
# Auto-detects and installs the latest APK from ../output/
```

**Linux / macOS:**

```bash
cd scripts/
./install_target.sh
# Same behavior as PowerShell version
```

> **More details:** See the script header (comments at the top of `install_target.ps1` or `install_target.sh`) for additional options such as `-ApkPath`, `-Serial`, and `-Identity backup`.

What the script does:
1. Verifies device is connected and `/product` is writable (`adb root && adb remount` must be done first).
2. Removes competing priv-app variants (both legacy `de.esolutions.metadataservice` and current `technology.cariad.entertainment.metadataservice`).
3. Pushes the APK into `/product/priv-app/<package>/`.
4. Copies `privapp-permissions-metadataservice.xml` to `/product/etc/permissions/`.
5. Sets ownership, permissions, and restores the SELinux label.
6. Clears the PackageManager cache and reboots.
7. Resets the radio app state (`de.eso.radio`) — stops the app and clears its data/cache for all users on the device.

> **Before running the install script:**
> ```
> adb root
> adb remount
> ```

### 5.2 Backup the original MDS before replacing

**Windows:**
```powershell
cd scripts/
.\backup_original_mds.ps1
```

**Linux / macOS:**
```bash
cd scripts/
./backup_original_mds.sh
```

Backups are stored in `backup_original_mds/<timestamp>/`.

> **More details:** See the respective script headers for advanced options and behavior.

---

## 6. Component Reference

### 6.1 Main Application (`metadataservice/`)

- **Language:** Kotlin + C++ (Gracenote JNI)
- **Architecture:** Android Service with Dagger DI, Room database, Kotlin coroutines/Flow
- **Key classes:**
  - `MetadataService` — Android `Service` entry point; routes binds to current or legacy AIDL stub
  - `MetadataServiceImpl` — Current AIDL `IMetadataServiceAidl.Stub`
  - `LegacyMetadataServiceAdapter` — Legacy `de.esolutions` AIDL stub; delegates to `MetadataServiceImpl`
  - `MetadataImageProvider` — `ContentProvider` serving cached artwork to authorized clients
  - `AccessManager` — Runtime URI-grant authorization (UID-scoped, in-process memory)
  - `ClientPermission` — Checks calling process holds `USE_SERVICE` permission
- **Product flavors:** `dev` × `row`, `prod` × `row`, `dev` × `cn`, `prod` × `cn`
- **Build types:** `debug`, `release`

### 6.2 ConnectedRadio Library (`metadataservice_conradlib/`)

- Fetches station logos and station metadata from the ConnectedRadio backend.
- Included as Gradle subproject (`:conradlib`) when building the main app.
- Requires a valid JWT token in `token/testtoken.txt` at build time.

### 6.3 Client Interface (`metadataservice_clientinterface/`)

- AIDL definitions for both the current (`technology.cariad.entertainment.metadataserviceclient`) and legacy (`de.esolutions.metadataserviceclient`) namespaces.
- Kotlin Parcelable data classes: `Station`, `RecognizedSongInfo`, `CoverArtRequestData`, `SongLyrics`, `MusicMetadata`, and others.
- Included as Gradle subproject (`:clientinterface`) in the main app build.
- Clients bind to MDS using one of the two supported intent actions:
  - Current: `technology.cariad.entertainment.metadataserviceclient.api.IMetadataServiceAidl`
  - Legacy: `de.esolutions.metadataserviceclient.api.IMetadataServiceAidl`

### 6.4 Scripts (`scripts/`)

| Script | Platform | Purpose |
|---|---|---|
| `build_all_variants.sh` | bash | Build all three runtime profiles |
| `install_target.ps1` | PowerShell | Install as privileged system app on device |
| `install_target.sh` | bash | Same as above for Linux/macOS |
| `capture_mds_logs.ps1` | PowerShell | Capture and filter ADB logs |
| `backup_original_mds.ps1` | PowerShell | Back up existing priv-app MDS before replacing |
| `backup_original_mds.sh` | bash | Same as above for Linux/macOS |
| `test_local.sh` | bash | Run unit tests locally |

---

## 7. Version and Identity Notes

- **Active build version:** `1.90-release` — source of truth is `metadataservice/deploy/versions.json`.
- **Package rename:** this branch ships under `technology.cariad.entertainment.metadataservice`. The legacy package `de.esolutions.metadataservice` is supported as a dual-compatibility binding target but is **not** the `applicationId`.
- **Dual AIDL compatibility:** MDS accepts binds from both current-namespace and legacy-namespace clients simultaneously. The `LegacyMetadataServiceAdapter` translates parcelables and rewrites image-provider URIs for legacy clients.
- **Image-provider authorities:** both `technology.cariad.entertainment.metadataservice.contentprovider.MetadataImageProvider` and `de.esolutions.metadataservice.MetadataService.ImageProvider` are registered; the provider accepts both.
- **Permissions:**
  - Current `USE_SERVICE`: `technology.cariad.entertainment.metadataservice.USE_SERVICE`
  - Legacy `USE_SERVICE`: `de.esolutions.metadataservice.USE_SERVICE`

---

## Where to Start Reading

1. This file for setup and build
2. `metadataservice/README.md` for app-level architecture detail
3. `doc/README.md` for migration notes and API documentation index
4. `doc/CHANGELOG.md` for per-version change history
5. Module `README.md` files for the specific component you are modifying

---

## 8. Documentation Addendum (2026-09-03)

This workspace still contains historical and vendor-era documentation in several modules.
When in doubt, treat the following as current source-of-truth for build/runtime behavior:

1. `metadataservice/app/build.gradle`
2. `scripts/build_all_variants.sh`
3. `doc/README.md`

Current profile mapping for development builds:

- `emu`
- `icc`
- `no-gn`

Current no-gn expectation:

- `GN_LICENSE_VARIANT=NONE`
- `BuildConfig.GN_LICENSE_ENABLED=false`
- partial metadata can still appear on some runtime paths (for example text without image)
