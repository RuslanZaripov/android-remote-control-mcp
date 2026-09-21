<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 68 — Rathole defaults from `.env` baked into debug AND release builds; remove apply-rathole-env flow

## Context (not derivable from code)

- Goal (user): install the APK (debug **or** release) → press Start (UI button,
  `ADB_START_SERVER`, or auto-start) → rathole tunnel `Connected`. Zero value entry: no app UI
  typing, no host `make apply-rathole-env` step.
- Android app processes cannot read the host `.env` at runtime (process env is set by the
  Android system) — so Gradle reads `<root>/.env` at build time and bakes the four rathole
  values as `BuildConfig.RATHOLE_*_DEFAULT` constants; the app applies them only to EMPTY
  DataStore fields (stored values — UI/ADB — always win, including an explicit `""`).
- **User decisions (2026-09-10, explicit):**
  - Bake into **both** `debug` and `release` build types (user instruction «и в релиз и в дебаг»).
    SECURITY CONSEQUENCE (accepted): rathole token + server key are readable inside BOTH APKs
    (`strings`, minify disabled). APKs built with a real `.env` must NOT be publicly
    distributed (Play / open-source releases) — the token grants full client access to the
    user's VPS tunnel.
  - **Remove** the Plan 67 host flow completely (explicit user instruction — satisfies the
    CLAUDE.md explicit-permission requirement for deletions): `scripts/apply-rathole-env.sh`,
    the Makefile `apply-rathole-env` target + `.PHONY` entry, `ApplyRatholeEnvScriptTest.kt`,
    the README `#### Configure rathole from .env` subsection; `.env.example` rathole comment
    reworded to the build-time mechanism.
  - KEPT: the four `RATHOLE_*` vars in `.env` (now consumed by Gradle + `make test-unit`
    sourcing), the app's `ADB_CONFIGURE` rathole extras (generic headless interface, Plan 66 —
    still a valid override path), `RatholeVpsConnectionTest` (host-side VPS verification),
    Plan 67 plan file (permanent artifact, untouched).
- Server-start chain verified: `McpServerService` start → `tunnelManager.start(config.port)`
  (when `!httpsEnabled`) → `TunnelManager.start` reads `settingsRepository.serverConfig`
  (build defaults applied) → `if (!tunnelEnabled) return` → starts `RATHOLE` provider.
  Therefore baked defaults (`allPresent`) yield Connect-on-Start with no extra config.
- **Branch:** `feat/rathole-tunnel-provider` (continues Plan 66/67; no PR per user decision;
  user pushes to fork).

## User Story 1 — Bake rathole defaults from `.env` into debug and release builds

Why: the APK itself carries the tunnel configuration for a fresh install; DataStore stays the
override layer (UI/ADB), so nothing is lost for multi-device or value-rotation use.

Acceptance criteria:
- [x] `.env` (missing → all-empty defaults) parsed at build time with light shape validation;
  invalid value fails the build naming the variable and expected shape.
- [x] `BuildConfig.RATHOLE_*_DEFAULT` populated in debug AND release from `.env`.
- [x] Empty DataStore fields are filled from build defaults; when all four are non-empty,
  `tunnel_provider` defaults to `RATHOLE` and `tunnel_enabled` to `true`.
- [x] Stored prefs always win (explicit `""` included — no re-seeding).
- [x] Unit tests: existing default test stays green (env-independent via mocked companion) +
  5 new cases.
- [x] Docs updated (README section, PROJECT.md line, `.env.example` comment) incl. the
  distribution warning.
- [ ] **Manual QA Steps** (real device, NOT covered by automated tests): fresh install
  (debug and/or release APK) with a built-from-`.env` APK → press Start → `Connected` (after
  the VPS ports accept the connection).

### Task 1.1 — `app/build.gradle.kts`

**File (modify):** `app/build.gradle.kts`
1. Add a top-level function next to `getGitDescribeVersion()` (existing top-level-fn pattern):

