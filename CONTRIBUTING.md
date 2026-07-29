# Contributing to PackControl

PackControl accepts focused issues and pull requests. Keep update-format and
installer changes independent of Minecraft UI wherever possible.

## Local setup

Install JDK 21 and use the checked-in Gradle Wrapper:

```text
gradlew.bat build
```

On Linux or macOS run `chmod +x gradlew` once and use `./gradlew`.

Do not use a system Gradle version to regenerate build output. If the wrapper
must be updated, use a reviewed Gradle distribution and commit all four wrapper
files together.

Before opening a pull request, run:

```text
gradlew.bat :common:test
gradlew.bat :packcontrol-publisher:test
gradlew.bat :fabric:assemble :forge:assemble :neoforge:assemble
```

The Publisher fixture test is a separate CI check because its ignored-looking
`.jar` inputs are intentionally tiny test resources. Do not replace them with
third-party mod binaries.

## Change guidelines

- Add tests for validation, download, planning and transaction failure paths.
- Keep blocking I/O off the Minecraft render thread.
- Treat every URL, path, size and hash in a manifest as untrusted input.
- Never add tokens, private keys, local instance state, real mod JARs, crash
  reports or generated build directories.
- Do not silently weaken rollback, ownership or hash verification guarantees.
- Update the manifest specification when the wire format changes.
- Add user-visible changes to `CHANGELOG.md`.

Use Conventional Commit-style subjects when practical, for example
`fix: preserve locally modified managed files`.

## Release process

1. Ensure CI passes and update `CHANGELOG.md`.
2. Run `gradlew.bat clean releaseArtifacts`.
3. Inspect `build/release-artifacts` and verify `checksums.txt`.
4. Create and push a SemVer tag such as `v0.2.0`.
5. The release workflow repeats all tests and creates a draft GitHub Release.
6. A maintainer reviews the draft and publishes it manually.

Pushing a tag never bypasses tests, and the workflow contains no repository or
publisher credentials beyond GitHub's short-lived workflow token.
