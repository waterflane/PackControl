# PackControl Publisher

`packcontrol-publisher` is a Java 21 command-line application. It does not use
Minecraft, NeoForge, Architectury, Loom, or the PackControl game UI.

Run it through Gradle:

```text
gradlew :packcontrol-publisher:run --args="inspect --instance C:\packs\example"
gradlew :packcontrol-publisher:run --args="build --instance C:\packs\example --output C:\packs\dist"
gradlew :packcontrol-publisher:run --args="validate --input C:\packs\dist"
```

By default, `inspect` and `build` read
`<instance>/packcontrol-publisher.json`. Use `--config <path>` to select a
different file.

## Input policy

- JAR files are scanned only below `mods/`.
- `config/`, `defaultconfigs/`, and `kubejs/` become overrides.
- JAR files below an overrides root and symbolic links are rejected.
- Other instance files, including `options.txt`, are not published.
- Mods matched by SHA-512 are resolved through the Modrinth batch API.
- A non-Modrinth JAR needs an explicit public GitHub Release Asset mapping and
  `allowThirdPartyJar: true`.
- An unresolved required mod blocks the build. An unresolved path listed in
  `optionalMods` is omitted with a warning.
- Secret-like configuration fields (`token`, `secret`, `password`,
  `authorization`, and API key variants) are rejected.

Minimal configuration:

```json
{
  "packId": "example",
  "name": "Example Pack",
  "version": "1.0.0",
  "releaseId": "example-1.0.0",
  "summary": "Example",
  "minecraftVersion": "1.21.1",
  "loader": "neoforge",
  "loaderVersion": "21.1.200",
  "minimumPackControlVersion": "1.0.0",
  "releaseBaseUrl": "https://downloads.example.org/example/1.0.0/",
  "optionalMods": [],
  "githubMods": {
    "mods/private-build.jar": {
      "owner": "example",
      "repository": "public-mod",
      "tag": "v1.0.0",
      "assetName": "private-build.jar",
      "allowThirdPartyJar": true
    }
  }
}
```

The output directory receives `packcontrol-manifest.json`, deterministic
`overrides.zip`, `<packId>-<version>.mrpack`, and `checksums.txt`. Generation
happens in a temporary directory. The manifest, both archives, embedded
override files, and checksums are reread and validated before publication.