```kotlin
/**
 * Reads RATHOLE_* tunnel defaults from the gitignored root .env for baking into BuildConfig.
 * Missing file -> empty map (feature inactive). Light shape validation only:
 * completeness, unsafe-character, and key-format checks stay in the app
 * (rathole provider preflight at tunnel start).
 */
fun readRatholeEnvDefaults(rootDir: File): Map<String, String> {
    val envFile = rootDir.resolve(".env")
    if (!envFile.isFile) return emptyMap()
    val values =
        envFile
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains("=") }
            .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }

    fun validated(key: String, hint: String, check: (String) -> Boolean): String {
        val value = values[key].orEmpty()
        if (value.isEmpty()) return ""
        // Never echo the value (secrets in build logs) — length only.
        require(check(value)) { "$key in .env is invalid (expected $hint; got ${value.length} chars)" }
        return value
    }

    return mapOf(
        "rathole_server_addr" to
            validated("RATHOLE_SERVER_ADDR", "host:port, port 1-65535") { v ->
                val idx = v.lastIndexOf(':')
                idx > 0 &&
                    v.length > idx + 1 &&
                    v.substring(0, idx).none { c -> c.isWhitespace() } &&
                    v.substring(idx + 1).toIntOrNull()?.let { it in 1..65535 } == true
            },
        "rathole_server_public_key" to
            validated("RATHOLE_SERVER_PUBLIC_KEY", "non-empty base64 key") { it.isNotBlank() },
        "rathole_token" to validated("RATHOLE_TOKEN", "non-empty token") { it.isNotBlank() },
        "rathole_public_url" to
            validated("RATHOLE_PUBLIC_URL", "https:// URL without spaces") {
                it.startsWith("https://") && it.none { c -> c.isWhitespace() }
            },
    )
}

private fun buildConfigStringLiteral(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
```

2. In `buildTypes`, both variants bake the same values (release is NOT different by policy —
   user decision; CI has no `.env` so CI/release-distribution builds stay empty):

```kotlin
    val ratholeDefaults = readRatholeEnvDefaults(rootDir)

    buildTypes {
        debug {
            // Debug applicationId is set per-flavor via the variant API (androidComponents below) so it becomes
            // `…mcp.<flavor>.debug`, keeping the release applicationId identical across flavors.
            isDebuggable = true
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "RATHOLE_SERVER_ADDR_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_server_addr"].orEmpty()),
            )
            buildConfigField(
                "String",
                "RATHOLE_SERVER_PUBLIC_KEY_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_server_public_key"].orEmpty()),
            )
            buildConfigField(
                "String",
                "RATHOLE_TOKEN_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_token"].orEmpty()),
            )
            buildConfigField(
                "String",
                "RATHOLE_PUBLIC_URL_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_public_url"].orEmpty()),
            )
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            buildConfigField(
                "String",
                "RATHOLE_SERVER_ADDR_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_server_addr"].orEmpty()),
            )
            buildConfigField(
                "String",
                "RATHOLE_SERVER_PUBLIC_KEY_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_server_public_key"].orEmpty()),
            )
            buildConfigField(
                "String",
                "RATHOLE_TOKEN_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_token"].orEmpty()),
            )
            buildConfigField(
                "String",
                "RATHOLE_PUBLIC_URL_DEFAULT",
                buildConfigStringLiteral(ratholeDefaults["rathole_public_url"].orEmpty()),
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
```

(`val ratholeDefaults` goes directly above `buildTypes {` inside the `android { }` block, so
`rootDir` and `keystorePropertiesFile` stay in scope; adjust placement to compile.)

**DoD**
- [x] Build fails with a clear `require` message for a malformed `.env` value; succeeds with
  empty defaults when `.env` is absent.

### Task 1.2 — `SettingsRepositoryImpl.kt`

**File (modify):** `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt`

0. Add import (file has none currently): `import com.danielealbano.androidremotecontrolmcp.BuildConfig`

1. Top-level (next to `mapPreferencesToServerConfig`):

```kotlin
/**
 * Rathole tunnel defaults baked into the APK at build time from the root .env
 * (app/build.gradle.kts readRatholeEnvDefaults). Empty when the build had no .env.
 * Applied to EMPTY DataStore fields only — stored values (UI/ADB) always win.
 */
data class RatholeBuildDefaults(
    val serverAddr: String,
    val serverPublicKey: String,
    val token: String,
    val publicUrl: String,
) {
    val allPresent: Boolean
        get() =
            serverAddr.isNotBlank() &&
                serverPublicKey.isNotBlank() &&
                token.isNotBlank() &&
                publicUrl.isNotBlank()

    companion object {
        val fromBuildConfig =
            RatholeBuildDefaults(
                BuildConfig.RATHOLE_SERVER_ADDR_DEFAULT,
                BuildConfig.RATHOLE_SERVER_PUBLIC_KEY_DEFAULT,
                BuildConfig.RATHOLE_TOKEN_DEFAULT,
                BuildConfig.RATHOLE_PUBLIC_URL_DEFAULT,
            )
    }
}
```

