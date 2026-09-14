<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 70 — Rathole tunnel reliability: orphan cleanup, per-device service, heartbeat diagnostics

**Branch**: `feat/rathole-tunnel-provider` (user decision 2026-09-14: plans 66-69's rathole work — 30 commits —
is not yet merged into main, and this plan modifies that code, so it continues on the same branch;
the original "branch from main" line is superseded).

**Diagnosis (not derivable from code):**
- rathole allows exactly ONE control channel per service name; the name was hardcoded `mcp`
  → two phones (or a phone + its own orphan) evict each other
  (`Dropping previous control channel`, 40s `Heartbeat timed out` cycles on the VPS).
- rathole is a native child process: Android does NOT reap it on uninstall/force-stop/OOM,
  and `TunnelManager.start()` replaced `activeProvider` without stopping it → double clients.
- Client (pinned v0.5.0) resets its 40s heartbeat timer on EVERY received command; the observed
  exact 40.0s cycles mean zero server heartbeats arrived. The 8× `early eof` data-channel WARNs
  logged in the same millisecond as `Control channel established` are the PREVIOUS cycle's pooled
  data channels torn down by the server on handle replacement — cosmetic, not a cause.
- rathole has no per-visitor timeout and no admin API; the only liveness signal on the control
  channel is the one-way server heartbeat (v0.5.0 has TCP keepalive on the control channel, but a
  half-open mobile path can still starve it) → a dead link is detected only by `heartbeat_timeout`,
  and a visitor landing on a dead data channel hangs forever.
- User-approved scope: P0 (orphan cleanup + idempotent start) + P1 (per-device service name)
  + P2 (heartbeat tuning + diagnostics).

## User Story 1 — Never leave a stale rathole client behind (P0)

**Why:** stale clients keep fighting for the same VPS service long after the app is gone; the fix
is to kill our own orphans at start, never run two providers at once, and let the service shutdown
fully tear the process down.

Acceptance criteria:
- [ ] `RatholeTunnelProvider.start()` kills same-UID stale rathole clients (matched by the exact
      `<filesDir>/rathole/client.toml` argument in `/proc/<pid>/cmdline`) before launching; a
      foreign-UID process holding the config → `TunnelStatus.Error`, no launch.
- [ ] `TunnelManager.start()` stops the previously active provider before starting a new one
      (idempotent across repeat starts and provider switches).
- [ ] `McpServerService` tunnel-stop timeout covers the provider teardown (no orphan window on
      normal service destruction).
- [ ] Existing tunnel tests pass.

### Task 1.1 — Stale-client cleanup in RatholeTunnelProvider

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProvider.kt` — modify

Action 1 — companion: add after `SHUTDOWN_TIMEOUT_MS`:

```kotlin
internal const val DEFAULT_PROC_DIR = "/proc"

/** Pids in [procDir] whose cmdline contains an argument equal to [configPath]; unreadable entries are skipped. */
internal fun findStaleRatholePids(procDir: File, configPath: String): List<Int> {
    val entries = procDir.listFiles() ?: return emptyList()
    return entries
        .filter { it.isDirectory && it.name.all(Char::isDigit) }
        .mapNotNull { dir ->
            val isMatch =
                runCatching {
                    dir.resolve("cmdline")
                        .readBytes()
                        .toString(Charsets.ISO_8859_1)
                        .split('\u0000')
                        .any { it == configPath }
                }.getOrDefault(false)
            if (isMatch) dir.name.toInt() else null
        }
}

/** Real uid from the `Uid:` line of a `/proc/<pid>/status` file, or null when unreadable/malformed. */
internal fun processUidOrNull(statusFile: File): Int? =
    runCatching {
        statusFile
            .readLines()
            .firstOrNull { it.startsWith("Uid:") }
            ?.substringAfter(':')
            ?.trim()
            ?.split('\t')
            ?.firstOrNull()
            ?.toIntOrNull()
    }.getOrNull()

@Suppress("BlockingMethodInNonBlockingContext")
/** SIGKILL via the system `kill` (toybox on Android); true when signalled successfully. */
internal fun killProcess(pid: Int): Boolean =
    runCatching {
        ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() == 0
    }.getOrDefault(false)
