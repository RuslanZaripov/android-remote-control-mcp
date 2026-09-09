<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 67 — rathole host `.env` configuration + optional real-VPS connection test

## Context (not derivable from code)

- User does not want to type the four rathole values (Server Address, Server Public Key,
  Service Token, Public URL) into the app UI. The app's ADB headless interface (Plan 66 US3,
  `AdbConfigHandler` extras `rathole_server_addr` / `rathole_server_public_key` /
  `rathole_token` / `rathole_public_url`) already persists them to DataStore — so US1 needs
  ZERO app-code changes: a host-side script reads the project `.env` and sends one
  `ADB_CONFIGURE` broadcast (+ optionally `ADB_START_SERVER`).
- Live issue that motivated US2: the app's tunnel stays `Connecting` forever against the user's
  VPS (VPS ports 80/443/2333 were closed to outside probes). An env-gated JVM test that drives
  the real provider against the real VPS separates VPS-side problems (port/keys/token) from
  device-side problems without a phone.
- **User decisions (2026-09-09):** single project `.env` serves both the apply script and the
  VPS test; the VPS test SKIPS (JUnit5 assumption) when the env vars are absent — never
  hard-fails (CI-safe, unlike `NgrokTunnelIntegrationTest` which hard-fails); Plan 66 stays
  untouched.
- **Branch:** work continues on `feat/rathole-tunnel-provider` — Plan 66's commits are not in
  `main` and this plan depends on them (`AdbConfigHandler` extras, `RatholeTunnelProvider`,
  `HostRatholeBinaryResolver` in `RatholeTunnelIntegrationTest.kt`). No PR per user decision.
- Debug applicationIds are per-flavor: `com.danielealbano.androidremotecontrolmcp.gms.debug` /
  `…foss.debug`; release: `com.danielealbano.androidremotecontrolmcp` (see `app/build.gradle.kts`
  flavor block). The apply script must auto-detect which is installed.
- `shellcheck` is NOT installed locally or in CI — shell-script QA is `bash -n` + a JVM
  ProcessBuilder test (repo pattern: `RatholeTunnelIntegrationTest` spawns real processes).

## User Story 1 — Configure rathole from host `.env` (zero app changes)

Why: secrets stay on the host in the gitignored `.env`; one command fills the app's DataStore;
the app UI is already read-only display from then on.

Acceptance criteria:
- [x] `.env.example` documents the four `RATHOLE_*` vars incl. VPS-test skip semantics.
- [x] `scripts/apply-rathole-env.sh`: validates `.env`, auto-detects installed app package +
  single adb device, sends `ADB_CONFIGURE` with provider/tunnel/rathole extras; `--start` also
  sends `ADB_START_SERVER`; `--dry-run` prints the command with secrets masked and needs no adb;
  never prints full secret values; clear errors (missing file/vars/device/app).
- [ ] `make apply-rathole-env` (with `ARGS` passthrough) + `.PHONY` entry.
- [ ] README headless section documents the `.env` → `make apply-rathole-env` flow.
- [ ] `ApplyRatholeEnvScriptTest` (JVM) covers syntax, dry-run success, masked secrets,
  missing-var error, missing-file error.
- [ ] **Manual QA Steps** (real device, NOT covered by automated tests): fill `.env`,
  `make apply-rathole-env ARGS=--start`, UI shows filled rathole fields, server starts, tunnel
  status moves off Disconnected (Connected only once the VPS ports 80/443/2333 accept outside
  connections — see the runbook diagnostics for the current port state).

### Task 1.1 — `.env.example` variables

**File (modify):** `.env.example` — append after the `PRIVACY_MODEL_DIR` block:

```
# Rathole (self-hosted tunnel, Plan 67).
# Used by `make apply-rathole-env` (host -> device via ADB_CONFIGURE broadcast) and by
# RatholeVpsConnectionTest: when RATHOLE_SERVER_ADDR, RATHOLE_SERVER_PUBLIC_KEY and
# RATHOLE_TOKEN are ALL set and the host `rathole` binary is on PATH, that test connects a
# real client to your VPS; otherwise the test is SKIPPED (CI has no .env, so it always
# skips there).
RATHOLE_SERVER_ADDR=
RATHOLE_SERVER_PUBLIC_KEY=
RATHOLE_TOKEN=
RATHOLE_PUBLIC_URL=
```

**DoD**
- [x] `.env` (gitignored) still ignored; example contains no real values.

### Task 1.2 — `scripts/apply-rathole-env.sh`