2. `mapPreferencesToServerConfig` — new second parameter + changed lines (all other lines
   unchanged):

```kotlin
private fun mapPreferencesToServerConfig(
    prefs: Preferences,
    ratholeBuildDefaults: RatholeBuildDefaults = RatholeBuildDefaults.fromBuildConfig,
): ServerConfig {
    val bindingAddressName = prefs[BINDING_ADDRESS_KEY] ?: BindingAddress.LOCALHOST.name
    val certificateSourceName = prefs[CERTIFICATE_SOURCE_KEY] ?: CertificateSource.AUTO_GENERATED.name

    val tunnelProviderName =
        prefs[TUNNEL_PROVIDER_KEY]
            ?: (if (ratholeBuildDefaults.allPresent) TunnelProviderType.RATHOLE.name else TunnelProviderType.CLOUDFLARE.name)
    val cloudflareTunnelModeName =
        prefs[CLOUDFLARE_TUNNEL_MODE_KEY] ?: CloudflareTunnelMode.FREE.name

    return ServerConfig(
        ...
        tunnelEnabled = prefs[TUNNEL_ENABLED_KEY] ?: ratholeBuildDefaults.allPresent,
        ...
        ratholeServerAddr = prefs[RATHOLE_SERVER_ADDR_KEY] ?: ratholeBuildDefaults.serverAddr,
        ratholeServerPublicKey = prefs[RATHOLE_SERVER_PUBLIC_KEY_KEY] ?: ratholeBuildDefaults.serverPublicKey,
        ratholeToken = prefs[RATHOLE_TOKEN_KEY] ?: ratholeBuildDefaults.token,
        ratholePublicUrl = prefs[RATHOLE_PUBLIC_URL_KEY] ?: ratholeBuildDefaults.publicUrl,
        ...
    )
}
```

Note: `mapPreferencesToServerConfig` already carries `@Suppress("CyclomaticComplexMethod")`;
keep the method within detekt limits after the edit (extract a small file-private helper if
detekt complains — do NOT add new suppressions).

**DoD**
- [x] Fresh DataStore + baked defaults → `serverConfig` exposes the four values,
  `tunnelProvider == RATHOLE`, `tunnelEnabled == true`.

### Task 1.3 — Unit tests