```

Context: in the code above the char literals must be the Kotlin escape sequences (NUL — the
cmdline separator — and TAB — the status-file column separator) written as escapes in the
source, not as literal control characters.

Action 2 — constructor: add `procDir` with a default (Dagger invokes the synthetic 2-arg
constructor, so Hilt injection is unaffected; tests pass a fake dir):

```kotlin
class RatholeTunnelProvider
    @Inject
    constructor(
        private val binaryResolver: RatholeBinaryResolver,
        @param:ApplicationContext private val context: Context,
        private val procDir: File = File(DEFAULT_PROC_DIR),
    ) : TunnelProvider {
```

Action 3 — `start()`: call `if (!killStaleClients()) return` right after
`_status.value = TunnelStatus.Connecting` and BEFORE `writeClientConfig` (rathole hot-reloads its
config file — killing after the rewrite could keep an orphan alive on the fresh config). The
function MUST signal the abort to `start()` (a bare `return` would only exit the helper and let
`start()` overwrite the live foreign config — review finding CRITICAL-1). Add private fun:

```kotlin
/**
 * Kills rathole client processes orphaned by previous app runs (force-stop, OOM, uninstall —
 * Android does not reap child processes). A stale process carries this app's exact client.toml
 * path in its cmdline. Returns false (aborting the start) when a foreign-UID process holds the
 * config; true otherwise.
 */
private fun killStaleClients(): Boolean {
    val configPath = File(File(context.filesDir, "rathole"), "client.toml").absolutePath
    for (pid in findStaleRatholePids(procDir, configPath)) {
        val uid = processUidOrNull(File(procDir, "$pid/status"))
        when {
            uid == android.os.Process.myUid() -> {
                Log.w(TAG, "Killing stale rathole client (pid $pid)")
                killProcess(pid)
            }

            uid != null -> {
                Log.e(TAG, "Foreign process (uid $uid) holds the rathole config — aborting start")
                _status.value = TunnelStatus.Error(
                    "Another process (uid $uid) is using the rathole config — cannot start",
                )
                return false
            }

            else -> Log.w(TAG, "Stale rathole client found (pid $pid) with unreadable uid — leaving it")
        }
    }
    return true
}
```

Context: fully-qualified `android.os.Process` on purpose — importing `android.os.Process` would
shadow `java.lang.Process` used by the `process` property.

### Task 1.2 — Idempotent TunnelManager.start()

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/TunnelManager.kt` — modify

Action 1 — extract the stop body into a helper; `start()` stops the active provider first:

```kotlin
suspend fun start(localPort: Int) {
    mutex.withLock {
        val config = settingsRepository.serverConfig.first()

        if (!config.tunnelEnabled) return

        // A tunnel always targets an http://localhost origin, so it MUST NOT run while the
        // server serves HTTPS. (The McpServerService start path enforces this too.)
        if (config.httpsEnabled) return

        // Idempotency: never run two providers (and thus two rathole clients) at once.
        stopActiveProvider(logStop = false)

        val provider =
            when (config.tunnelProvider) {
                TunnelProviderType.CLOUDFLARE -> cloudflareTunnelProviderFactory.get()
                TunnelProviderType.NGROK -> ngrokTunnelProviderFactory.get()
                TunnelProviderType.RATHOLE -> ratholeTunnelProviderFactory.get()
            }

        statusRelayJob =
            scope.launch {
                provider.status.collect { _tunnelStatus.value = it }
            }

        try {
            provider.start(localPort, config)
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            statusRelayJob?.cancel()
            statusRelayJob = null
            throw e
        }
        activeProvider = provider
    }
}

suspend fun stop() {
    mutex.withLock {
        stopActiveProvider(logStop = true)
        _tunnelStatus.value = TunnelStatus.Disconnected
    }
}

/** Stops the currently active provider (no-op when none). Must be called under [mutex]. */
private suspend fun stopActiveProvider(logStop: Boolean) {
    if (logStop && _tunnelStatus.value !is TunnelStatus.Disconnected) {
        serverLogRepository.log(ServerLogEntry.Type.TUNNEL, "Tunnel stopped")
    }
    statusRelayJob?.cancel()
    statusRelayJob = null
    activeProvider?.stop()
    activeProvider = null
}
```

Context: the early returns (`tunnelEnabled`/`httpsEnabled`) keep their current no-stop semantics —
intentionally out of scope.

### Task 1.3 — Close the onDestroy orphan window

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/McpServerService.kt` — modify

Action 1 — companion: `TUNNEL_STOP_TIMEOUT_MS = 3_000L` → `7_000L` (provider teardown can take up
to 5s); update the comment above the stop block to: "Worst-case blocking time:
TUNNEL_STOP_TIMEOUT_MS (7s) + SHUTDOWN_GRACE_PERIOD_MS (1s) + SHUTDOWN_TIMEOUT_MS (5s) = ~13s total.
This is well within the Android service onDestroy ANR threshold (~200s), so blocking the main
thread here is acceptable."

Action 2 — `docs/ARCHITECTURE.md` shutdown-sequence line (~line 103): "Stops tunnel (with 3s
ANR-safe timeout)" → "Stops tunnel (with 7s ANR-safe timeout)" (review finding W5).

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProviderTest.kt` — modify

Test helper (new, private in the test class) — builds a fake proc dir entry. The `joinToString`
separator/prefix is the NUL escape string; the status file content is a single-line Kotlin string
with TAB and newline escape sequences (see the escape note in Task 1.1).

```kotlin
private fun fakeProcEntry(
    procDir: File,
    pid: Int,
    vararg cmdlineArgs: String,
    uid: Int,
) {
    val dir = File(procDir, pid.toString())
    dir.mkdirs()
    File(dir, "cmdline").writeBytes(cmdlineArgs.joinToString("\u0000", "\u0000") { it }.toByteArray())
    File(dir, "status").writeText("Name:\trathole\nUid:\t$uid\t0\t0\n")
}
```

Note (review finding I10): `createProvider()` gains an optional trailing
`procDir: File = File(RatholeTunnelProvider.DEFAULT_PROC_DIR)` parameter (used by the proc tests
below); the US3 constructor rewrite keeps this parameter last.

| Test | Verifies |
|------|----------|
| `findStaleRatholePids matches only the exact config path arg` | fake procDir: pid `123` cmdline args (`rathole`, `--client`, `<cfg>`), pid `456` (different path), pid `789` (no cmdline file), non-numeric dir `self` → `[123]` |
| `findStaleRatholePids returns empty for a missing dir` | non-existent dir → `emptyList()` |
| `processUidOrNull parses the Uid line and tolerates malformed input` | Uid line with 3 tab-separated columns, first `4242` → 4242; missing file / no `Uid:` line → null |
| `start kills a same-uid stale client and reaches Connected` | **Setup**: `Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Linux"))` (needs real `kill`); spawn a `#!/bin/sh` keepalive script (`while :; do sleep 0.2; done`) with the client.toml path as its argument; `fakeProcEntry` for its pid with `uid = android.os.Process.myUid()` (0 on JVM stubs — consistent with what production code reads); provider with `fakeBinaryEmitting("Control channel established")` → status becomes Connected and the stale script has exited (`staleProcess.waitFor()` returns within timeout) |
| `start with a foreign-uid stale client sets Error and does not launch` | fake procDir: pid `999` cmdline = client.toml path, status uid `9999`; `mockBinaryResolver.resolve()` stubbed (preflight runs BEFORE the cleanup, so it is called exactly once — review finding CRITICAL-1) → status Error containing `Another process (uid 9999)` AND `File(tmpDir, "rathole")` was never created (writeClientConfig unreached ⇒ no process launched) |
| `start with an unreadable-uid stale client proceeds` | fake procDir: pid `555` cmdline = client.toml path, NO status file → the unreadable-uid branch logs and continues; status reaches Connected (review finding I12) |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/TunnelManagerTest.kt` — modify

| Test | Verifies |
|------|----------|
| `start stops the previously active provider before starting a new one` | **Setup**: `every { mockSettingsRepository.serverConfig } returnsMany listOf(flowOf(cfConfig), flowOf(ngrokConfig))` (`serverConfig` is a `Flow` — each `first()` consumes the next flow; review finding W2); two `manager.start(8080)` → `coVerify(order = true) { mockCloudflareProvider.stop(); mockNgrokProvider.start(8080, ngrokConfig) }` |
| `start twice with the same provider stops and restarts it` | `returnsMany listOf(flowOf(cfConfig), flowOf(cfConfig))` → `coVerify(exactly = 1) { mockCloudflareProvider.stop() }` and the provider's `start` verified for both calls |

**DoD:**
- [ ] No behavior change for Cloudflare/ngrok providers. (Lint/tests run once at the end — final Quality gates.)

## User Story 2 — Per-device rathole service name (P1)

**Why:** one service name = one client on the VPS; making the name a user setting (UI + ADB +
TOML render) lets multiple phones share one rathole server, each with its own
`[server.services.<name>]` block and public URL.

Acceptance criteria:
- [ ] New setting `ratholeServiceName` (default `mcp`, TOML bare key 1-64 chars, persisted,
      validated), exposed in UI and ADB extra `rathole_service_name`.
- [ ] Rendered client.toml uses `[client.services.<name>]`.
- [ ] README documents the multi-device setup; PROJECT.md updated.

### Task 2.1 — Setting: ServerConfig + SettingsRepository

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt` — modify

Action 1 — after `ratholePublicUrl`:

```kotlin
val ratholePublicUrl: String = "",
val ratholeServiceName: String = DEFAULT_RATHOLE_SERVICE_NAME,
```

KDoc `@property ratholeServiceName`: `The rathole service name (TOML bare key; must match the server's [server.services.<name>] block; one service per device).`

Companion:

```kotlin
/** Default rathole service name (must match the server's `[server.services.<name>]` block). */
const val DEFAULT_RATHOLE_SERVICE_NAME = "mcp"
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt` — modify

Action 2 — key (next to the other `RATHOLE_*` keys): `private val RATHOLE_SERVICE_NAME_KEY = stringPreferencesKey("rathole_service_name")`

Action 3 — `mapPreferencesToServerConfig`: `ratholeServiceName = prefs[RATHOLE_SERVICE_NAME_KEY] ?: ServerConfig.DEFAULT_RATHOLE_SERVICE_NAME,`

Action 4 — file-private pattern near `HOSTNAME_PATTERN`: `private val RATHOLE_SERVICE_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")`

Action 5 — `RatholeSettings` class:

```kotlin
suspend fun updateServiceName(name: String) {
    change(RATHOLE_SERVICE_NAME_KEY, name, "rathole service name", redact = false)
}

fun validateServiceName(name: String): Result<String> =
    if (RATHOLE_SERVICE_NAME_PATTERN.matches(name)) {
        Result.success(name)
    } else {
        Result.failure(
            IllegalArgumentException(
                "rathole service name must be 1-64 chars: letters, digits, '_' or '-'",
            ),
        )
    }
```

Action 6 — companion delegation (next to the other rathole overrides):
`override suspend fun updateRatholeServiceName(name: String) = rathole.updateServiceName(name)` and
`override fun validateRatholeServiceName(name: String): Result<String> = rathole.validateServiceName(name)`

Context (user decision, review finding I7): deliberately NO build-time `.env` default for the
service name (unlike the other four rathole fields, Plan 68) — the per-device name is an
intentional per-device choice; the `mcp` default covers single-device setups.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt` — modify

```kotlin
/** Updates the rathole service name (TOML bare key; must match the server config). */
suspend fun updateRatholeServiceName(name: String)

/**
 * Validates a rathole service name: 1-64 chars of letters, digits, '_' or '-' (TOML bare key).
 * Pure, non-suspending.
 *
 * @return [Result.success] with the validated name, or [Result.failure] with an [IllegalArgumentException].
 */
fun validateRatholeServiceName(name: String): Result<String>
```

### Task 2.2 — UI: MainViewModel + TunnelSettingsScreen + strings

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt` — modify

Action 1 — input state (next to the other rathole inputs, ~line 119):

```kotlin
private val _ratholeServiceNameInput = MutableStateFlow("")
val ratholeServiceNameInput: StateFlow<String> = _ratholeServiceNameInput.asStateFlow()
```

Action 2 — config collection (~line 169): `_ratholeServiceNameInput.value = config.ratholeServiceName`

Action 3 — update fn (next to `updateRatholePublicUrl`, mirrors it):

```kotlin
fun updateRatholeServiceName(name: String) {
    _ratholeServiceNameInput.value = name
    viewModelScope.launch {
        settingsRepository.updateRatholeServiceName(name)
    }
}
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/TunnelSettingsScreen.kt` — modify

Action 1 — `val ratholeServiceNameInput by viewModel.ratholeServiceNameInput.collectAsStateWithLifecycle()` (next to the other rathole vals, ~line 78).

Action 2 — `RatholeConfigFields` call site: add `serviceName = ratholeServiceNameInput,` and `onServiceNameChange = viewModel::updateRatholeServiceName,`.

Action 3 — `RatholeConfigFields` composable: add params `serviceName: String,` (after `publicUrl`) and `onServiceNameChange: (String) -> Unit,` (after `onPublicUrlChange`); after the public-URL block, before the closing `Column` brace, add the standard field block (labelLarge label + `Spacer(4.dp)` + single-line `OutlinedTextField` with placeholder + help `bodySmall` `onSurfaceVariant`) using the three new string resources.

**File**: `app/src/main/res/values/strings.xml` — modify (after `remote_access_rathole_public_url_help`):

```xml
<string name="remote_access_rathole_service_name_label">Service Name</string>
<string name="remote_access_rathole_service_name_hint">mcp</string>
<string name="remote_access_rathole_service_name_help">Must match the [server.services.name] block on your VPS. Use a different name per device to run several phones against one server</string>
```

### Task 2.3 — ADB: AdbConfigHandler

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandler.kt` — modify

Action 1 — dispatch (after `applyRatholePublicUrl(intent)`, ~line 74): `applyRatholeServiceName(intent)`

Action 2 — method (next to `applyRatholePublicUrl`, mirrors its validate-then-update pattern):

```kotlin
private suspend fun applyRatholeServiceName(intent: Intent) {
    val value = intent.getStringExtra(EXTRA_RATHOLE_SERVICE_NAME) ?: return
    settingsRepository.validateRatholeServiceName(value).fold(
        onSuccess = {
            settingsRepository.updateRatholeServiceName(it)
            Log.i(TAG, "rathole service name updated to $it")
        },
        onFailure = { Log.w(TAG, "Ignoring invalid rathole_service_name '$value': ${it.message}") },
    )
}
```

Action 3 — companion const (next to the other rathole extras): `internal const val EXTRA_RATHOLE_SERVICE_NAME = "rathole_service_name"`

### Task 2.4 — Provider render + validation

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProvider.kt` — modify

Action 1 — `renderClientConfig`: add param `serviceName: String,` (after `token`); template line
`[client.services.mcp]` → `[client.services.$serviceName]`; KDoc: drop "service name fixed to `mcp`"
→ "service name from the settings ([ServerConfig.ratholeServiceName])".

Action 2 — `writeClientConfig` call site: pass `serviceName = config.ratholeServiceName,`.

Action 3 — companion: `internal val SERVICE_NAME_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")`;
`validateRatholeConfigFields`: new `when` branch before `else` (enforcement point for raw UI input,
which persists like the other rathole fields — user decision, review finding W4):

```kotlin
!SERVICE_NAME_PATTERN.matches(config.ratholeServiceName) -> {
    "rathole service name must be a TOML bare key (1-64 chars: letters, digits, '_' or '-')"
}
```

(Covers both the empty and the non-bare-key cases — an invalid name would otherwise render a
syntactically invalid client.toml and rathole would exit with a generic configuration error.)

Action 4 — class KDoc: replace "The server side (VPS) is configured separately; its service name
MUST be `mcp`." with "The server side (VPS) is configured separately and MUST define a
`[server.services.<name>]` block matching [ServerConfig.ratholeServiceName] (default `mcp`) —
one service name per device."

### Task 2.5 — Docs

**File**: `README.md` — modify

Action 1 — rathole VPS details block, after the server-toml code block, add:

```markdown
> **Multiple devices:** rathole allows one control channel per service name — two clients with
> the same name evict each other. To run several phones against one server, define one service
> per device (e.g. `[server.services.mcp2]` with its own token and `bind_addr`) and set the
> matching **Service Name** in each phone (UI: Tunnel → Self-hosted (rathole) → Service Name;
> ADB: `rathole_service_name`).
```

Action 2 — step 4 (In the app): add "Service Name" to the list of entered values.

Action 3 — ADB example block: after the `rathole_public_url` line add the line
`--es rathole_service_name "mcp"` with a trailing backslash continuation.

Action 4 — ADB extras table: after the `rathole_public_url` row add
`| rathole_service_name | string | rathole service name (TOML bare key; must match the server's [server.services.name] block) |`

**File**: `docs/PROJECT.md` — modify

Action 1 — the "Self-hosted rathole" bullet (~line 644): `(service name `mcp`)` →
`(service name is user-configurable, default `mcp`; one service per device)`.

Action 2 — settings-defaults list (~line 711, after "rathole Public URL"): add
`- **rathole Service Name**: `mcp` (TOML bare key, 1-64 chars; must match the server's `[server.services.<name>]` block)`.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` — modify
**Setup**: existing nested-class fixtures for the rathole fields (fake DataStore pattern).

| Test | Verifies |
|------|----------|
| `validateRatholeServiceName accepts valid TOML bare keys` | `"mcp"`, `"MCP-2"`, `"a_b_9"` → success |
| `validateRatholeServiceName rejects invalid values` | `""`, `"a b"`, `"a/b"`, `"a.b"`, `"m".repeat(65)` → failure |
| `rathole service name defaults to mcp and round-trips` | unset → `serverConfig` emits `"mcp"`; `updateRatholeServiceName("mcp2")` → emits `"mcp2"` |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt` — modify
**Setup**: existing intent-builder fixtures; extend the existing `stubRatholeValidators()` helper
with stubs for `validateRatholeServiceName` / `validateRatholeLogLevel` — the mock is
`relaxUnitFun`-only, so unstubbed `Result` returns throw (review finding I8).

| Test | Verifies |
|------|----------|
| `rathole_service_name extra is validated and stored` | valid `"mcp2"` → `updateRatholeServiceName("mcp2")` called |
| `invalid rathole_service_name extra is ignored` | `"a b"` → update not called |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt` — modify

| Test | Verifies |
|------|----------|
| `updateRatholeServiceName calls repository and updates input state` | mirrors the existing `updateRatholePublicUrl` test |
| `serverConfig collection sets rathole service name input` | extend the existing `serverConfig collection sets rathole input fields` test with `ratholeServiceName = "mcp2"` → input flow emits `"mcp2"` |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProviderTest.kt` — modify

| Test | Verifies |
|------|----------|
| `renderClientConfig renders the configured service name` | `serviceName = "mcp2"` → toml contains `[client.services.mcp2]` |
| `validateRatholeConfigFields rejects invalid service names` | `ratholeServiceName = ""` and `"a b"` → error contains `service name must be a TOML bare key` |
| `existing renderClientConfig test updated for the new param` | existing `renderClientConfig emits noise transport mcp service and quoted values` passes `serviceName = "mcp"`; its `[client.services.mcp]` assertion stays valid (review finding W3) |
| `connected line renders the configured service name in client.toml` | extend the existing Connected test: config `ratholeServiceName = "mcp2"` → written toml contains `[client.services.mcp2]` |

**DoD:**
- [ ] Default behavior unchanged for existing users (name stays `mcp`). (Lint/tests run once at the end — final Quality gates.)

## User Story 3 — Heartbeat tuning + diagnostics (P2)

**Why:** the client only detects a dead control channel via server heartbeats (one-way, no
keepalive) and the app currently gives no visibility into reconnect loops; make the server beat
faster (README), time the client out at 30s, cap visitor hangs at the proxy (Caddy), and make
reconnects diagnosable (RUST_LOG + in-app server log).

Acceptance criteria:
- [ ] Rendered client.toml pins `heartbeat_timeout = 30`.
- [ ] README VPS config sets `heartbeat_interval = 10` (with the >0 / below-client-timeout note)
      and provides a Caddy snippet with 30s read/write timeouts (with the visitor-hang note).
- [ ] New optional setting `ratholeLogLevel` (empty = default) is passed to the rathole process
      as the `RUST_LOG` env var (UI + ADB).
- [ ] `Failed to run the control channel` log lines are written to the TUNNEL server log,
      debounced to at most 1 entry / 60s (logcat gets every event — user decision, review finding I9).

### Task 3.1 — heartbeat_timeout in the rendered client.toml

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProvider.kt` — modify

Action 1 — companion: `internal const val HEARTBEAT_TIMEOUT_SECS = 30`

Action 2 — `renderClientConfig` template, `[client]` section becomes:

```toml
[client]
remote_addr = "$serverAddr"
heartbeat_timeout = $HEARTBEAT_TIMEOUT_SECS
```

Context: must stay ABOVE the server interval (README sets 10) and below rathole's 40s default →
dead-link detection ~10s earlier than stock.

### Task 3.2 — ratholeLogLevel setting (RUST_LOG)

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/model/ServerConfig.kt` — modify
Action 1 — after `ratholeServiceName`: `val ratholeLogLevel: String = "",`
KDoc: `@property ratholeLogLevel Optional RUST_LOG filter passed to the rathole client process (e.g. rathole::client=debug); empty = rathole's default level.`

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImpl.kt` — modify
Action 2 — key: `private val RATHOLE_LOG_LEVEL_KEY = stringPreferencesKey("rathole_log_level")`
Action 3 — mapping: `ratholeLogLevel = prefs[RATHOLE_LOG_LEVEL_KEY] ?: "",`
Action 4 — file-private: `private val RATHOLE_LOG_LEVEL_PATTERN = Regex("^[A-Za-z0-9_=,:.*_-]+$")`
Action 5 — `RatholeSettings`:

```kotlin
suspend fun updateLogLevel(level: String) {
    change(RATHOLE_LOG_LEVEL_KEY, level, "rathole log level", redact = false)
}

fun validateLogLevel(level: String): Result<String> =
    if (level.isEmpty() || RATHOLE_LOG_LEVEL_PATTERN.matches(level)) {
        Result.success(level)
    } else {
        Result.failure(
            IllegalArgumentException(
                "rathole log level must be a RUST_LOG filter " +
                    "(letters, digits, '=', ',', ':', '.', '*', '-', '_')",
            ),
        )
    }
```

Action 6 — companion delegation: `updateRatholeLogLevel` / `validateRatholeLogLevel` overrides.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepository.kt` — modify
Interface methods + KDoc mirroring the service-name pair; empty value is valid and clears the filter.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModel.kt` — modify
`_ratholeLogLevelInput` StateFlow pair (next to the service-name inputs), config collection
(`_ratholeLogLevelInput.value = config.ratholeLogLevel`), and `updateRatholeLogLevel` mirroring
`updateRatholeServiceName`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/ui/screens/settings/TunnelSettingsScreen.kt` — modify
Collect the new input; `RatholeConfigFields` gains `logLevel: String` + `onLogLevelChange: (String) -> Unit`;
add the standard field block after the service-name block (plain `OutlinedTextField` with the hint).

**File**: `app/src/main/res/values/strings.xml` — modify (after the service-name strings):

```xml
<string name="remote_access_rathole_log_level_label">Log Level (advanced)</string>
<string name="remote_access_rathole_log_level_hint">rathole::client=debug</string>
<string name="remote_access_rathole_log_level_help">RUST_LOG filter passed to the rathole process; empty = rathole default. Use rathole::client=debug to trace heartbeats and reconnects</string>
```

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandler.kt` — modify
`EXTRA_RATHOLE_LOG_LEVEL = "rathole_log_level"`; `applyRatholeLogLevel(intent)` mirroring
`applyRatholeServiceName` (empty is valid → clears the filter); dispatch call after
`applyRatholeServiceName`.

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProvider.kt` — modify
In `start()`, the process builder becomes:

```kotlin
val pb = ProcessBuilder(binaryPath, "--client", configPath)
if (config.ratholeLogLevel.isNotEmpty()) {
    pb.environment()["RUST_LOG"] = config.ratholeLogLevel
}
pb.redirectErrorStream(true)
```

### Task 3.3 — Control-channel drop logging

**File**: `app/src/main/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProvider.kt` — modify

Action 1 — constructor: add `private val serverLogRepository: ServerLogRepository,` (after `context`,
before `procDir`); import `ServerLogRepository` and `ServerLogEntry`. Final constructor shape:
`(binaryResolver, context, serverLogRepository, procDir = File(DEFAULT_PROC_DIR))`.

Action 2 — companion: `internal const val MSG_CONTROL_CHANNEL_FAILED = "Failed to run the control channel"`,
`internal const val MAX_LOG_REASON_LENGTH = 200`, `internal const val DROP_LOG_DEBOUNCE_MS = 60_000L`;
class property: `private var lastDropLogAtMs = 0L`

Action 3 — `handleLine`: new `when` branch (after the auth-failed branch, before the connected branch):

```kotlin
line.contains(MSG_CONTROL_CHANNEL_FAILED) -> {
    val reason = line
        .substringAfter("$MSG_CONTROL_CHANNEL_FAILED: ", missingDelimiterValue = "")
        .trim()
        .take(MAX_LOG_REASON_LENGTH)
    Log.w(TAG, "rathole control channel dropped: $reason")
    val now = System.currentTimeMillis()
    if (now - lastDropLogAtMs >= DROP_LOG_DEBOUNCE_MS) {
        lastDropLogAtMs = now
        serverLogRepository.log(ServerLogEntry.Type.TUNNEL, "rathole control channel dropped: $reason")
    }
}
```

Context: status intentionally stays as-is — a drop is transient (rathole backs off and
reconnects); only auth failure is terminal. The 60s debounce (user decision, review finding I9)
prevents ~1 entry/s server-log spam while a VPS is down (rathole retry backoff caps at
`retry_interval`, default 1s); `handleLine` runs on the single log-reader coroutine, so
`lastDropLogAtMs` needs no synchronization.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProviderTest.kt` — modify
`createProvider()` → `RatholeTunnelProvider(mockBinaryResolver, mockContext, RecordingServerLogRepository())`
(import `com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository`).

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/RatholeTunnelIntegrationTest.kt` — modify
Constructor call (~line 73): add `serverLogRepository = RecordingServerLogRepository(),`
(named arguments; the `procDir` default still applies).

### Task 3.4 — README VPS config + Caddy + ADB + PROJECT.md

**File**: `README.md` — modify

Action 1 — VPS server toml example: under `[server]` add `heartbeat_interval = 10`; after the toml
block add: "Keep `heartbeat_interval > 0` and below the client's `heartbeat_timeout` (the app ships
`heartbeat_timeout = 30`): the client detects a dead link only through these heartbeats."

