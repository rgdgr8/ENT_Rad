# Documentation Hub

This folder is the central entry point for project documentation.

## Build and Usage
- Main app guide: `metadataservice/README.md`
- Legacy setup notes: `doc/legacy_setup.txt`
- Build scripts: `scripts/` (build_all_variants.sh, build_local.sh, test_local.sh, switch_app.sh, CI scripts)

## Architecture and Migration
- Client implementation notes: `doc/ClientImplementation.md`
- Installing over the legacy `technology.cariad.entertainment.metadataservice` app: `doc/INSTALL_OVER_LEGACY.md`

## Module Documentation
- Main service docs: `metadataservice/doc/`
- Main service user manual: `metadataservice/user_manual/`
- Client interface docs: `metadataservice_clientinterface/README.md`
- Conradlib legacy docs: `metadataservice_conradlib/README.md`
- Probe app docs: `mds_probe/README.md`

## Release and Quality
- Release notes: `release_notes/` (includes releasenote.adoc and release_notes.txt)
- Review exports: `reviews/` (git-ignored)
- Static analysis: `static_code_analysis/` (includes ignored-issues.json and Coverity reports)

## Migration Sign-off Checklist
- Namespace migration complete to `technology.cariad.entertainment.*` in tracked source/config/docs.
- Legacy namespace verification clean:
	- no legacy package-prefix usages in tracked non-generated files
	- no legacy metadataservice package-name usages in tracked non-generated files
- AIDL path/package alignment verified for client interface sources.
- Build verification complete for all supported dev profiles:
	- `emu`, `icc`, `no-gn`
- No blocking IDE diagnostics in:
	- `metadataservice/`
	- `metadataservice_clientinterface/`
- Known residual warnings are currently non-blocking (deprecations/toolchain warnings).

## Assets
- Icons: `doc/icons/`

## Build Artifacts
- APK output: `output/`

## CI / Integration
- Conan dependency config: `integration/conanfile.py`
- Stream config: `integration/stream.json`

---

## Addendum (2026-09-03) - Current Source-of-Truth Snapshot

This addendum preserves existing text and appends current, verified values from the active branch.

### Active Identities
- Current app package: `technology.cariad.entertainment.metadataservice`
- Legacy compatibility package: `de.esolutions.metadataservice`
- Current AIDL action: `technology.cariad.entertainment.metadataserviceclient.api.IMetadataServiceAidl`
- Legacy AIDL action still supported: `de.esolutions.metadataserviceclient.api.IMetadataServiceAidl`

### Runtime Profiles
- Use: `-Pmds.profile=emu|icc|no-gn`
- `emu`: DEV-friendly defaults, mock service-list/disable-reasons behavior
- `icc`: real bench behavior, real service-list and disable-reasons by default
- `no-gn`: same base behavior as `icc`, with `GN_LICENSE_VARIANT=NONE` and `GN_LICENSE_ENABLED=false`

### Build Output Naming
`scripts/build_all_variants.sh` currently produces profile-specific APK names in `output/`:
- `MetadataService-EmuGeneric-tech-cariad-dev-row-debug.apk`
- `MetadataService-ICC-tech-cariad-dev-row-debug.apk`
- `MetadataService-NoGN-tech-cariad-dev-row-debug.apk`

### no-gn Clarification
License disable in `no-gn` does not guarantee full suppression of all song-recognition-related text paths.
Partial metadata (for example text without image) may still occur depending on runtime path and provider behavior.