**File (new):** `scripts/apply-rathole-env.sh` (executable, `#!/usr/bin/env bash`):

```bash
#!/usr/bin/env bash
# Apply rathole tunnel settings from the project .env to the connected device via the
# ADB_CONFIGURE broadcast (README: "Headless Setup via ADB"). Zero app-code dependency.
#
# .env values must not contain spaces (the file is sourced, like the Makefile test targets).
#
# Usage: scripts/apply-rathole-env.sh [--start] [--dry-run]
#   --start    also broadcast ADB_START_SERVER after applying the settings
#   --dry-run  validate .env and print the command (secrets masked); needs no adb
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV_FILE="${RATHOLE_ENV_FILE:-$(dirname "$SCRIPT_DIR")/.env}"
RECEIVER_COMPONENT="com.danielealbano.androidremotecontrolmcp.services.mcp.AdbConfigReceiver"
ACTION_CONFIGURE="com.danielealbano.androidremotecontrolmcp.ADB_CONFIGURE"
ACTION_START_SERVER="com.danielealbano.androidremotecontrolmcp.ADB_START_SERVER"

START_SERVER=0
DRY_RUN=0
for arg in "$@"; do
  case "$arg" in
    --start)   START_SERVER=1 ;;
    --dry-run) DRY_RUN=1 ;;
    *) echo "ERROR: unknown option '$arg' (usage: apply-rathole-env.sh [--start] [--dry-run])" >&2; exit 2 ;;
  esac
done

if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run: cp .env.example .env, then fill in the RATHOLE_* values." >&2
  exit 1
fi
# Project's own gitignored KEY=VALUE file.
source "$ENV_FILE"

missing=()
for var in RATHOLE_SERVER_ADDR RATHOLE_SERVER_PUBLIC_KEY RATHOLE_TOKEN RATHOLE_PUBLIC_URL; do
  [[ -n "${!var:-}" ]] || missing+=("$var")
done
if (( ${#missing[@]} > 0 )); then
  echo "ERROR: missing or empty in $ENV_FILE: ${missing[*]}" >&2
  exit 1
fi

mask() {
  local v="$1"
  if (( ${#v} <= 16 )); then printf '****'; else printf '%s...%s' "${v:0:4}" "${v: -4}"; fi
}

if (( DRY_RUN )); then
  echo "[dry-run] configure: adb shell am broadcast -a $ACTION_CONFIGURE -n <app-id>/$RECEIVER_COMPONENT \\"
  echo "  --ez tunnel_enabled true --es tunnel_provider RATHOLE \\"
  echo "  --es rathole_server_addr '$RATHOLE_SERVER_ADDR' --es rathole_server_public_key '$(mask "$RATHOLE_SERVER_PUBLIC_KEY")' \\"
  echo "  --es rathole_token '$(mask "$RATHOLE_TOKEN")' --es rathole_public_url '$RATHOLE_PUBLIC_URL'"
  if (( START_SERVER )); then
    echo "[dry-run] start server: adb shell am broadcast -a $ACTION_START_SERVER -n <app-id>/$RECEIVER_COMPONENT"
  fi
  exit 0
fi

detect_app_id() {
  local pkgs id
  pkgs="$(adb shell pm list packages)"
  for id in com.danielealbano.androidremotecontrolmcp.gms.debug \
            com.danielealbano.androidremotecontrolmcp.foss.debug \
            com.danielealbano.androidremotecontrolmcp; do
    if grep -q "^package:$id\$" <<<"$pkgs"; then printf '%s' "$id"; return 0; fi
  done
  echo "ERROR: app not installed (tried gms.debug, foss.debug, release)." >&2
  return 1
}

connected="$(adb devices | grep -c $'\tdevice$' || true)"
if [[ "$connected" -eq 0 ]]; then echo "ERROR: no adb device connected (check: adb devices)." >&2; exit 1; fi
if [[ "$connected" -gt 1 ]]; then echo "ERROR: multiple adb devices connected; disconnect all but one." >&2; exit 1; fi

app_id="$(detect_app_id)"

adb shell am broadcast \
  -a "$ACTION_CONFIGURE" \
  -n "$app_id/$RECEIVER_COMPONENT" \
  --ez tunnel_enabled true \
  --es tunnel_provider RATHOLE \
  --es rathole_server_addr "$RATHOLE_SERVER_ADDR" \
  --es rathole_server_public_key "$RATHOLE_SERVER_PUBLIC_KEY" \
  --es rathole_token "$RATHOLE_TOKEN" \
  --es rathole_public_url "$RATHOLE_PUBLIC_URL"

if (( START_SERVER )); then
  adb shell am broadcast -a "$ACTION_START_SERVER" -n "$app_id/$RECEIVER_COMPONENT"
fi

echo "Rathole settings applied to $app_id."
echo "Note: if the MCP server was already running, restart it to pick up the tunnel config (rerun with --start or use the app UI)."
```