Action 2 — step 3 (TLS reverse proxy): after the existing sentence add a Caddyfile snippet and the
note: "Set 30s upstream read/write timeouts (snippet: Caddy; equivalent for nginx): rathole has no
per-visitor timeout, so without them a visitor landing on a dead tunnel data channel hangs forever
instead of getting a 504."

```
mcp.example.com {
    reverse_proxy 127.0.0.1:8081 {
        transport http {
            read_timeout 30s
            write_timeout 30s
        }
    }
}
```

Action 3 — ADB example block: after the `rathole_service_name` line add
`--es rathole_log_level "rathole::client=debug"` with a trailing backslash continuation.

Action 4 — ADB extras table: after the `rathole_service_name` row add
`| rathole_log_level | string | optional RUST_LOG filter for the rathole process (empty = default level) |`

**File**: `docs/PROJECT.md` — modify
Settings-defaults list: after the "rathole Service Name" line add
`- **rathole Log Level**: Empty (optional RUST_LOG filter passed to the rathole process, e.g. rathole::client=debug for heartbeat/reconnect tracing)`.

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/tunnel/RatholeTunnelProviderTest.kt` — modify

| Test | Verifies |
|------|----------|
| `renderClientConfig pins heartbeat_timeout 30` | rendered toml contains `heartbeat_timeout = 30` |
| `start passes ratholeLogLevel to the process as RUST_LOG` | **Setup**: fake binary script `#!/bin/sh` running `printf '%s' "$RUST_LOG" > "$2.rustlog"` then `sleep 60` (argv: `--client`, config path); config `ratholeLogLevel = "rathole::client=debug"` → after start, `<cfg>.rustlog` contains the value; a second start with empty level → empty file. Call `provider.stop()` between the two starts — the `check(process == null)` guard throws otherwise (review finding I10) |
| `control channel drop line is written to the TUNNEL server log` | **Setup**: `RecordingServerLogRepository`; `fakeBinaryEmitting("Control channel established", "Failed to run the control channel: Heartbeat timed out. Retry in 500ms...")` → a TUNNEL entry containing `Heartbeat timed out` exists and status stays Connected |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/data/repository/SettingsRepositoryImplTest.kt` — modify

| Test | Verifies |
|------|----------|
| `validateRatholeLogLevel accepts empty and valid filters` | `""`, `"info"`, `"rathole::client=debug"`, `"warn,rathole=trace"` → success |
| `validateRatholeLogLevel rejects unsafe characters` | `"a b"`, `"a;b"`, `"$(reboot)"`, `"a/b"` → failure |
| `rathole log level defaults to empty and round-trips` | unset → `serverConfig` emits `""`; `updateRatholeLogLevel("debug")` → emits `"debug"` |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/services/mcp/AdbConfigHandlerTest.kt` — modify
**Setup**: reuses the US2 `stubRatholeValidators()` extension (already includes `validateRatholeLogLevel`).

