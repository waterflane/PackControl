# PackControl manifest format

`packcontrol-manifest.json` describes one immutable pack release. Version 1 is
JSON encoded as UTF-8. The game client must parse and validate the manifest
before planning, downloading or changing the instance.

## Top-level object

| Field | Type | Meaning |
| --- | --- | --- |
| `schemaVersion` | integer | Must be `1`. |
| `metadata` | object | Pack and build identity. |
| `minimumPackControlVersion` | SemVer string | Oldest compatible client. |
| `files` | array | Individually downloadable managed files. |
| `overrides` | object | The reproducible overrides archive and its entries. |
| `removedFiles` | string array | Previously managed paths to remove. |

`metadata` contains `packId`, `name`, SemVer `version`, immutable `releaseId`,
`minecraftVersion`, `loader`, and `loaderVersion`. For the current MVP,
`minecraftVersion` is `1.21.1` and `loader` is `neoforge`.

## File entries

Each `files` element has:

- `path`: relative instance path using `/`;
- `downloads`: ordered HTTPS candidates; later URLs are fallbacks;
- `hashes`: required lowercase or uppercase hexadecimal `sha1`, `sha256` and
  `sha512` digests;
- `size`: exact byte count;
- `required`: whether lack of a usable source blocks the update;
- `environment.client` and `environment.server`: `required`, `optional`, or
  `unsupported`.

A file cannot be unsupported in both environments. Optional files may have no
download candidate; required files may not.

## Overrides

`overrides` contains `fileName`, ordered `downloads`, all three hashes, exact
archive `size`, and `entries`. `fileName` is a root-level `.zip` name, normally
`overrides.zip`.

Each entry contains its instance-relative `path`, hashes and uncompressed
`size`. Only `config/`, `defaultconfigs/` and `kubejs/` are accepted override
roots. Directory records are not part of the manifest.

## Example

```json
{
  "schemaVersion": 1,
  "metadata": {
    "packId": "example-pack",
    "name": "Example Pack",
    "version": "1.2.0",
    "releaseId": "example-pack-1.2.0",
    "minecraftVersion": "1.21.1",
    "loader": "neoforge",
    "loaderVersion": "21.1.233"
  },
  "minimumPackControlVersion": "0.1.0",
  "files": [
    {
      "path": "mods/example.jar",
      "downloads": [
        "https://cdn.modrinth.com/data/example/versions/1/example.jar"
      ],
      "hashes": {
        "sha1": "0000000000000000000000000000000000000000",
        "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
        "sha512": "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
      },
      "size": 12345,
      "required": true,
      "environment": {
        "client": "required",
        "server": "required"
      }
    }
  ],
  "overrides": {
    "fileName": "overrides.zip",
    "downloads": [
      "https://github.com/example/example-pack/releases/download/v1.2.0/overrides.zip"
    ],
    "hashes": {
      "sha1": "0000000000000000000000000000000000000000",
      "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
      "sha512": "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
    },
    "size": 500,
    "entries": [
      {
        "path": "config/example.toml",
        "hashes": {
          "sha1": "0000000000000000000000000000000000000000",
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "sha512": "00000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        },
        "size": 12
      }
    ]
  },
  "removedFiles": [
    "mods/obsolete.jar"
  ]
}
```

The zero digests illustrate field lengths only and do not describe a real
artifact.

## Validation and security rules

- Absolute, UNC-like, drive-prefixed and `..` traversal paths are rejected.
- Duplicate paths, case-insensitive duplicates and parent/child file conflicts
  across files, overrides and removals are rejected.
- Downloads must be absolute HTTPS URLs without credentials or fragments.
- Every downloadable artifact and override entry requires SHA-1, SHA-256 and
  SHA-512 plus an exact size.
- Default limits are 4,096 files, 8 URLs per artifact, 16,384 override entries,
  4,096 removals, 2 GiB per file, 1 GiB per overrides archive, 256 MiB per
  override entry, and 16 GiB total declared download size.
- The installer verifies HTTP status, byte count and hashes in staging before
  changing the instance.
- `removedFiles` is an instruction, not ownership proof. The installer removes
  a path only if the previous `installed-state.json` says PackControl manages
  it.

Unknown top-level JSON fields are currently ignored by Gson. Publishers should
emit only fields defined by the selected `schemaVersion`; a breaking format
change requires a new schema version.

## Imported mrpack bootstrap

Publisher-generated `.mrpack` files contain two generated service entries
beside ordinary user overrides:

```text
overrides/packcontrol-pack.json
overrides/.packcontrol/bootstrap.json
```

They are installation metadata, not manifest `overrides.entries`. Therefore
they are neither packed into the standalone `overrides.zip` nor presented as
user configuration overrides.

`packcontrol-pack.json` version 1 contains:

```json
{
  "schemaVersion": 1,
  "targetGithubRepository": "example/example-pack",
  "updateChannel": "stable"
}
```

`updateChannel` is `stable` or `beta`. The repository is the public GitHub
`owner/repository` used for read-only release discovery.

The bootstrap schema is:

| Field | Type | Meaning |
| --- | --- | --- |
| `schemaVersion` | integer | Must be `1`. |
| `packId` | string | Must match the published manifest. |
| `packVersion` | SemVer string | Version already imported by the launcher. |
| `releaseId` | string | Immutable release identity. |
| `manifestSha256` | 64-character hex string | SHA-256 of the published manifest file. |
| `managedFiles` | array | Exact content eligible for initial adoption. |

Each `managedFiles` entry has `path`, `size`, and complete `sha1`, `sha256`,
and `sha512` hashes. The list is the union of manifest `files` and ordinary
`overrides.entries`; it excludes both service entries.

On startup PackControl validates bootstrap syntax, safe relative paths,
duplicates, conflicts, limits, regular-file type, size, and every hash. Only a
complete match is converted to `.packcontrol/installed-state.json`. A mismatch
does not partially adopt files. Repeating the same bootstrap is a no-op, while
a different existing installed state blocks adoption and is never overwritten.

Bootstrap performs no downloads and applies no update while Minecraft is
running.