Notes:
- `RATHOLE_ENV_FILE` override exists ONLY for the JVM test's temp-env-file cases; default is
  `<repo-root>/.env`.
- Secrets on the adb command line match the trust model of the documented headless setup
  (README already documents broadcasts with values); the script itself never echoes them.

**DoD**
- [x] `bash -n` clean; executable bit set; no `set -x`; no secret echoed on any code path.

### Task 1.3 — `ApplyRatholeEnvScriptTest`

**File (new):** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/ApplyRatholeEnvScriptTest.kt`

**Setup:** JUnit5; script located via `File(System.getProperty("user.dir"), "../scripts/apply-rathole-env.sh")`
(Gradle test working dir = `app/` module dir; `fail` with a clear message if absent). Each test
writes a temp `.env` via `@TempDir` and spawns `bash <script> --dry-run` through ProcessBuilder
with `RATHOLE_ENV_FILE` in the environment (merge with `System.getenv()`; pass `env()` map).
Assert on exit code + stdout/stderr. No adb needed for any case (`--dry-run` path).

| Test | Verifies |
|------|----------|
| `script passes bash -n syntax check` | `bash -n <script>` exit 0 |
| `dry-run with full env prints configure command with masked secrets` | temp env with 4 vars (token `super-secret-token-123`, key 44 chars) → exit 0; stdout contains `ADB_CONFIGURE`, `RATHOLE_SERVER_ADDR` value, `tunnel_provider RATHOLE`; stdout does NOT contain the full token or full key (masked form = first 4 chars + `...` + last 4 chars, e.g. `supe...-123`; assert absence of the full literals, not the exact masked string) |
| `dry-run with --start also prints start-server command` | stdout contains `ADB_START_SERVER` |
| `dry-run fails when a variable is empty` | env missing `RATHOLE_TOKEN` → exit 1; stderr names `RATHOLE_TOKEN` |
| `dry-run fails when .env is missing` | `RATHOLE_ENV_FILE` → nonexistent path → exit 1; stderr mentions `.env` |
| `unknown option exits with usage` | `--bogus` → exit 2; stderr contains `usage` |

**DoD**
- [ ] The 6 new `ApplyRatholeEnvScriptTest` tests pass under `:app:testGmsDebugUnitTest` with
  no adb on PATH (full-suite state per the final quality gates below).

### Task 1.4 — Makefile target

**File (modify):** `Makefile`
- Add `apply-rathole-env` to the `.PHONY` line (the one starting `compile-cloudflared
  compile-ngrok-native download-rathole …`).
- Add target next to the other host-facing targets (e.g. right after `download-rathole`):

```make
apply-rathole-env: ## Apply rathole settings from .env to the device (ARGS=--start | --dry-run)
	bash scripts/apply-rathole-env.sh $(ARGS)
