# Example PackControl pack repository

This directory shows the source layout for a pack owner. It contains no mod
JARs, credentials or generated release files.

```text
pack-repository/
├── instance/
│   ├── config/example.toml
│   ├── defaultconfigs/server.toml
│   └── kubejs/server_scripts/example.js
├── packcontrol-pack.json
└── packcontrol-publisher.json
```

Copy your permitted instance inputs into `instance/`. Place mod JARs under
`instance/mods/` locally; do not commit or distribute third-party JARs unless
their license permits it. Modrinth matches are resolved in one SHA-512 batch.
Each non-Modrinth JAR needs an explicit public GitHub Release Asset mapping and
`allowThirdPartyJar: true`.

Build and validate from the repository root:

```text
gradlew.bat :packcontrol-publisher:run --args="inspect --instance ../examples/pack-repository/instance --config ../examples/pack-repository/packcontrol-publisher.json"
gradlew.bat :packcontrol-publisher:run --args="build --instance ../examples/pack-repository/instance --config ../examples/pack-repository/packcontrol-publisher.json --output ../publisher-output"
gradlew.bat :packcontrol-publisher:run --args="validate --input ../publisher-output"
```

Upload the generated `packcontrol-manifest.json`, `overrides.zip`, `.mrpack`
and `checksums.txt` to the same public GitHub Release. The Publisher configuration
uses `example.invalid` deliberately; replace it with the immutable public asset
base URL used by your release process.
