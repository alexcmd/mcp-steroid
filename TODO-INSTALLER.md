# TODO — Installer epic (PR #113) status & remaining scope

Running plan for the installer epic. The epic was deliberately peeled into focused PRs off `main`
instead of merging the big `installer/version-json-driven` branch wholesale. This file tracks what has
landed and what is **still only on #113**.

## Landed on `main` (extracted from #113, in better form)

- **devrig launcher ownership** — `~/.mcp-steroid/bin/devrig` self-heal + PATH + wrapper registration
  (#117/#118).
- **Website generator** — `:website-gen` `WebsiteArtifacts` (version.json + updatePlugins.xml), Kotlin,
  Ktor (#119); GitHub Action runs it main-only (#120).
- **Dead-module cleanup** — removed `:jdk-downloader` + `:pgp-verifier` (#121); IDE downloads confirmed
  SHA-256-based.
- **JDK data model + cache + PGP** — `:website-gen` `resolveAllJdks` → `JdkModel`, on-disk/in-memory
  `Cache`, BouncyCastle `PgpVerifier` with **pinned** vendor fingerprints (#122). Validated live (8
  artifacts). See [website-gen/CLAUDE.md](website-gen/CLAUDE.md).

## Still only on `installer/version-json-driven` (the remaining deliverable)

The **installer-script generator** — everything else from #113 is now superseded by `main`:

| #113 file | Disposition |
|---|---|
| `site-gen/.../installer/InstallerGenerator.kt` | **PORT** — generates `install.sh` / `install.ps1` from JDK + devrig coordinates |
| `site-gen/src/main/resources/templates/install.{sh,ps1}.tmpl` | **PORT** — the script templates (the actual installer logic) |
| `site-gen/.../installer/CoordinateResolver.kt` (+ `CoordinateResolverTest`) | **REPLACE** with `:website-gen` `resolveAllJdks` `JdkModel` + a thin adapter |
| `site-gen/.../installer/site/SiteArtifacts.kt` (+ test) | **DROP** — superseded by `main`'s `WebsiteArtifacts` |
| `site-gen/.../installer/tests/InstallerBootstrapTest.kt` | **PORT** — nginx sidecar, real HTTP, sha256-verify, content-addressed dirs, idempotency |
| `site-gen/.../installer/tests/InstallerRealArtifactsTest.kt` | **PORT/ADAPT** — re-derives sha/javaHome from a downloaded file (see decision D3) |
| `site-gen/.../installer/tests/JdkCoordinatesMetadataTest.kt` | **ADAPT** — asserts the platform set (see decision D2) |
| old `:pgp-verifier`, `:jdk-downloader`, `:site-gen` build wiring | **DROP** — superseded by `:website-gen` |

### The adapter (`JdkModel` → installer-script fields)

`JdkArtifact` is information-complete; the generator needs a thin translation layer:
- platform key: `JdkOs/JdkArch` enums → `<os>-<cpu>` string with **`AARCH64`→`arm64`** (matches the
  scripts' `uname -m` normalization);
- archive: `ArchiveType` → already carries `.extension` (`tar.gz`/`zip`);
- `fileName`, `sha256`, `url`, `javaHome` map 1:1 (javaHome is forward-slash, no leading slash — matches
  `JdkCoordinatesMetadataTest`'s assertion).

## Open decisions (resolve before the integration PR)

- **D1 — home of the installer generator**: extend `:website-gen` (add `InstallerGenerator` + templates +
  an `installerIntegrationTest` source set) vs. a new `:installer-gen` module depending on `:website-gen`.
- **D2 — platform coverage**: extend the installer (`ALL_PLATFORMS` + both script templates + the
  metadata test) to consume the new **alpine (musl) x64/arm64** and **macos-x64** entries (alpine support
  was the stated goal), vs. ship #113's original 5 platforms first and add alpine in a follow-up.
- **D3 — `InstallerRealArtifactsTest` source of truth**: have the test download via the `Cache` and
  re-derive sha/javaHome from the local file (keeps the existing test shape), vs. trust the model's
  already-PGP-verified computed `sha256`/`javaHome` and assert the model directly (simpler, no re-download).

## Minor, non-blocking (from the #122 quorum review)

- Azul `latest` selection: trust `latest=true` + assert a single result, vs. the current sort-and-take.
- Fold `UrlKey.resolvedUrl` into the cache key (PGP currently backstops the gap).
