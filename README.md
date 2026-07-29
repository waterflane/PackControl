# PackControl

PackControl is a client-side modpack updater for Minecraft 1.21.1. It discovers
published pack releases, validates every declared artifact, stages downloads
away from the game instance, and applies an update transactionally with backup
and rollback.

NeoForge is the first supported loader. Fabric and Forge artifacts are also
built for development and compatibility testing, but should currently be
treated as experimental.

## Installation

Requirements:

- Minecraft 1.21.1;
- Java 21;
- NeoForge 21.1.x;
- Architectury API 13.0.8 or newer;
- the matching `packcontrol-neoforge-<version>.jar`.

Install NeoForge and Architectury API, then copy the PackControl NeoForge JAR
into the instance's `mods` directory. Start the game once so PackControl can
create its configuration.

The recommended installation path is to import the `.mrpack` produced by
PackControl Publisher. The archive supplies both the root
`packcontrol-pack.json` and `.packcontrol/bootstrap.json`; no service files
need to be created by hand.

On the first Minecraft start, PackControl hashes every file named by the
bootstrap. It creates `.packcontrol/installed-state.json` only when all sizes
and SHA-1/SHA-256/SHA-512 values match. A changed or missing file is left
unmanaged, and an existing installed state is never replaced by bootstrap.

For development or a manually assembled instance, the minimal pack
configuration is:

```json
{
  "schemaVersion": 1,
  "targetGithubRepository": "example/example-pack",
  "updateChannel": "stable"
}
```

`stable` ignores draft and prerelease GitHub releases; `beta` may select
prereleases. Public repositories need no GitHub token.

## Update workflow

1. Open PackControl and select **Check for updates**.
2. Review the installed and available versions, changelog, download size, and
   the add/update/remove/keep summary.
3. Review warnings for locally modified managed files.
4. Download the release into staging.
5. Restart when prompted so PackControl can apply the staged transaction.
6. If applying fails, use the offered rollback. PackControl does not remove
   files it did not record in its installed state.

PackControl never applies a discovered update while Minecraft is running.
Downloading to staging and applying the transaction remain separate actions;
the current MVP applies only through the existing explicit workflow.

Publishing is intentionally not performed by the Minecraft client. Pack owners
use the standalone [PackControl Publisher](packcontrol-publisher/README.md) to
build a manifest, deterministic overrides archive, Modrinth pack and checksums.

## Development

The repository includes the Gradle Wrapper and targets Java 21:

```text
gradlew.bat build
gradlew.bat :packcontrol-publisher:test
gradlew.bat releaseArtifacts
```

On Linux or macOS run `chmod +x gradlew` once, then use `./gradlew`.
`releaseArtifacts` runs the core and publisher tests, builds loader-ready JARs
and sources, creates the Publisher CLI distribution, and writes everything
with `checksums.txt` to `build/release-artifacts`.

See [CONTRIBUTING.md](CONTRIBUTING.md), the
[manifest specification](docs/packcontrol-manifest.md), the
[example pack repository](examples/pack-repository/README.md), and
[CHANGELOG.md](CHANGELOG.md).

## License

PackControl is licensed under `GPL-3.0-only`. See [LICENSE](LICENSE).
