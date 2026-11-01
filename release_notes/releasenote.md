# MetadataService Release Notes

**Version:** 1.91-release  
**Release Date:** TBD  
**Last Updated:** 2026-09-18

---

## Summary

MetadataService App v1.91-release successfully completes the migration from the legacy de.esolutions.metadataservice package to the new Cariad namespace (technology.cariad.entertainment.metadataservice). This release maintains full backward compatibility with legacy clients while establishing the new package as the primary system app. All 4 build profiles tested and passing.

---

## What's New in This Release

### Migration to Cariad Namespace (Primary Change)

- **New Application Package ID:** technology.cariad.entertainment.metadataservice
  - Aligns with Cariad SE organizational structure
  - Registered as system app in /product/priv-app/ during deployment
  - Supersedes legacy de.esolutions.metadataservice package


- **Clean Installation Path:** Step-by-step migration process
  - Removes both old and new package variants
  - Installs new app
  - Resets radio app state to ensure clean initial state

### Installation Process Updates

- **Radio App Reset Logic** — Automatic state reset of radio app during deployment
  - Clears cached metadata and stored preferences
  - Ensures reliable metadata retrieval after installation

### Additional Improvements (Non-Migration)

- **Song Recognition Reliability** — Enhanced retry logic for song recognition
  - Hardened SongInfo recognition timeout handling with bounded retries and session guards
  - Improves recognition success rate in poor signal conditions

- **Demo App Enhancements** — Added build timestamp to status page
  - Displays build date and time in settings UI for version verification
  - Adds an ARMS backend URL to the service list, enabling test flows to fetch radio metadata from the new backend.

---

## Dependencies & Prerequisites

### Build Environment
- **JDK:** 17 or 21 (Gradle daemon requires JDK 17+)
- **Android NDK:** 26.1.10909125 (side-by-side installation, configurable via NDK_VERSION)
- **Gradle:** 8.9 (via wrapper; do not install separately)
- **CMake:** 3.22.1 or newer (for JNI/Gracenote build)
- **Android SDK Target:** API level 34
  - compileSdk: 34
  - minSdk: 33
  - targetSdk: 34

### Native & Vendor Libraries
- **Gracenote SDK:** 2.6.19.14264 (source: gnsdk-auto-2.6.19.14264-20250729/)
- **ConradLib (ConnectedRadio):** OI_2025-10-28-113620
- **GMDLib:** 2025_10_28-125437-OI-MAIN
- **MagicAdapter:** Prebuilt in libs_from_Cariad/
- **sSOA Manifest:** Version 24w15.4

### Runtime Environment
- **Metadata-Service ClientInterface:** Version 11.1.0
- **Gracenote Backend Certificate:** truststore_gracenote.pem (from 2024-09-17)
- **Package ID (Current):** technology.cariad.entertainment.metadataservice
- **Package ID (Legacy):** de.esolutions.metadataservice (dual-contract binding for compatibility)

---

## Installation & Deployment


**Prerequisites:** adb root && adb remount (makes /product writable)

1. **Device Verification** — Verify device connectivity and permissions
   - Confirm adb root access
   - Confirm remount capability

2. **Variant Removal** — Remove old/conflicting package variants
   - Removes both current and legacy package IDs
   - Clears associated data

3. **APK Push** — Deploy APK to privileged system directory
   - Target: /product/priv-app/technology.cariad.entertainment.metadataservice/
   - Privileged system app installation

4. **Permission XML Copy** — Install required system permissions
   - Deploys privapp-permissions-metadataservice.xml to /product/etc/permissions/
   - Enables privileged APIs

5. **Ownership & SELinux Setup** — Configure file ownership and security context
   - Sets correct user/group ownership
   - Applies SELinux context

6. **Radio App State Reset** — Reset radio app state for all users
   - Clears cached metadata
   - Resets to initial state
   - Target package: de.eso.radio

---

## Known Issues & Workarounds

### Gracenote Truststore (Persistent Issue)

- **Issue:** Gracenote truststore update via Magic-Adapter remains activated even if not integrated on Magic side
- **Impact:** Fallback truststore (installed as app asset) is always used
- **Workaround:** Accept corresponding errors/warnings in trace logs
- **Status:** By design (agreed with Cariad)

### Cache Clearing After Update

- **Issue:** Access right issue when upgrading to version >= 1.2.12-MAIN
- **Workaround:** Clear MetadataService App cache once after update
  - Manual: Connect via ADB → Clear cache
  - Alternative: Factory reset on target
