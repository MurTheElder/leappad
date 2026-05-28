# Changelog

## [v0.1.0] — Unreleased

### Added
- Full Phase 2 source build: all common and Fabric subproject Java files
- Gradle subproject structure (common/, fabric/) with Gradle 8.12
- Fabric Loom 1.10.x, Fabric API 0.92.9, Cloth Config 11.1.118
- GitHub Actions build workflow (manual trigger, jar validation)
- Portal block, ignition handler, registry, mirror portal placement
- Transfer sequence: probe, session, orchestrator, WorldPinger, ProbeListener
- Character profile system: CharacterProfile, ProfileManager, AutosavePushManager
- Packet definitions for all 11 transfer sequence channels
- Config system with hard validation (leappad.json)
- All Fabric wiring: entry points, networking, Mixins, registrar, command registry
- Block assets: blockstates, models (NS/EW), texture, lang