**File (modify):** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt`

**Setup:** in `setUp` add `mockkObject(RatholeBuildDefaults.Companion)` +
`every { RatholeBuildDefaults.fromBuildConfig } returns RatholeBuildDefaults("", "", "", "")`;
in `tearDown` add `unmockkObject(RatholeBuildDefaults.Companion)`. Required: unit tests run
against the debug BuildConfig which, on a dev machine with a filled `.env`, contains REAL
values — without the mock the suite would depend on the developer's `.env` (CI has none).
New tests set the mock to specific values per case.

| Test | Verifies |
|------|----------|
| `default rathole settings are empty` (existing) | empty build defaults → `""` ×4, CLOUDFLARE, disabled (unchanged assertion) |
| `build defaults fill empty rathole fields` | mock 4 non-empty → `getServerConfig()` returns them |
| `build defaults activate RATHOLE provider and tunnel enabled` | mock 4 non-empty → `tunnelProvider == RATHOLE` and `tunnelEnabled == true` |
| `stored values win over build defaults` | `updateRatholeServerAddr(...)` first → stored value returned, not the default |
| `explicitly cleared rathole value stays empty` | store `""` via update → `""` returned (default does not re-apply) |
| `partial build defaults fill values but do not auto-enable` | mock 3 of 4 non-empty → values present, provider CLOUDFLARE, `tunnelEnabled == false` |

**DoD**
- [x] All `SettingsRepositoryImplTest` tests pass with AND without a filled `.env` in the repo.

### Task 1.4 — Docs

- **README.md** — new short section (near the rathole/Headless docs): "Build-time rathole
  defaults (debug and release)" — mechanism (`.env` → BuildConfig → empty-field defaults +
  auto-enable when all four present), precedence (DataStore > build defaults), and the
  warning: APKs built with a real `.env` contain the rathole token/key — distribute only to
  trusted devices, never to public channels.
- **docs/PROJECT.md** — one line in the settings section: rathole defaults may be baked from
  the root `.env` at build time (both build types); stored settings override.
- **.env.example** — reword the rathole comment block:

```
# Rathole (self-hosted tunnel, Plan 68).
# The Gradle build bakes these values into the APK as build-time defaults (debug AND
# release): a fresh install with all four set connects on Start with no config entry.
# WARNING: they are then readable inside the APK (strings) — never distribute such an APK.
# Also used by RatholeVpsConnectionTest: when RATHOLE_SERVER_ADDR, RATHOLE_SERVER_PUBLIC_KEY
# and RATHOLE_TOKEN are ALL set in the environment and the host `rathole` binary is on PATH,
# that test connects a real client to your VPS; otherwise it is SKIPPED (CI always skips).
RATHOLE_SERVER_ADDR=
RATHOLE_SERVER_PUBLIC_KEY=
RATHOLE_TOKEN=
RATHOLE_PUBLIC_URL=
```

**DoD**
- [x] No stale `apply-rathole-env` references remain outside `docs/plans/` (see US2 check).

## User Story 2 — Remove the apply-rathole-env host flow (explicit user instruction)

Why: build-time baking supersedes the one-shot host→device ADB apply; keeping both would be
two competing configuration paths.

Acceptance criteria:
- [x] `scripts/apply-rathole-env.sh` deleted; Makefile target + `.PHONY` entry removed.
- [x] `ApplyRatholeEnvScriptTest.kt` deleted (it tests only the removed script).
- [x] README `#### Configure rathole from .env` subsection removed (the main
  `ADB_CONFIGURE` broadcast example with rathole extras STAYS — app feature unchanged).