- **Note:** Only required once per update

### GMD-Functionality Not Tested

- **Issue:** GMD functionality not enabled in OEM backend configuration
- **Impact:** Cannot be fully tested
- **Workaround:** Contact OEM backend team for GMD enablement
- **Status:** Known limitation

### ExternalStorageManager Availability (Platform Limitation)

- **Issue:** ExternalStorageManager (ESM) is unavailable for this migration in both dev and prod builds
- **Impact:** The external `metadata` partition is not used in migration test runs
- **Behavior:**
  - Storage initialization uses app-internal storage via `context.filesDir`
  - Metadata cache and persistence paths operate on internal app storage instead of ESM-managed external storage
- **Workaround:** Use internal app storage as the migration baseline and validate migration behavior with the same storage mode in dev and prod
- **Status:** Known migration limitation; internal-storage fallback is the current supported path

---

## Build & Test Status

### Build Profiles

| Profile | Purpose | Features | Status |
|---------|---------|----------|--------|
| emu | Emulator testing | Gracenote enabled, dev token | PASS |
| icc | Real hardware test | Gracenote enabled, Runtime token | PASS |
| no-gn | Gracenote disabled | Gracenote disabled, Runtime token | PASS |
| prod | Production | No UI, Gracenote enabled, Runtime token | PASS |

**Test Results Summary:** All build profiles tested and passing

### Unit Tests

**Test Statistics (Row Variants):**
| Metric | Count |
|--------|-------|
| **Total Tests** | 416 |
| **Passed** | 409 |
| **Failed** | 0 |
| **Skipped** | 7 |
| **Pass Rate** | 100% |

**Test Coverage by Functional Category:**

- **Data Management** (52 tests) — Data storage, caching, repositories, entity conversion
- **Service Communication** (28 tests) — AIDL interfaces, service binding, permissions, IPC
- **Metadata Retrieval** (38 tests) — Song recognition, Gracenote integration, provider APIs, cover art lookup, and station logo fetching
- **Network & Backend** (24 tests) — ConnectedRadio/ARMS backend, HTTP operations, URL handling
- **Audio Processing** (18 tests) — Audio recording, encoding, buffer management
- **Utility & Helpers** (45 tests) — Logging, file operations, byte conversions, position data, permissions
- **Database Operations** (18 tests) — SQLite queries, DAOs, database initialization
- **UI & Components** (32 tests) — Preferences, callbacks, state management
- **Robolectric-Incompatible Tests** (7 tests, pre-ignored) — FileProvider cache conflicts requiring infrastructure-level fixes

**Notes:** All non-skipped tests pass consistently across all 4 row variants (devRowDebug, devRowRelease, prodRowDebug, prodRowRelease). The 7 skipped tests are pre-ignored baseline tests in ImageDataStoreTest due to known Robolectric FileProvider cache persistence issues.

### Gracenote Availability

- **Online Functionality:** Available per Gracenote license file (per brand)
- **License Variant:** GN_LICENSE_ENABLED configurable via BuildConfig

---

## Development Build Configuration

Run all Gradle commands from the `metadataservice/` subdirectory.

Use either Gradle wrapper depending on platform:
- `./gradlew` for Linux/macOS
- `.\gradlew.bat` for Windows

### Development Builds (devRowDebug)

Build one profile at a time:

```powershell
cd metadataservice/

# EMU
.\gradlew.bat :app:assembleDevRowDebug "--project-prop=mds.profile=emu"

# ICC
.\gradlew.bat :app:assembleDevRowDebug "--project-prop=mds.profile=icc"

# No-GN
.\gradlew.bat :app:assembleDevRowDebug "--project-prop=mds.profile=no-gn"
```

Output:
- `metadataservice/app/build/outputs/apk/devRow/debug/MetadataService-EmuGeneric-tech-cariad-dev-row-debug.apk`

- `metadataservice/app/build/outputs/apk/devRow/debug/MetadataService-ICC-tech-cariad-dev-row-debug.apk`

- `metadataservice/app/build/outputs/apk/devRow/debug/MetadataService-NoGN-tech-cariad-dev-row-debug.apk`

### Production Builds (prodRow)

```powershell
cd metadataservice/

# Production debug
.\gradlew.bat :app:assembleProdRowDebug

# Production release (unsigned APK by default)
.\gradlew.bat :app:assembleProdRowRelease
```

