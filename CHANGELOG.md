# Changelog

All notable changes to PackControl are documented here. Versions follow
Semantic Versioning.

## [Unreleased]

### Added

- Versioned PackControl manifest model and strict validation.
- Transactional update planning, staging, backup and rollback.
- Modrinth, public GitHub Release and allowlisted direct HTTPS sources.
- Standalone Publisher CLI with deterministic `overrides.zip` and `.mrpack`
  export.
- Read-only GitHub Releases discovery and guided update UI.
- Pull request CI and draft release automation.

### Changed

- NeoForge for Minecraft 1.21.1 is documented as the first supported loader.
- Loader metadata now uses the PackControl homepage and SPDX
  `GPL-3.0-only` identifier.

## [0.1.0] - Unreleased

- Initial development baseline.

[Unreleased]: https://github.com/waterflane/PackControl/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/waterflane/PackControl/releases/tag/v0.1.0