| Test | Verifies |
|------|----------|
| `rathole_log_level extra updates the setting` | valid `"debug"` → `updateRatholeLogLevel("debug")` called |
| `invalid rathole_log_level extra is ignored` | `"a b"` → update not called |

**File**: `app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/ui/viewmodels/MainViewModelTest.kt` — modify

| Test | Verifies |
|------|----------|
| `updateRatholeLogLevel calls repository and updates input state` | mirrors the service-name test |
| `serverConfig collection sets rathole log level input` | extend the same collection test already extended in US2 with `ratholeLogLevel = "debug"` → input flow emits `"debug"` (review finding I13) |

**DoD:**
- [ ] Host rathole integration path unchanged: default service name `mcp`, log level unset (no `RUST_LOG` env). (Lint/tests run once at the end — final Quality gates.)

## Quality gates (run ONLY after all user stories are implemented)

- [ ] `make lint` — zero ktlint/detekt findings, no suppressions added beyond the two documented in the actions.
- [ ] `./gradlew build` — succeeds without errors and without new warnings.
- [ ] `make test-unit` — full unit + JVM integration suite green.
- [ ] `code-reviewer` subagent in plan-compliance mode over the whole implementation; fix ALL findings; re-run until clean.
- [ ] Push + `gh pr create` per `docs/TOOLS.md`; report the PR URL.