Output:
- `metadataservice/app/build/outputs/apk/prodRow/debug/MetadataService-ICC-tech-cariad-prod-row-debug.apk`

- `metadataservice/app/build/outputs/apk/prodRow/release/MetadataService-ICC-tech-cariad-prod-row-release-unsigned.apk`

### Production Build with Gracenote Disabled

```powershell
cd metadataservice/
.\gradlew.bat :app:assembleProdRowDebug "--project-prop=mds.profile=no-gn"
```

```bash
cd metadataservice/
./gradlew :app:assembleProdRowDebug "--project-prop=mds.profile=no-gn"
```

### Interactive Build Helper (Optional)

For development variants, you can also use the interactive helper from workspace root:

```bash
./scripts/build_all_variants.sh
```

This script lets you select EMU/ICC/No-GN profiles interactively and builds the selected dev variants.


---

## Architecture Overview

### System Components

MetadataService App (technology.cariad.entertainment.metadataservice)

Core application components:
- Kotlin-based service (MetadataService class)
- AIDL service interface (IMetadataServiceAidl) with dual binding support
- SQLite cache for metadata storage
- Development UI for testing and debugging

Native Libraries (via JNI)

- Gracenote SDK 2.6.19.14264 — Music recognition and metadata
- GMDLib 2025_10_28-125437-OI-MAIN — Genre/mood classification
- ConradLib OI_2025-10-28-113620 — ConnectedRadio backend integration
- MagicAdapter — Cariad service communication layer

Metadata Sources

- ConnectedRadio Backend — Real-time station logos and metadata via HTTPS
- ARMS Backend Endpoint — Under-development alternative to ConnectedRadio Backend
- Gracenote Online API — Song recognition and music metadata
- Local Cache — SQLite database with fallback data

### Data Flow

1. Radio app (or client) sends metadata request via AIDL (IMetadataServiceAidl)
2. MetadataService receives request and checks local SQLite cache
3. If not cached, fetches from ConnectedRadio/ARMS backend endpoint (via ConradLib service-list or override URL) or Gracenote Online API
4. Stores result in cache and returns to client
5. Client displays metadata (station name, logo, song info, etc.) in UI

### Backward Compatibility Layer

Both AIDL contracts are served by the same application instance:

- **New Contract:** technology.cariad.entertainment.metadataserviceclient.api.IMetadataServiceAidl
- **Legacy Contract:** de.esolutions.metadataserviceclient.api.IMetadataServiceAidl
- **Service Permission Compatibility:** Both `technology.cariad.entertainment.metadataservice.USE_SERVICE` and `de.esolutions.metadataservice.USE_SERVICE` are accepted during migration
- **Image Provider Authority (New):** technology.cariad.entertainment.metadataservice.contentprovider.MetadataImageProvider
- **Image Provider Authority (Legacy):** de.esolutions.metadataservice.MetadataService.ImageProvider
- **Legacy Binder Adapter:** Legacy interface descriptor and parcelables/callbacks are translated to current implementation at runtime
- **Legacy URI Rewriting:** Returned image URIs are rewritten to legacy authority for legacy clients

Legacy clients (compiled against de.esolutions package) can continue to bind and retrieve metadata without recompilation.

---

## Troubleshooting

### Q: Installation fails with "permission denied"
**A:** Ensure you have run adb root && adb remount first. Device must be in root/remounted state.

### Q: Radio app metadata shows "NO IMAGE AVAILABLE"
**A:** This is normal in emulator (sandbox limitation). Test on real hardware or check ConnectedRadio backend status.

### Q: Gracenote truststore errors in logcat
**A:** Expected behavior (by design). See "Known Issues" section for details.


---

## References

- **Installation Scripts:** scripts/install_target.sh (Bash), scripts/install_target.ps1 (PowerShell)
- **Build Configuration:** metadataservice/build.gradle, metadataservice/gradle.properties
- **Deployment Guide:** README.md Section 5 (Deployment to Device)
- **Version Info:** metadataservice/deploy/versions.json
- **Change Log:** doc/CHANGELOG.md
- **Installation Instructions:** doc/INSTALL_OVER_LEGACY.md (legacy migration)
- **Build System:** build.gradle, settings.gradle, gradle/ folder

---

## Support & Escalation

**For Technical Issues:**
- Contact: christian.winter@cariad.technology
- Fallback: ext.group.Media.OI-MetadataService@esolutions.de


---

*For internal use. Not for external distribution without approval.*