- [x] `.env.example` rathole comment reworded (Task 1.4).
- [x] Repo-wide grep over TRACKED files finds no `apply-rathole-env` references outside
  `docs/plans/`: `git grep -n "apply-rathole-env" -- . ':(exclude)docs/plans'` → empty.
  (The user's gitignored local `.env` may still contain the old comment — out of scope.)

### Task 2.1 — Deletions and Makefile/README edits

**Files (delete):** `scripts/apply-rathole-env.sh`,
`app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ApplyRatholeEnvScriptTest.kt`

**File (modify):** `Makefile` — remove the `apply-rathole-env` target block (2 lines +
blank separator) and the `apply-rathole-env` token in the `.PHONY` continuation line
(`compile-cloudflared compile-ngrok-native download-rathole apply-rathole-env check-so-alignment \` →
`compile-cloudflared compile-ngrok-native download-rathole check-so-alignment \`).

**File (modify):** `README.md` — delete the `#### Configure rathole from `.env`` subsection
added by Plan 67 (heading + paragraph + bash block + closing paragraph), restoring the
original adjacency: main `ADB_CONFIGURE` example block → "Disable bearer authentication"
example.

**DoD**
- [x] `make help` lists no `apply-rathole-env`; grep check from the acceptance criteria passes.

## Commit plan (ordered)

1. `feat(settings): bake rathole defaults from .env into debug and release builds` —
   Tasks 1.1, 1.2, 1.3 and ALL of 1.4 (README new section, `docs/PROJECT.md` line,
   `.env.example` rewording).
2. `chore(rathole): remove apply-rathole-env host flow (superseded by build-time defaults)` —
   Task 2.1 only (script + test deletions, Makefile, README subsection removal).

## Final quality gates (after ALL user stories)

- [x] `make lint` (ktlint + detekt) — zero NEW findings. Known pre-existing state stays: the
  detekt `UnusedParameter` in the user's uncommitted WIP `ScreenIntrospectionTools.kt` and any
  findings from the WIP `ScreenCaptureProvider.kt` (user decision, out of scope — do NOT
  "fix" user WIP).
  _Executed 2026-09-10: ktlint clean (after `ktlintFormat` fix for code-reviewer P1 — import
  ordering, if-else wrapping, Gradle-DSL signatures in the plan-68 files); detekt's ONLY finding
  = WIP `ScreenIntrospectionTools.kt:183` `UnusedParameter` — user confirmed WIP stays untouched._
- [x] `make build` (debug) AND `./gradlew :app:assembleGmsRelease` succeed; generated
  `BuildConfig` (AGP 9.x layout:
  `app/build/generated/source/buildConfig/{gms,foss}/{debug,release}/com/danielealbano/androidremotecontrolmcp/BuildConfig.java`)
  contains the `.env` values in BOTH variants; `.env` value change → rebuild picks it up.
  _Executed 2026-09-10: gms/foss × debug/release all BUILD SUCCESSFUL; 4 RATHOLE_*_DEFAULT fields
  present in all 4 generated BuildConfig (release shows the real .env public URL)._
- [x] `make test-unit` — state equals the accepted baseline (3 WIP test failures +
  NGROK_AUTHTOKEN env gap in this environment); `ApplyRatholeEnvScriptTest` is gone from the
  suite; new `SettingsRepositoryImplTest` cases green.
  _Executed 2026-09-10: gms 2320 tests / 4 failed / 1 skipped, foss 2280 / 4 / 1 — failures are
  exactly the accepted baseline (Ngrok initError, Privacy masking, ScreenIntrospection x2),
  RatholeTunnelIntegrationTest 3/3 green (host binary re-downloaded, sha256-pinned),
  RatholeVpsConnectionTest skipped (no RATHOLE_* env in test JVM), new SettingsRepositoryImplTest
  rathole block 8/8 green. Note: `make test-unit` sources `.env`; with the host binary on PATH
  that would make RatholeVpsConnectionTest hit the real VPS — from this datacenter VM the
  Cloud.ru ASN is firewalled, so the clean-baseline run used the binary on PATH without sourced
  env vars (documented here as the local verification method)._
- [x] code-reviewer subagent (plan-compliance mode) over the full implementation; ALL findings
  fixed; re-run until clean.
  _Round 1: NOT COMPLIANT (P1 ktlint x12, P2 six stale checkmarks) — fixed in 7f99dc6 + 11f805d.
  Round 2: COMPLIANT, no remaining findings._
- [ ] Commits pushed to `fork` — USER pushes manually (no credentials in this environment);
  NO PR (user decision).

**Known limitations (documented, not defects):**
- Rathole token + server key are readable inside debug AND release APKs built with a real
  `.env` (`strings`/decompilation, minify disabled) — explicit user decision; such APKs must
  not be publicly distributed. CI builds (no `.env`) and any build without `.env` stay clean.
- Build-time validation is shape-only (host:port / https:// / non-empty); the app's preflight
  re-checks completeness, unsafe characters, and key format — host:port and https-URL SHAPES
  are not re-validated in the app, so a shape-passing but odd baked value (e.g. `[::1]:2333`,
  an https URL with a path) surfaces as a runtime connection error.
- Changing `.env` after an install requires reinstalling the APK; already-stored DataStore
  values are NOT overwritten by new build defaults (clear the field in UI or `pm clear` to
  re-seed).
- The app's `ADB_CONFIGURE` rathole extras remain as an override path (Plan 66 interface) —
  intentionally kept.

<!-- Review findings (code-reviewer, 2026-09-10, plan compliance):
     P1 CRITICAL — 12 ktlint violations in plan-68 files (SettingsRepositoryImpl.kt import
     ordering + if/else wrapping in tunnelProviderName; app/build.gradle.kts
     chain-method-continuation, function-signature x2) — the earlier gate note "ktlint clean"
     was false (the detekt failure had masked ktlint output in the gate log). FIXED via
     `./gradlew ktlintFormat` (format-only diff), re-verified: ktlint clean, detekt only the
     WIP finding, SettingsRepositoryImplTest 136/136 green.
     P2 WARNING — six stale [ ] checkmarks (US1 AC stored-prefs / unit-tests / docs;
     Task 1.2, 1.3, 1.4 DoDs) — flipped to [x].
     I1 INFO — plan file in commit 1 carried forward-looking US2 marks/notes (US2 landed in the
     second commit); final state consistent, no action (audit-trail note only).
     Verdict after fixes: pending re-verify. -->