**Commit order** (one commit per user story, staged files only — never `git add -A`):
1. `feat(tunnel): kill stale rathole clients and make tunnel start idempotent`
2. `feat(tunnel): per-device rathole service name`
3. `feat(tunnel): rathole heartbeat tuning and diagnostics`

## Review findings (plan-reviewer, 2026-09-14) — all addressed

Verdict received: APPROVE_WITH_CHANGES. All findings resolved before implementation:

| # | Sev | Finding | Resolution |
|---|-----|---------|------------|
| 1 | CRITICAL | foreign-uid `return` only exited the helper — `start()` would still overwrite the live foreign config and launch a 2nd client; test expectation on `resolve()` self-contradictory | Task 1.1 Action 3 rewritten: `killStaleClients(): Boolean`, `start()` does `if (!killStaleClients()) return`; test now asserts `File(tmpDir, "rathole")` never created, `resolve()` stubbed (preflight runs first) |
| 2 | WARNING | `returnsMany listOf(cfConfig, ngrokConfig)` does not compile — `serverConfig` is `Flow<ServerConfig>` | US1 TunnelManagerTest rows use `returnsMany listOf(flowOf(cfConfig), flowOf(ngrokConfig))` |
| 3 | WARNING | existing `renderClientConfig emits noise transport mcp service and quoted values` test breaks on the new param | US2 test table: row updating the existing call to `serviceName = "mcp"` |
| 4 | WARNING | UI persists raw service name; invalid value → invalid TOML → generic rathole exit | USER DECISION: preflight rejection — `SERVICE_NAME_PATTERN` + new `validateRatholeConfigFields` branch (Task 2.4 Action 3) + test row |
| 5 | WARNING | `docs/ARCHITECTURE.md:103` "3s ANR-safe timeout" goes stale | Task 1.3 Action 2 added |
| 6 | WARNING | per-task DoDs embedded lint/test commands (run-once-at-end rule) | DoDs of US1/US2/US3 stripped to behavioral items; gates stay in the final section |
| 7 | INFO | no `.env` bake-in for the service name (Plan 68 pattern) | USER DECISION: out of scope, documented as Context in Task 2.1 |
| 8 | INFO | `stubRatholeValidators()` must gain the two new validator stubs | US2 AdbConfigHandlerTest Setup line updated; US3 reuses it |
| 9 | INFO | drop logging = ~1 server-log entry/s while VPS down | USER DECISION: 60s debounce (`DROP_LOG_DEBOUNCE_MS`), logcat unaffected; Task 3.3 rewritten |
| 10 | INFO | `createProvider()` must keep `procDir` through US3; RUST_LOG test needs `stop()` between starts | note added after the US1 test helper; RUST_LOG test row updated |
| 11 | INFO | TOCTOU between /proc scan and kill | accepted (sub-ms window, own-UID only) — no action |
| 12 | INFO | unreadable-uid branch untested | US1 test row `start with an unreadable-uid stale client proceeds` added |
| 13 | INFO | no config-collection test for `ratholeLogLevel` | US3 MainViewModelTest row added |
| 14 | INFO | new suppressions vs. codebase precedent | consistent with existing suppressions in the same files — no action |
| 15 | INFO | "no TCP keepalive" imprecise (v0.5.0 keepalives the control channel) | header diagnosis bullet reworded |