```

**DoD**
- [x] `make apply-rathole-env ARGS=--dry-run` works from a repo with a filled `.env`; `make apply-rathole-env` with no `.env` prints the cp guidance and exits non-zero.

### Task 1.5 — README

**File (modify):** `README.md` — in the "Headless Setup via ADB" section, right after the
main `adb shell am broadcast` example block (before the "Disable bearer authentication"
example), add:

````markdown
#### Configure rathole from `.env`

Instead of pasting values into the UI, keep them in the project's gitignored `.env`
(see `.env.example`) and apply them to the connected device with one command:

```bash
cp .env.example .env   # fill in the RATHOLE_* values once
make apply-rathole-env ARGS=--dry-run   # validate + preview (secrets masked)
make apply-rathole-env ARGS=--start     # apply + start the MCP server
```

The script auto-detects the installed app (gms/foss debug or release) and requires exactly
one connected adb device.
````

**DoD**
- [x] Rendered markdown is valid; no new mermaid; consistent with section heading levels.

## User Story 2 — Optional real-VPS connection test (env-gated, skip when unset)

Why: reproduces the live `Connecting`-forever situation from the host; failing message directly
names the VPS-side cause (auth) vs. silent no-server retry.

Acceptance criteria:
- [ ] `RatholeVpsConnectionTest` runs only when `RATHOLE_SERVER_ADDR` +
  `RATHOLE_SERVER_PUBLIC_KEY` + `RATHOLE_TOKEN` are all non-empty AND the host rathole binary
  resolves; otherwise the whole class is SKIPPED (JUnit5 assumption in `@BeforeAll`) — CI and
  default local runs are unaffected.
- [ ] When enabled: real provider + real host client against the real VPS → `Connected` within
  30s; a `TunnelStatus.Error` (e.g. wrong key/token) fails the test with the provider's message.
- [ ] No CI changes (no secrets → always skips there).

### Task 2.1 — `RatholeVpsConnectionTest`

**File (new):** `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/RatholeVpsConnectionTest.kt`

```kotlin
package com.danielealbano.androidremotecontrolmcp.integration

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.RatholeTunnelProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Optional REAL-VPS connection test.
 *
 * Runs only when RATHOLE_SERVER_ADDR, RATHOLE_SERVER_PUBLIC_KEY and RATHOLE_TOKEN are all set
 * in the environment (project `.env`, sourced by the Makefile test targets) and the host
 * `rathole` binary is on PATH; otherwise the whole class is SKIPPED via JUnit5 assumptions
 * (CI has no .env, so it always skips there). Connects a real rathole client to the real VPS
 * and asserts `Connected` — verifies the VPS side (port 2333 open, server key, service
 * token) without a phone, separating VPS-side problems from device-side ones.
 */
@DisplayName("RatholeVpsConnectionTest")
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class RatholeVpsConnectionTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var provider: RatholeTunnelProvider

    @JvmStatic
    @BeforeAll
    fun checkPrerequisites() {
        assumeTrue(
            envConfigured(),
            "RATHOLE_SERVER_ADDR / RATHOLE_SERVER_PUBLIC_KEY / RATHOLE_TOKEN not all set — " +
                "skipping the real-VPS connection test (fill them in .env to enable).",
        )
        assumeTrue(
            HostRatholeBinaryResolver().resolve() != null,
            "host rathole binary not found on PATH (install the pinned v0.5.0 x86_64 asset).",
        )
    }

    @BeforeEach
    fun setUp() {
        // Build.SUPPORTED_ABIS is null on the JVM
        mockkObject(RatholeTunnelProvider.Companion)
        every { RatholeTunnelProvider.isSupportedAbi() } returns true
        val mockContext = mockk<Context> { every { filesDir } answers { tempDir } }
        provider = RatholeTunnelProvider(HostRatholeBinaryResolver(), mockContext)
    }

    @AfterEach
    fun tearDown() {
        runBlocking { provider.stop() }
        unmockkAll()
    }

    @Test
    fun `connects to the configured VPS within 30s`() =
        runBlocking {
            val config =
                ServerConfig(
                    ratholeServerAddr = requireEnv("RATHOLE_SERVER_ADDR"),
                    ratholeServerPublicKey = requireEnv("RATHOLE_SERVER_PUBLIC_KEY"),
                    ratholeToken = requireEnv("RATHOLE_TOKEN"),
                    ratholePublicUrl = "https://vps-connection-test.local",
                )
            val localPort = ServerSocket(0).use { it.localPort }
            provider.start(localPort, config)
            val status =
                withTimeout(CONNECT_TIMEOUT_MS) {
                    provider.status.first { it is TunnelStatus.Connected || it is TunnelStatus.Error }
                }
            assertTrue(
                status is TunnelStatus.Connected,
                "VPS connection did not reach Connected within ${CONNECT_TIMEOUT_MS}ms: $status",
            )
        }

    private companion object {
        private const val CONNECT_TIMEOUT_MS = 30_000L

        private fun requireEnv(name: String): String =
            System.getenv(name) ?: error("$name missing — checkPrerequisites should have skipped")

        private fun envConfigured(): Boolean =
            listOf("RATHOLE_SERVER_ADDR", "RATHOLE_SERVER_PUBLIC_KEY", "RATHOLE_TOKEN")
                .all { !System.getenv(it).isNullOrEmpty() }
    }
}
```

Notes:
- `HostRatholeBinaryResolver` is the top-level class in `RatholeTunnelIntegrationTest.kt`
  (same package) — reuse, do not duplicate.
- `filesDir answers { tempDir }` — `answers {}` keeps the reference lazy (same pattern as
  `RatholeTunnelProviderTest` in Plan 66 US1).
- No backend process is started: rathole establishes the control channel at client startup;
  `local_addr` is only used when the server forwards a remote connection to it.
- The test's `ratholePublicUrl` is a dummy: the VPS test asserts the tunnel link only, not the
  Caddy-fronted public URL (that is a separate manual check from the internet: `curl
  https://mcp.irzaripov.ru/health` after the device is Connected).

**DoD**
- [ ] Without env vars: class reports SKIPPED (0 failed) under `:app:testGmsDebugUnitTest`.
- [ ] With a live reachable VPS (user's, after its ports are open): PASSES from the host.

## Commit plan (ordered)

1. `feat(rathole): host .env apply script for headless configuration` — Tasks 1.1, 1.2, 1.3,
   1.4, 1.5 (`.env.example`, script, `ApplyRatholeEnvScriptTest`, Makefile, README).
2. `test(rathole): optional real-VPS connection test (env-gated, skips when unset)` — Task 2.1.

## Final quality gates (after ALL user stories)

- [ ] `make lint` (ktlint + detekt) — zero NEW findings. Known pre-existing state stays: the
  detekt `UnusedParameter` in the user's uncommitted WIP `ScreenIntrospectionTools.kt` and any
  findings from the WIP `ScreenCaptureProvider.kt` (user decision, out of scope — do NOT
  "fix" user WIP).
- [ ] `make build` succeeds (APK unchanged content-wise; no jniLibs change).
- [ ] `make test-unit` — new tests green; full-suite state equals the Plan 66 accepted state
  (3 WIP test failures + NGROK_AUTHTOKEN env gap in this environment).
- [ ] `ApplyRatholeEnvScriptTest` + `RatholeVpsConnectionTest` run explicitly (skip-path +
  dry-run path) before the commit.
- [ ] code-reviewer subagent (plan-compliance mode) over the full implementation; ALL findings
  fixed; re-run until clean.
- [ ] Commits pushed to `fork` (`RuslanZaripov/android-remote-control-mcp`); NO PR (user
  decision).

**Known limitations (documented, not defects):**
- The apply script trusts `adb` shell access (same as the documented headless setup); values
  appear on the device's broadcast command line during the brief apply.
- `RATHOLE_VPS`-style separate variable names were rejected by the user: the VPS test reuses
  the device-target vars (same server). A `.env` pointing at a non-VPS rathole server makes the
  test connect there — acceptable, documented above.
- Script requires exactly one connected adb device (no `-s` support) by design — keeps the
  interface minimal.

## Review findings (plan-reviewer, resolved before implementation — 2026-09-09)

- **W1** (Task 1.5): outer fence was 3 backticks while the inner README snippet used a
  3-backtick `bash` fence, so the outer block closed early and the rest of US2 rendered as
  code. FIXED: outer fence is 4 backticks, inner stays 3.
- **W2** (Task 1.2 `mask()`): 9–16-char secrets were ~fully revealed (8 of 9 chars). FIXED:
  values ≤ 16 chars print as `****`; longer values reveal only 4+4 chars.
- **I1** (Task 1.3 row 2): stated masked literal `super...123` was wrong (actual:
  `supe...-123`). FIXED: row now asserts absence of the full literals, not an exact masked
  string.
- **I2** (Task 1.2): `source` of an unquoted value containing a space fails with a confusing
  bash error. FIXED: header note — values must not contain spaces (fails safely before any
  adb call).
- **I3** (Task 2.1 Notes): "verified in Plan 66 integration test 3" was a wrong citation.
  FIXED: reworded to the rathole control-channel architecture fact.
- **I4** (Task 2.1 Notes): MockK eager-evaluation rationale was inaccurate. FIXED: reworded
  to "same lazy `answers {}` pattern as `RatholeTunnelProviderTest`".
- **I5** (US1 Manual QA, Task 2.1 Notes): "Plan 66 Stage 4/6" are unresolvable (Plan 66 has
  no Stage sections). FIXED: rephrased to concrete port/curl steps.
- **I6** (Task 1.3 DoD): "6 tests pass" was ambiguous against the accepted full-suite state.
  FIXED: scoped to the 6 new `ApplyRatholeEnvScriptTest` tests.
- **I7** (Task 1.1 comment): skip trigger omitted the host-binary precondition. FIXED:
  comment now names both conditions.
- **I8** (final gates): baseline note missed the WIP `ScreenCaptureProvider.kt`. FIXED:
  broadened, with an explicit "do NOT fix user WIP".
- Verdict after fixes: all CRITICAL/WARNING/INFO resolved; no structural, ordering, JUnit,
  Makefile, README-anchor, or security-model defects were found.
