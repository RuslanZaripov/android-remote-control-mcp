<!-- SACRED DOCUMENT — DO NOT MODIFY except for checkmarks ([ ] → [x]) and review findings. -->
<!-- You MUST NEVER alter, revert, or delete files outside the scope of this plan. -->
<!-- Plans in docs/plans/ are PERMANENT artifacts. There are ZERO exceptions. -->

# Plan 66 — Self-hosted rathole tunnel provider (in-app rathole client)

**Purpose (not derivable from code):** Cloudflare Quick Tunnels are unreachable from the target
user's network (RKN blocks `api.trycloudflare.com` / Cloudflare edge), and ngrok requires a
third-party account. rathole is a small self-hosted TCP tunnel: the user runs a rathole **server**
on their own VPS (already done: service `mcp` → Caddy with letsencrypt → local port, domain
`mcp.irzaripov.ru`), and the phone runs the rathole **client**. This plan adds that client as a
third in-app tunnel provider ("Self-hosted (rathole)") so the user selects it in Settings → Tunnel
and enters four values. No Termux/adb steps on the phone.

**rathole facts (empirically verified against v0.5.0 sources — latest release, 2023-10-01):**
- CLI: `rathole --client <config.toml>`; config auto-detects server/client mode from the top-level table.
- Log markers (stdout, tracing fmt — verified on the real binary: ALL tracing lines go to
  stdout, stderr is empty): connected = `Control channel established`; auth failure =
  `Authentication failed: <service>` (client would then retry forever → we MUST kill the process);
  transient connect failures are retried by rathole itself with backoff (leave status `Connecting`).
- Official v0.5.0 `aarch64-unknown-linux-musl` build has **no TLS** feature → transport is pinned
  to **Noise** (`Noise_NK_25519_ChaChaPoly_BLAKE2s`); the server is authenticated by the pinned
  public key, so the client may also connect by bare IP.
- Pinned release assets (sha256 verified):
  - `rathole-aarch64-unknown-linux-musl.zip` = `fa4a6fc63d86f8f1faa7c103a845e4715ce79a048455c0eec897b27237576564`
    (static ELF aarch64 → runs on Android; bundled as `jniLibs/arm64-v8a/librathole.so`)
  - `rathole-x86_64-unknown-linux-gnu.zip` = `3e7d0d0f365120cd3cd351d147d1a12ee960c8068b464d4dd533a3821873b80e`
    (static PIE; host/CI use only — not for Android bionic)
- **arm64-v8a only**: rathole publishes no Android x86_64 build; other ABIs get the graceful
  unsupported error (ngrok pattern). Consequence: no redroid (x86_64) E2E for this provider —
  covered by a JVM integration test using the host x86_64 binary on loopback.

**Approved decisions (agreed with the user):**
1. New required setting `ratholePublicUrl` (the https URL fronting the tunnel; the client cannot
   discover it, unlike Cloudflare Quick Tunnels).
2. NO automatic `publicUrlOverride` sync — the user sets Public URL override manually (same as
   Cloudflare TOKEN mode).
3. Pin rathole **v0.5.0**; bundle **arm64-v8a only**.
4. Server service name is fixed to `mcp` (matches the user's VPS server config; documented in UI help text).

**Scope boundary — files this plan may touch (and NO others):**
- `app/src/main/kotlin/.../data/model/TunnelProviderType.kt`
- `app/src/main/kotlin/.../data/model/ServerConfig.kt`
- `app/src/main/kotlin/.../data/model/TunnelStatus.kt` (KDoc only)
- `app/src/main/kotlin/.../data/repository/SettingsRepository.kt`
- `app/src/main/kotlin/.../data/repository/SettingsRepositoryImpl.kt`
- `app/src/main/kotlin/.../services/tunnel/RatholeBinaryResolver.kt` (new)
- `app/src/main/kotlin/.../services/tunnel/AndroidRatholeBinaryResolver.kt` (new)
- `app/src/main/kotlin/.../services/tunnel/RatholeTunnelProvider.kt` (new)
- `app/src/main/kotlin/.../services/tunnel/TunnelManager.kt`
- `app/src/main/kotlin/.../di/AppModule.kt`
- `app/src/main/kotlin/.../ui/viewmodels/MainViewModel.kt`
- `app/src/main/kotlin/.../ui/screens/settings/TunnelSettingsScreen.kt`
- `app/src/main/kotlin/.../services/mcp/AdbConfigHandler.kt`
- `app/src/main/res/values/strings.xml`
- `app/src/test/kotlin/.../services/tunnel/RatholeTunnelProviderTest.kt` (new)
- `app/src/test/kotlin/.../services/tunnel/TunnelManagerTest.kt`
- `app/src/test/kotlin/.../data/repository/SettingsRepositoryImplTest.kt`
- `app/src/test/kotlin/.../ui/viewmodels/MainViewModelTest.kt`
- `app/src/test/kotlin/.../services/mcp/AdbConfigHandlerTest.kt`
- `app/src/test/kotlin/.../integration/RatholeTunnelIntegrationTest.kt` (new)
- `Makefile`
- `.gitignore`
- `.github/workflows/ci.yml`
- `README.md`
- `docs/PROJECT.md`

`.../` = `com/danielealbano/androidremotecontrolmcp`.
`app/src/main/jniLibs/arm64-v8a/librathole.so` is a gitignored build artifact (produced by
`make download-rathole`); it is NOT committed. `RatholeTunnelProvider` is Hilt-auto-provided
(`@Inject` constructor), but the `RatholeBinaryResolver` INTERFACE needs an `@Binds` in
`AppModule.kt` (same as `CloudflaredBinaryResolver` — Task 1.3, Action 2).

**Plan document handling:** this file is a tracked, PERMANENT artifact and MUST be committed as a
standalone `docs(plans): add plan 66` commit (repo convention). It is the ONLY file outside the
scope boundary this plan's PR adds.

**Branch & commit order (implementation phase):** branch `feat/rathole-tunnel-provider` from
latest `main`; commit `docs(plans): add plan 66` first, then one commit per user story in order
(`feat(tunnel): add rathole provider core`, `feat(tunnel): persist and validate rathole settings`,
`feat(tunnel): rathole UI and ADB configuration`, `test(tunnel): rathole integration test, CI, docs`);
push after each user story; lint + full test suite ONLY after all stories; then code-reviewer in
plan-compliance mode, then PR.

---

## User Story 1 — Bundle the rathole client and add the provider core

**Why:** the app must be able to start/stop/monitor a rathole child process exactly like
cloudflared (Process + log-line parsing), with the binary shipped inside the APK so no manual steps
are needed on the phone.

**Acceptance criteria:**
- [x] `TunnelProviderType.RATHOLE` exists.
- [x] `ServerConfig` has the four rathole fields (default `""`); DataStore round-trips them.
- [x] `RatholeTunnelProvider` writes a `client.toml` (Noise transport, service `mcp`), runs the
  binary, transitions Connecting → `Connected(endpoints=[ratholePublicUrl])` on
  `Control channel established`, → Error + process kill on `Authentication failed`, → Error on
  unexpected exit, graceful idempotent `stop()`.
- [x] Non-arm64-v8a devices get the graceful "not supported … Use Cloudflare instead" error.
- [x] `TunnelManager` selects the rathole provider when `tunnelProvider == RATHOLE`.
- [x] `make download-rathole` fetches the pinned v0.5.0 asset, verifies sha256, installs
  `app/src/main/jniLibs/arm64-v8a/librathole.so`; all `build*` targets depend on it.
- [x] CI e2e job caches/downloads the .so; unit tests green.
- [ ] **Manual QA Steps** (real arm64-v8a device — NOT covered by automated tests): connect once
  with a bare IP (`62.233.43.72:2333`) and once with a hostname; verify both reach `Connected`.
  Risk: the static musl binary resolves DNS via `/etc/resolv.conf`, which stock Android does not
  provide — if hostname resolution fails, document IP-only support in the UI help text.

### Task 1.1 — Add `RATHOLE` to the provider enum and `ServerConfig`

**Action 1 — modify** `app/src/main/kotlin/.../data/model/TunnelProviderType.kt` (full new content):

```kotlin
package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * Available tunnel provider types for remote access.
 */
enum class TunnelProviderType {
    /** Cloudflare Quick Tunnel — no account needed, random URL per session. */
    CLOUDFLARE,

    /** ngrok tunnel — requires authtoken, supports stable domains. */
    NGROK,

    /** Self-hosted rathole tunnel — user's own VPS, Noise transport, fixed public URL. */
    RATHOLE,
}
```

**Action 2 — modify** `app/src/main/kotlin/.../data/model/ServerConfig.kt`: in the class KDoc,
after the `@property cloudflareTunnelToken` line insert the four `@property` lines below; change
the `@property tunnelProvider` line text `(Cloudflare or ngrok)` → `(Cloudflare, ngrok, or rathole)`;
in the data class body, after `val cloudflareTunnelExtraArgs: String = "",` insert the four fields.

Fields to insert:

```kotlin
    val ratholeServerAddr: String = "",
    val ratholeServerPublicKey: String = "",
    val ratholeToken: String = "",
    val ratholePublicUrl: String = "",
```

KDoc lines to insert (after the `@property cloudflareTunnelToken` line):

```
 * @property ratholeServerAddr The rathole server address as `host:port` (required when using rathole).
 * @property ratholeServerPublicKey The rathole server Noise public key (base64, from `rathole --genkey`).
 * @property ratholeToken The rathole service token shared with the server config (required when using rathole).
 * @property ratholePublicUrl The public https:// URL fronting the rathole tunnel (required when using rathole).
```

**Action 3 — modify** `app/src/main/kotlin/.../data/model/TunnelStatus.kt` (KDoc only): in the
`TunnelEndpoint.valid` KDoc change `Always `true` for Free/ngrok;` → `Always `true` for
Free/ngrok/rathole;`; in the `Connected.endpoints` KDoc change `Free/ngrok always expose exactly
one (always-valid) endpoint.` → `Free/ngrok/rathole always expose exactly one (always-valid)
endpoint.`

### Task 1.2 — Add the rathole binary resolvers

**Action 1 — create** `app/src/main/kotlin/.../services/tunnel/RatholeBinaryResolver.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

/**
 * Resolves the filesystem path to the rathole binary.
 *
 * Production implementation resolves the binary from the app's native library
 * directory (extracted by the package manager at install time).
 * Test implementations can point to a host-native binary.
 */
interface RatholeBinaryResolver {
    /** Returns the absolute path to the rathole binary, or null if not found. */
    fun resolve(): String?
}
```

**Action 2 — create** `app/src/main/kotlin/.../services/tunnel/AndroidRatholeBinaryResolver.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

/**
 * Resolves the rathole binary from the app's native library directory.
 *
 * The binary is packaged as `librathole.so` in `jniLibs/arm64-v8a/` and extracted
 * by the Android package manager to `nativeLibraryDir` at install time (requires
 * `useLegacyPackaging = true`). The native library directory has execute permissions,
 * allowing the binary to be run as a child process via `ProcessBuilder`.
 */
class AndroidRatholeBinaryResolver
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
    ) : RatholeBinaryResolver {
        override fun resolve(): String? {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val binaryFile = File(nativeLibDir, LIBRARY_NAME)

            return when {
                !binaryFile.exists() -> {
                    Log.e(TAG, "rathole binary not found at: ${binaryFile.absolutePath}")
                    null
                }

                !binaryFile.canExecute() -> {
                    Log.e(TAG, "rathole binary is not executable: ${binaryFile.absolutePath}")
                    null
                }

                else -> {
                    binaryFile.absolutePath
                }
            }
        }

        companion object {
            private const val TAG = "MCP:RatholeResolver"
            internal const val LIBRARY_NAME = "librathole.so"
        }
    }
```

### Task 1.3 — Add `RatholeTunnelProvider`

**Action 1 — create** `app/src/main/kotlin/.../services/tunnel/RatholeTunnelProvider.kt`:

```kotlin
package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Self-hosted rathole tunnel provider.
 *
 * Runs the `rathole` client binary as a child process (Noise transport, pinned to the
 * server's public key), forwarding `http://localhost:<localPort>` to a self-hosted
 * rathole server ([ServerConfig.ratholeServerAddr]). The server side (VPS) is configured
 * separately; its service name MUST be `mcp`.
 *
 * The public URL is not discoverable from the client (unlike Cloudflare Quick Tunnels),
 * so it is user-configured ([ServerConfig.ratholePublicUrl]) and published as the single
 * Connected endpoint.
 *
 * Available on `arm64-v8a` devices only (the bundled binary is a static
 * aarch64-linux-musl build; rathole publishes no Android x86_64 build).
 */
class RatholeTunnelProvider
    @Inject
    constructor(
        private val binaryResolver: RatholeBinaryResolver,
        @param:ApplicationContext private val context: Context,
    ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var process: Process? = null
        private var logReaderJob: Job? = null
        private var processMonitorJob: Job? = null

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(process == null) { "Tunnel is already running" }

                if (!isSupportedAbi()) {
                    val abis = Build.SUPPORTED_ABIS?.joinToString() ?: "unknown"
                    _status.value =
                        TunnelStatus.Error(
                            "rathole is not supported on this device architecture " +
                                "($abis). Use Cloudflare instead.",
                        )
                    return
                }

                val missing =
                    listOf(
                        config.ratholeServerAddr to "server address",
                        config.ratholeServerPublicKey to "server public key",
                        config.ratholeToken to "service token",
                        config.ratholePublicUrl to "public URL",
                    )
                        .filter { it.first.isEmpty() }
                        .joinToString(", ") { it.second }
                if (missing.isNotEmpty()) {
                    _status.value = TunnelStatus.Error("rathole configuration is missing: $missing")
                    return
                }

                val unsafe =
                    listOf(
                        config.ratholeServerAddr to "server address",
                        config.ratholeServerPublicKey to "server public key",
                        config.ratholeToken to "service token",
                        config.ratholePublicUrl to "public URL",
                    )
                        .filter { containsUnsafeTomlChars(it.first) }
                        .joinToString(", ") { it.second }
                if (unsafe.isNotEmpty()) {
                    _status.value =
                        TunnelStatus.Error(
                            "rathole configuration contains unsupported characters " +
                                "(quote, backslash, or control chars): $unsafe",
                        )
                    return
                }

                if (!isValidPublicKey(config.ratholeServerPublicKey)) {
                    _status.value =
                        TunnelStatus.Error(
                            "rathole server public key must be the 44-char base64 Noise key " +
                                "printed by `rathole --genkey`",
                        )
                    return
                }

                val binaryPath =
                    binaryResolver.resolve() ?: run {
                        _status.value = TunnelStatus.Error("rathole binary not found")
                        return
                    }

                _status.value = TunnelStatus.Connecting
                try {
                    val configPath = writeClientConfig(localPort, config)
                    // rathole logs to stdout — merge so the log reader sees every line
                    val pb = ProcessBuilder(binaryPath, "--client", configPath)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    process = proc

                    launchLogReader(proc) { line -> handleLine(line, config.ratholePublicUrl) }
                    startProcessMonitor(proc)
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    Log.e(TAG, "Failed to start rathole process", e)
                    _status.value = TunnelStatus.Error("Failed to start rathole: ${e.message}")
                    process = null
                }
            }
        }

        /**
         * Writes the rathole client config to `<filesDir>/rathole/client.toml`
         * (overwritten on every start — idempotent) and returns its absolute path.
         */
        private fun writeClientConfig(
            localPort: Int,
            config: ServerConfig,
        ): String {
            val dir = File(context.filesDir, "rathole")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "client.toml")
            file.writeText(
                renderClientConfig(
                    serverAddr = config.ratholeServerAddr,
                    publicKey = config.ratholeServerPublicKey,
                    token = config.ratholeToken,
                    localAddr = "127.0.0.1:$localPort",
                ),
            )
            return file.absolutePath
        }

        /**
         * Handles one rathole log line (merged stdout/stderr). `Authentication failed` is
         * terminal (rathole would retry forever) → error + kill. `Control channel established` → Connected with the
         * user-configured public URL. Everything else (transient connection retries) is
         * ignored — rathole backs off and retries on its own.
         */
        private suspend fun handleLine(
            line: String,
            publicUrl: String,
        ) {
            when {
                line.contains(MSG_AUTH_FAILED) -> {
                    if (_status.value !is TunnelStatus.Error) {
                        Log.e(TAG, "rathole authentication failed — check the service token")
                        _status.value =
                            TunnelStatus.Error("rathole authentication failed — check the service token")
                    }
                    mutex.withLock { teardownProcess() }
                }

                line.contains(MSG_CONNECTED) && _status.value is TunnelStatus.Connecting -> {
                    Log.i(TAG, "rathole tunnel connected")
                    _status.value =
                        TunnelStatus.Connected(
                            endpoints = listOf(TunnelEndpoint(url = publicUrl, valid = true)),
                            providerType = TunnelProviderType.RATHOLE,
                        )
                }
            }
        }

        override suspend fun stop() {
            mutex.withLock {
                teardownProcess()
                _status.value = TunnelStatus.Disconnected
            }
        }

        /**
         * Cancels reader/monitor jobs and tears down the process. Must be called while
         * holding [mutex]. No-op when no process is running.
         */
        private suspend fun teardownProcess() {
            val proc = process ?: return
            logReaderJob?.cancel()
            logReaderJob = null
            processMonitorJob?.cancel()
            processMonitorJob = null

            proc.destroy()
            withTimeoutOrNull(SHUTDOWN_TIMEOUT_MS) {
                @Suppress("BlockingMethodInNonBlockingContext")
                proc.waitFor()
            } ?: proc.destroyForcibly()

            process = null
        }

        private fun launchLogReader(
            proc: Process,
            onLine: suspend (String) -> Unit,
        ) {
            logReaderJob =
                scope.launch {
                    try {
                        BufferedReader(InputStreamReader(proc.errorStream)).use { reader ->
                            while (isActive) {
                                @Suppress("BlockingMethodInNonBlockingContext")
                                val line = reader.readLine() ?: break
                                Log.d(TAG, "rathole: $line")
                                onLine(line)
                            }
                        }
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        if (isActive) {
                            Log.w(TAG, "Error reading rathole logs", e)
                        }
                    }
                }
        }

        private fun startProcessMonitor(proc: Process) {
            processMonitorJob =
                scope.launch {
                    // Give the process a moment to start before monitoring exit
                    delay(PROCESS_MONITOR_INITIAL_DELAY_MS)

                    @Suppress("BlockingMethodInNonBlockingContext")
                    val exitCode = proc.waitFor()

                    if (isActive && _status.value !is TunnelStatus.Disconnected &&
                        _status.value !is TunnelStatus.Error
                    ) {
                        Log.w(TAG, "rathole process exited unexpectedly with code $exitCode")
                        _status.value =
                            TunnelStatus.Error("rathole process exited unexpectedly (code $exitCode)")
                        mutex.withLock { teardownProcess() }
                    }
                }
        }

        companion object {
            private const val TAG = "MCP:RatholeTunnel"

            /** rathole client log line emitted once the control channel is established. */
            internal const val MSG_CONNECTED = "Control channel established"

            /** rathole client log line emitted when the service token is rejected. */
            internal const val MSG_AUTH_FAILED = "Authentication failed"

            internal const val SHUTDOWN_TIMEOUT_MS = 5_000L
            private const val PROCESS_MONITOR_INITIAL_DELAY_MS = 1_000L

            internal const val SUPPORTED_ABI = "arm64-v8a"

            /** 44-char base64 of a 32-byte X25519 Noise public key. */
            internal val PUBLIC_KEY_REGEX = Regex("^[A-Za-z0-9+/]{43}=$")

            /** Pure ABI-membership check, separated from [Build] so it is unit-testable. */
            internal fun isAbiSupported(deviceAbis: Array<String>): Boolean =
                deviceAbis.any { it == SUPPORTED_ABI }

            internal fun isSupportedAbi(): Boolean = isAbiSupported(Build.SUPPORTED_ABIS)

            internal fun isValidPublicKey(publicKey: String): Boolean =
                PUBLIC_KEY_REGEX.matches(publicKey)

            /** True when the value cannot be embedded verbatim in a double-quoted TOML string. */
            internal fun containsUnsafeTomlChars(value: String): Boolean =
                value.any { it == '"' || it == '\\' || it.isISOControl() }

            /**
             * Renders the rathole client config (Noise transport; service name fixed to `mcp`
             * to match the expected server-side service). Values are embedded in double-quoted
             * TOML strings; [start] rejects values containing a quote, backslash, or control
             * character before rendering.
             */
            internal fun renderClientConfig(
                serverAddr: String,
                publicKey: String,
                token: String,
                localAddr: String,
            ): String =
                """
                [client]
                remote_addr = "$serverAddr"

                [client.transport]
                type = "noise"

                [client.transport.noise]
                remote_public_key = "$publicKey"

                [client.services.mcp]
                token = "$token"
                local_addr = "$localAddr"
                """.trimIndent()
        }
    }
```

**Action 2 — modify** `app/src/main/kotlin/.../di/AppModule.kt`: add the imports
`com.danielealbano.androidremotecontrolmcp.services.tunnel.AndroidRatholeBinaryResolver` and
`com.danielealbano.androidremotecontrolmcp.services.tunnel.RatholeBinaryResolver` (next to the
existing tunnel-resolver imports) and, immediately after `bindCloudflareBinaryResolver`, add:

```kotlin
    @Binds
    abstract fun bindRatholeBinaryResolver(impl: AndroidRatholeBinaryResolver): RatholeBinaryResolver
```

### Task 1.4 — Wire the provider into `TunnelManager`

**Action 1 — modify** `app/src/main/kotlin/.../services/tunnel/TunnelManager.kt`: in the
`@Inject constructor`, after `ngrokTunnelProviderFactory` add the parameter
`private val ratholeTunnelProviderFactory: Provider<RatholeTunnelProvider>,`; in `start()`, in
the `when (config.tunnelProvider)` add the branch
`TunnelProviderType.RATHOLE -> ratholeTunnelProviderFactory.get()`.

### Task 1.5 — Bundle the binary via Makefile and CI

**Action 1 — modify** `Makefile` (4 edits):

1. In the `.PHONY` list, replace `compile-cloudflared compile-ngrok-native check-so-alignment \`
   with `compile-cloudflared compile-ngrok-native download-rathole check-so-alignment \`.
2. Immediately before the line `NGROK_SRC_DIR := vendor/ngrok-java` insert the variables and the
   target (variables above their target):

```make
RATHOLE_VERSION := 0.5.0
RATHOLE_RELEASE := https://github.com/rathole-org/rathole/releases/download/v$(RATHOLE_VERSION)
RATHOLE_DIST_DIR := $(CURDIR)/.rathole-dist
RATHOLE_JNILIBS_DIR := app/src/main/jniLibs
RATHOLE_ARM64_ZIP := $(RATHOLE_DIST_DIR)/rathole-aarch64-unknown-linux-musl.zip
RATHOLE_ARM64_SHA256 := fa4a6fc63d86f8f1faa7c103a845e4715ce79a048455c0eec897b27237576564

download-rathole: ## Download pinned rathole v$(RATHOLE_VERSION) client (arm64-v8a, static musl)
	@echo "Downloading rathole v$(RATHOLE_VERSION) (aarch64-unknown-linux-musl)..."
	mkdir -p $(RATHOLE_DIST_DIR)
	curl -fsSL -o $(RATHOLE_ARM64_ZIP) $(RATHOLE_RELEASE)/rathole-aarch64-unknown-linux-musl.zip
	@actual=$$(openssl dgst -sha256 -r "$(RATHOLE_ARM64_ZIP)" | awk '{print $1}'); \
		if [ "$$actual" != "$(RATHOLE_ARM64_SHA256)" ]; then \
			echo "ERROR: rathole zip sha256 mismatch (got $$actual, want $(RATHOLE_ARM64_SHA256))"; exit 1; \
		fi
	rm -rf $(RATHOLE_DIST_DIR)/arm64
	mkdir -p $(RATHOLE_DIST_DIR)/arm64
	unzip -o -q $(RATHOLE_ARM64_ZIP) -d $(RATHOLE_DIST_DIR)/arm64
	mkdir -p $(RATHOLE_JNILIBS_DIR)/arm64-v8a
	install -m 0755 $(RATHOLE_DIST_DIR)/arm64/rathole $(RATHOLE_JNILIBS_DIR)/arm64-v8a/librathole.so
	@echo "rathole installed: $(RATHOLE_JNILIBS_DIR)/arm64-v8a/librathole.so"
```
(No `sha256sum` — it is absent on stock macOS; `openssl` is present on macOS and ubuntu runners.)

4. Add `download-rathole` to the prerequisites of `build`, `build-foss`, `build-release` and
   `build-release-bundle` (e.g. `build: compile-cloudflared compile-ngrok-native download-rathole`),
   and append to the `clean` target a line `rm -rf $(RATHOLE_DIST_DIR)` (its own temp dir).

**Action 2 — modify** `.gitignore`: add `.rathole-dist/` next to the existing
`app/src/main/jniLibs/` entry.

**Action 3 — modify** `.github/workflows/ci.yml` (TWO jobs — `test-e2e` AND `build-release`;
both assemble APKs that must package `librathole.so`, so BOTH get the same two edits):

1. In EACH job, after the `Cache ngrok-java Android native` step add:

```yaml
      - name: Cache rathole Android native
        id: cache-rathole
        uses: actions/cache@v6
        with:
          path: app/src/main/jniLibs/arm64-v8a/librathole.so
          key: rathole-android-${{ runner.os }}-${{ hashFiles('Makefile') }}
```

2. In EACH job, after the `Compile ngrok-java native for Android` step add:

```yaml
      - name: Download rathole for Android
        if: steps.cache-rathole.outputs.cache-hit != 'true'
        run: make download-rathole
```

**Definition of Done (Task 1.5):** `make download-rathole` succeeds and produces a verified
`librathole.so`; a sha256 mismatch aborts the target; `make build` depends on it.

### Task 1.6 — Unit tests for the provider core

**File (new):** `app/src/test/kotlin/.../services/tunnel/RatholeTunnelProviderTest.kt`

**Setup:** `mockBinaryResolver = mockk<RatholeBinaryResolver>()`;
`mockContext = mockk<Context> { every { filesDir } returns tmp }` where `tmp` is a JUnit 5
`@TempDir` field (`org.junit.jupiter.api.io.TempDir` — established repo pattern);
`provider = RatholeTunnelProvider(mockBinaryResolver, mockContext)`; in `@BeforeEach`
`mockkObject(RatholeTunnelProvider.Companion)` + `every { RatholeTunnelProvider.isSupportedAbi() } returns true`
(ngrok test pattern — `Build.SUPPORTED_ABIS` is null on JVM); `@AfterEach unmockkAll()`.
Fake binaries: executable shell scripts via `File.createTempFile` (CloudflareTunnelProviderTest
pattern), e.g. `#!/bin/sh\necho "Control channel established"\nsleep 60\n`; full rathole config for
status tests = `ServerConfig(ratholeServerAddr="mcp.example.com:2333", ratholeServerPublicKey=<44-char
base64, e.g. "A" + "B".repeat(42) + "=">, ratholeToken="t", ratholePublicUrl="https://mcp.example.com")`.
Status tests that involve the fake process use `runBlocking` + REAL `withTimeout` (the repo's
`CloudflareTunnelIntegrationTest` pattern — `runTest` virtual time cannot wait for a real child
process on `Dispatchers.IO`); pure (no-process) tests use `runTest`.

| Test | Verifies |
|------|----------|
| `initial status is Disconnected` | Default state |
| `start on unsupported ABI sets error with helpful message` | Stub `isSupportedAbi() returns false` → Error contains "not supported" and "Cloudflare" |
| `start with missing server address sets error` | `ratholeServerAddr=""` → Error message `rathole configuration is missing: server address` |
| `start with all fields missing lists all of them` | Default `ServerConfig()` → Error lists all four names |
| `start with invalid public key sets error` | 43-char key → Error contains "44-char base64 Noise key" |
| `start with unsafe characters in token sets error` | Token containing `"` (or backslash / newline) → Error contains "unsupported characters" |
| `start with missing binary sets error status` | `resolve() returns null` → Error `rathole binary not found` |
| `start when already running throws IllegalStateException` | `sleep 60` script; second `start` throws "Tunnel is already running"; `stop()` in cleanup |
| `connected line transitions to Connected with configured public URL` | Script prints `Control channel established` → `Connected`; `endpoints.single().url == ratholePublicUrl`, `valid == true`, `providerType == RATHOLE`; written `client.toml` in filesDir contains `remote_addr`, `type = "noise"`, `[client.services.mcp]` |
| `auth failed line sets Error and kills process` | Script prints `Authentication failed: mcp` → Error `rathole authentication failed — check the service token`; after `stop()`, a fresh `start()` does not throw (process guard cleared) |
| `stop when not running is no-op` | No throw; status stays Disconnected |
| `stop after connected returns to Disconnected` | Connected then `stop()` → Disconnected |
| `isAbiSupported returns true only for arm64-v8a` | Pure: `["arm64-v8a"]` true, `["x86_64"]`/`[]` false |
| `isValidPublicKey accepts 44-char base64 and rejects others` | Pure: 44-char with `=` true; 43-char, 45-char, empty false |
| `renderClientConfig emits noise transport, mcp service, and quoted values` | Pure: output contains `remote_addr = "mcp.example.com:2333"`, `type = "noise"`, `remote_public_key = "..."`, `[client.services.mcp]`, `token = "..."`, `local_addr = "127.0.0.1:8080"` |

**File (modify):** `app/src/test/kotlin/.../services/tunnel/TunnelManagerTest.kt` — add
`mockRatholeProvider = mockk<RatholeTunnelProvider>(relaxed = true)` + `ratholeFactory`
(`Provider<RatholeTunnelProvider>`) and pass `ratholeTunnelProviderFactory = ratholeFactory` in
`createManager()`; add tests:

| Test | Verifies |
|------|----------|
| `start with tunnel enabled and rathole provider starts rathole tunnel` | `tunnelProvider = RATHOLE` → `verify { ratholeProvider.start(localPort, config) }`, status relayed |
| `start with cloudflare provider does not start rathole tunnel` | `verify(exactly = 0) { ratholeProvider.start(any(), any()) }` |

**Definition of Done (US1):** all US1 tests pass; `make download-rathole` + `make lint` clean for
the touched files (full suite runs only after all stories, per repo workflow).

---

## User Story 2 — Persist and validate rathole settings

**Why:** every setting MUST flow through `SettingsRepository` (DataStore), with validation for
host:port / https-URL and no secret values in change logs (repo rule).

**Acceptance criteria:**
- [x] Four `update*` methods + four DataStore keys; values round-trip through `serverConfig`.
- [x] `validateRatholeServerAddr` accepts hostname/IPv4 `host:port` (port 1-65535), rejects
  missing port, bad port, whitespace.
- [x] `validateRatholePublicUrl` accepts `https://host`, rejects http, port, path, query, userinfo.
- [x] Token/public-key updates log "changed" WITHOUT the value; addr/public-url log old → new.

### Task 2.1 — Repository interface and implementation

**Action 1 — modify** `app/src/main/kotlin/.../data/repository/SettingsRepository.kt`:
after `validateCertificateHostname` add the two validator declarations, and after
`updateCloudflareTunnelExtraArgs` add the four update declarations:

```kotlin
    /**
     * Validates a rathole server address (`host:port`).
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated address, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateRatholeServerAddr(addr: String): Result<String>

    /**
     * Validates a rathole public URL (must be an `https://` URL with no port, path,
     * query, or fragment).
     *
     * This is a pure validation function with no I/O; it is intentionally
     * non-suspending so callers are not forced into a coroutine context.
     *
     * @return [Result.success] with the validated URL, or [Result.failure] with an [IllegalArgumentException].
     */
    fun validateRatholePublicUrl(url: String): Result<String>

    /** Updates the rathole server address (`host:port`). */
    suspend fun updateRatholeServerAddr(addr: String)

    /** Updates the rathole server Noise public key (base64). */
    suspend fun updateRatholeServerPublicKey(publicKey: String)

    /** Updates the rathole service token. */
    suspend fun updateRatholeToken(token: String)

    /** Updates the rathole public https:// URL. */
    suspend fun updateRatholePublicUrl(url: String)
```

**Action 2 — modify** `app/src/main/kotlin/.../data/repository/SettingsRepositoryImpl.kt` (5 edits):

1. Add import `java.net.URI` (file already imports `java.net.URL`).
2. After `CLOUDFLARE_TUNNEL_EXTRA_ARGS_KEY` add the four keys:

```kotlin
private val RATHOLE_SERVER_ADDR_KEY = stringPreferencesKey("rathole_server_addr")
private val RATHOLE_SERVER_PUBLIC_KEY_KEY = stringPreferencesKey("rathole_server_public_key")
private val RATHOLE_TOKEN_KEY = stringPreferencesKey("rathole_token")
private val RATHOLE_PUBLIC_URL_KEY = stringPreferencesKey("rathole_public_url")
```

3. After the file-private `HOSTNAME_PATTERN` val add:

```kotlin
/** True for a dotted-quad IPv4 address: 4 ASCII-digit groups, no leading zeros, each octet 0-255. */
private fun isValidIpv4(host: String): Boolean {
    val parts = host.split(".")
    return parts.size == 4 &&
        parts.all { p ->
            p.length in 1..3 &&
                p.all { it in '0'..'9' } &&
                (p == "0" || p.first() != '0') &&
                p.toInt() <= 255
        }
}
```

4. In `mapPreferencesToServerConfig`, after the `cloudflareTunnelExtraArgs = ...` line add:

```kotlin
        ratholeServerAddr = prefs[RATHOLE_SERVER_ADDR_KEY] ?: "",
        ratholeServerPublicKey = prefs[RATHOLE_SERVER_PUBLIC_KEY_KEY] ?: "",
        ratholeToken = prefs[RATHOLE_TOKEN_KEY] ?: "",
        ratholePublicUrl = prefs[RATHOLE_PUBLIC_URL_KEY] ?: "",
```

5. Immediately after the `override fun validateCertificateHostname` implementation add the two
   validators, and after `override suspend fun updateCloudflareTunnelExtraArgs` add the four
   updates:

```kotlin
        override fun validateRatholeServerAddr(addr: String): Result<String> {
            val sep = addr.lastIndexOf(':')
            val host = if (sep > 0) addr.substring(0, sep) else ""
            val port = if (sep in 1 until addr.length) addr.substring(sep + 1) else ""
            val portValid = port.toIntOrNull()?.let { it in ServerConfig.MIN_PORT..ServerConfig.MAX_PORT } == true
            val hostValid = HOSTNAME_PATTERN.matches(host) || isValidIpv4(host)
            return if (hostValid && portValid) {
                Result.success(addr)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "rathole server address must be host:port (hostname or IPv4, port 1-65535)",
                    ),
                )
            }
        }

        override fun validateRatholePublicUrl(url: String): Result<String> {
            val uri = runCatching { URI(url.trim()) }.getOrNull()
            val valid =
                uri != null &&
                    uri.scheme?.lowercase() == "https" &&
                    uri.host != null &&
                    uri.port == -1 &&
                    (uri.path.isNullOrEmpty() || uri.path == "/") &&
                    uri.userInfo == null &&
                    uri.query == null &&
                    uri.fragment == null
            return if (valid) {
                Result.success(url)
            } else {
                Result.failure(
                    IllegalArgumentException(
                        "rathole public url must be an https:// URL without port, path, or query " +
                            "(e.g. https://mcp.example.com)",
                    ),
                )
            }
        }
```

```kotlin
        override suspend fun updateRatholeServerAddr(addr: String) =
            logScalarChange(RATHOLE_SERVER_ADDR_KEY, "rathole_server_addr", addr, "") { o, n ->
                "rathole server address changed $o → $n"
            }

        override suspend fun updateRatholeServerPublicKey(publicKey: String) =
            logScalarChange(
                RATHOLE_SERVER_PUBLIC_KEY_KEY,
                "rathole_server_public_key",
                publicKey,
                "",
            ) { _, _ ->
                "rathole server public key changed"
            }

        override suspend fun updateRatholeToken(token: String) =
            logScalarChange(RATHOLE_TOKEN_KEY, "rathole_token", token, "") { _, _ -> "rathole token changed" }

        override suspend fun updateRatholePublicUrl(url: String) =
            logScalarChange(RATHOLE_PUBLIC_URL_KEY, "rathole_public_url", url, "") { o, n ->
                "rathole public url changed $o → $n"
            }
```

### Task 2.2 — Repository tests

**File (modify):** `app/src/test/kotlin/.../data/repository/SettingsRepositoryImplTest.kt`
(same repo fixture/pattern as the existing `UpdateTunnelProvider` nested class).

| Test | Verifies |
|------|----------|
| `default rathole settings are empty` | Fresh repo: all four config fields `""` |
| `updates rathole server addr and round-trips` | `updateRatholeServerAddr("mcp.example.com:2333")` → config returns it |
| `updates rathole public key, token, public url and round-trip` | Each of the three updates persists and returns via `serverConfig` |
| `validateRatholeServerAddr accepts hostname with port` | `mcp.example.com:2333` success |
| `validateRatholeServerAddr accepts IPv4 with port` | `62.233.43.72:2333` success |
| `validateRatholeServerAddr rejects missing port / bad port / bad host` | `"host"`, `host:0`, `host:70000`, `host :2333`, `256.1.1.1:2333` all failure |
| `validateRatholePublicUrl accepts https host` | `https://mcp.example.com` success |
| `validateRatholePublicUrl rejects http, port, path, query, userinfo` | `http://…`, `https://h:8443`, `https://h/mcp`, `https://h?x=1`, `https://u@h/` all failure |
| `rathole token and public key change log does not contain the value` | After a token update, advance the test dispatcher past the 2000 ms `COALESCE_WINDOW_MS` so the logger flushes, then assert the flushed entry's rendered text contains no token text (write fresh — there is NO pre-existing secret-log test to mirror; see `SettingsChangeLoggerTest` time-advance pattern) |

**Definition of Done (US2):** all US2 tests pass; no secret value appears in any log line.

---

## User Story 3 — rathole UI and headless (ADB) configuration

**Why:** the user selects the provider in Settings → Tunnel and enters the four values (no other
UI surface); headless setups configure the same values via the ADB broadcast.

**Acceptance criteria:**
- [x] Provider radio "Self-hosted (rathole) / Your own VPS, Noise-encrypted" appears; selecting it
  reveals four fields (Server Address, Server Public Key, Service Token with show/hide, Public URL).
- [x] Inputs sync from `serverConfig` (ADB/external changes reflected) and persist on change.
- [x] ADB broadcast applies the four extras; invalid addr/url are rejected with a warning log;
  empty token/public key are ignored.

### Task 3.1 — `MainViewModel` inputs and updates

**Action 1 — modify** `app/src/main/kotlin/.../ui/viewmodels/MainViewModel.kt` (3 edits):

1. After the `_cloudflareExtraArgsInput`/`cloudflareExtraArgsInput` pair add:

```kotlin
        private val _ratholeServerAddrInput = MutableStateFlow("")
        val ratholeServerAddrInput: StateFlow<String> = _ratholeServerAddrInput.asStateFlow()

        private val _ratholeServerPublicKeyInput = MutableStateFlow("")
        val ratholeServerPublicKeyInput: StateFlow<String> = _ratholeServerPublicKeyInput.asStateFlow()

        private val _ratholeTokenInput = MutableStateFlow("")
        val ratholeTokenInput: StateFlow<String> = _ratholeTokenInput.asStateFlow()

        private val _ratholePublicUrlInput = MutableStateFlow("")
        val ratholePublicUrlInput: StateFlow<String> = _ratholePublicUrlInput.asStateFlow()
```

2. In the `settingsRepository.serverConfig.collect` block, after
   `_cloudflareExtraArgsInput.value = config.cloudflareTunnelExtraArgs` add:

```kotlin
                    _ratholeServerAddrInput.value = config.ratholeServerAddr
                    _ratholeServerPublicKeyInput.value = config.ratholeServerPublicKey
                    _ratholeTokenInput.value = config.ratholeToken
                    _ratholePublicUrlInput.value = config.ratholePublicUrl
```

3. After `updateCloudflareTunnelExtraArgs` add (exact ngrok update-function pattern):

```kotlin
        fun updateRatholeServerAddr(addr: String) {
            _ratholeServerAddrInput.value = addr
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateRatholeServerAddr(addr)
            }
        }

        fun updateRatholeServerPublicKey(publicKey: String) {
            _ratholeServerPublicKeyInput.value = publicKey
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateRatholeServerPublicKey(publicKey)
            }
        }

        fun updateRatholeToken(token: String) {
            _ratholeTokenInput.value = token
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateRatholeToken(token)
            }
        }

        fun updateRatholePublicUrl(url: String) {
            _ratholePublicUrlInput.value = url
            viewModelScope.launch(ioDispatcher) {
                settingsRepository.updateRatholePublicUrl(url)
            }
        }
```

### Task 3.2 — `TunnelSettingsScreen` fields and strings

**Action 1 — modify** `app/src/main/kotlin/.../ui/screens/settings/TunnelSettingsScreen.kt` (4 edits):

1. After the `cloudflareExtraArgsInput` collect line add:

```kotlin
    val ratholeServerAddrInput by viewModel.ratholeServerAddrInput.collectAsStateWithLifecycle()
    val ratholeServerPublicKeyInput by viewModel.ratholeServerPublicKeyInput.collectAsStateWithLifecycle()
    val ratholeTokenInput by viewModel.ratholeTokenInput.collectAsStateWithLifecycle()
    val ratholePublicUrlInput by viewModel.ratholePublicUrlInput.collectAsStateWithLifecycle()
```

2. In BOTH provider `when` blocks (label and description) add the missing branch (compiler-enforced):

```kotlin
                                             TunnelProviderType.RATHOLE -> {
                                                 stringResource(R.string.remote_access_provider_rathole)
                                             }
```

   (and `R.string.remote_access_provider_rathole_desc` in the description block).

3. After the ngrok `AnimatedVisibility` block add:

```kotlin
                    // rathole-specific fields
                    AnimatedVisibility(
                        visible = serverConfig.tunnelProvider == TunnelProviderType.RATHOLE,
                    ) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            RatholeConfigFields(
                                serverAddr = ratholeServerAddrInput,
                                publicKey = ratholeServerPublicKeyInput,
                                token = ratholeTokenInput,
                                publicUrl = ratholePublicUrlInput,
                                enabled = sectionEnabled,
                                onServerAddrChange = viewModel::updateRatholeServerAddr,
                                onPublicKeyChange = viewModel::updateRatholeServerPublicKey,
                                onTokenChange = viewModel::updateRatholeToken,
                                onPublicUrlChange = viewModel::updateRatholePublicUrl,
                            )
                        }
                    }
```

4. After the `NgrokConfigFields` composable add (field layout mirrors `NgrokConfigFields`;
   token field uses the `config_token_show`/`config_token_hide` content descriptions like
   `CloudflareTokenFields`):

```kotlin
@Composable
private fun RatholeConfigFields(
    serverAddr: String,
    publicKey: String,
    token: String,
    publicUrl: String,
    enabled: Boolean,
    onServerAddrChange: (String) -> Unit,
    onPublicKeyChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onPublicUrlChange: (String) -> Unit,
) {
    var showToken by remember { mutableStateOf(false) }

    Column {
        Text(
            text = stringResource(R.string.remote_access_rathole_server_addr_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = serverAddr,
            onValueChange = onServerAddrChange,
            singleLine = true,
            enabled = enabled,
            placeholder = {
                Text(text = stringResource(R.string.remote_access_rathole_server_addr_hint))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_access_rathole_server_addr_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.remote_access_rathole_public_key_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = publicKey,
            onValueChange = onPublicKeyChange,
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_access_rathole_public_key_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.remote_access_rathole_token_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = token,
            onValueChange = onTokenChange,
            singleLine = true,
            enabled = enabled,
            visualTransformation =
                if (showToken) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(
                        imageVector =
                            if (showToken) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                        contentDescription =
                            if (showToken) {
                                stringResource(R.string.config_token_hide)
                            } else {
                                stringResource(R.string.config_token_show)
                            },
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.remote_access_rathole_public_url_label),
            style = MaterialTheme.typography.labelLarge,
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = publicUrl,
            onValueChange = onPublicUrlChange,
            singleLine = true,
            enabled = enabled,
            placeholder = {
                Text(text = stringResource(R.string.remote_access_rathole_public_url_hint))
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.remote_access_rathole_public_url_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

**Action 2 — modify** `app/src/main/res/values/strings.xml`: after
`<string name="remote_access_cloudflare_service_url_copy">Copy service URL</string>` insert
(the two `remote_access_provider_rathole*` strings were already added in US1, see
post-review fix):

```xml
    <string name="remote_access_rathole_server_addr_label">Server Address</string>
    <string name="remote_access_rathole_server_addr_hint">mcp.example.com:2333</string>
    <string name="remote_access_rathole_server_addr_help">Hostname or IPv4 with port (IPv6 not supported)</string>
    <string name="remote_access_rathole_public_key_label">Server Public Key</string>
    <string name="remote_access_rathole_public_key_help">Base64 Noise public key from `rathole --genkey` on the server (a wrong key leaves the tunnel in Connecting)</string>
    <string name="remote_access_rathole_token_label">Service Token</string>
    <string name="remote_access_rathole_public_url_label">Public URL</string>
    <string name="remote_access_rathole_public_url_hint">https://mcp.example.com</string>
    <string name="remote_access_rathole_public_url_help">The public https:// URL that fronts this tunnel (e.g. behind your Caddy/nginx with TLS)</string>
```

### Task 3.3 — ADB headless extras

**Action 1 — modify** `app/src/main/kotlin/.../services/mcp/AdbConfigHandler.kt` (3 edits):

1. In the companion object, after `EXTRA_NGROK_DOMAIN` add:

```kotlin
        internal const val EXTRA_RATHOLE_SERVER_ADDR = "rathole_server_addr"
        internal const val EXTRA_RATHOLE_SERVER_PUBLIC_KEY = "rathole_server_public_key"
        internal const val EXTRA_RATHOLE_TOKEN = "rathole_token"
        internal const val EXTRA_RATHOLE_PUBLIC_URL = "rathole_public_url"
```

2. In the ACTION_CONFIGURE dispatch, after `applyNgrokDomain(intent)` add:

```kotlin
        applyRatholeServerAddr(intent)
        applyRatholeServerPublicKey(intent)
        applyRatholeToken(intent)
        applyRatholePublicUrl(intent)
```

3. After the `applyNgrokDomain` function add:

```kotlin
    private suspend fun applyRatholeServerAddr(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_RATHOLE_SERVER_ADDR) ?: return
        settingsRepository.validateRatholeServerAddr(value).fold(
            onSuccess = {
                settingsRepository.updateRatholeServerAddr(it)
                Log.i(TAG, "rathole server address updated to $it")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid rathole_server_addr '$value': ${it.message}") },
        )
    }

    private suspend fun applyRatholeServerPublicKey(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_RATHOLE_SERVER_PUBLIC_KEY) ?: return
        if (value.isEmpty()) {
            Log.w(TAG, "Ignoring empty rathole_server_public_key")
            return
        }
        settingsRepository.updateRatholeServerPublicKey(value)
        Log.i(TAG, "rathole server public key updated (length=${value.length})")
    }

    private suspend fun applyRatholeToken(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_RATHOLE_TOKEN) ?: return
        if (value.isEmpty()) {
            Log.w(TAG, "Ignoring empty rathole_token")
            return
        }
        settingsRepository.updateRatholeToken(value)
        Log.i(TAG, "rathole token updated (length=${value.length})")
    }

    private suspend fun applyRatholePublicUrl(intent: Intent) {
        val value = intent.getStringExtra(EXTRA_RATHOLE_PUBLIC_URL) ?: return
        settingsRepository.validateRatholePublicUrl(value).fold(
            onSuccess = {
                settingsRepository.updateRatholePublicUrl(it)
                Log.i(TAG, "rathole public url updated to $it")
            },
            onFailure = { Log.w(TAG, "Ignoring invalid rathole_public_url '$value': ${it.message}") },
        )
    }
```

### Task 3.4 — UI/headless tests

**File (modify):** `app/src/test/kotlin/.../ui/viewmodels/MainViewModelTest.kt`
(mirror the `updateNgrokAuthtoken calls repository and updates input state` pattern):

| Test | Verifies |
|------|----------|
| `updateRatholeServerAddr calls repository and updates input state` | `updateRatholeServerAddr("h:2333")` → input flow value + `coVerify` repo call |
| `updateRatholeServerPublicKey calls repository and updates input state` | Same for public key |
| `updateRatholeToken calls repository and updates input state` | Same for token |
| `updateRatholePublicUrl calls repository and updates input state` | Same for public URL |
| `rathole inputs sync from config` | Config with rathole fields collected → all four input flows hold the values |

**File (modify):** `app/src/test/kotlin/.../services/mcp/AdbConfigHandlerTest.kt`
(in `ConfigureTunnelTests`, mirror `tunnelProviderNgrok` / `cloudflareTunnelModeFree`):

| Test | Verifies |
|------|----------|
| `rathole_server_addr is applied` | Valid extra → `coVerify { settingsRepository.updateRatholeServerAddr("mcp.example.com:2333") }` |
| `invalid rathole_server_addr is rejected` | `--es rathole_server_addr "bad"` → `coVerify(exactly = 0) { updateRatholeServerAddr(any()) }` |
| `rathole_server_public_key is applied` | Non-empty → `coVerify` update |
| `empty rathole_server_public_key is ignored` | `coVerify(exactly = 0)` |
| `rathole_token is applied` | Non-empty → `coVerify` update |
| `empty rathole_token is ignored` | `coVerify(exactly = 0)` |
| `rathole_public_url is applied` | Valid → `coVerify` update |
| `invalid rathole_public_url is rejected` | `http://…` → `coVerify(exactly = 0)` |

**Definition of Done (US3):** all US3 tests pass; exhaustive `when` on `TunnelProviderType`
compiles (no missed UI branch).

---

## User Story 4 — End-to-end (JVM) verification, CI, and docs

**Why:** prove the bundled approach works against a REAL rathole server (Noise handshake, token
auth, data path) on loopback, keep CI green, and document the new provider + VPS-side setup.

**Acceptance criteria:**
- [x] JVM integration test: real host `rathole` server + provider client process → `Connected`
  with the configured public URL + proxied HTTP round-trip through the tunnel; wrong token →
  `Error` (auth). Hard-FAIL when the host binary is missing (CI always installs it — same
  contract as `CloudflareTunnelIntegrationTest`).
- [x] CI test-unit job installs the pinned host x86_64 binary.
- [x] README + PROJECT.md document the third provider, the four settings/ADB extras, and the
  VPS-side rathole server setup.

### Task 4.1 — `RatholeTunnelIntegrationTest`

**File (new):** `app/src/test/kotlin/.../integration/RatholeTunnelIntegrationTest.kt`

**Setup (shared infrastructure — full, per plan rules):**
- `HostRatholeBinaryResolver` (file-private class, `which rathole` via ProcessBuilder, same shape
  as `HostCloudflareBinaryResolver` in `CloudflareTunnelIntegrationTest`).
- `@BeforeEach`: resolve binary or `fail("rathole binary not found on host. Download the pinned
  v0.5.0 x86_64 asset from https://github.com/rathole-org/rathole/releases and put `rathole` on
  PATH.")`.
- Per test: `@TempDir` dir; three free ports via `ServerSocket(0)` (channel `portA`, service
  `portB`, local backend `portC`); raw-socket HTTP test server on `portC` answering a fixed body
  `OK rathole integration test` (pattern of `CloudflareTunnelIntegrationTest.handleTestRequest`);
  keypair from `rathole --genkey` (spawn the binary; its stdout is
  `Private Key:` / `<base64>` / `Public Key:` / `<base64>` — label and value on SEPARATE lines,
  capitalized; take the first non-empty line after each label, trimmed); server config in `@TempDir`:

```toml
[server]
bind_addr = "127.0.0.1:<portA>"

[server.transport]
type = "noise"

[server.transport.noise]
local_private_key = "<priv>"

[server.services.mcp]
token = "test-token"
bind_addr = "127.0.0.1:<portB>"
```

- Start the rathole **server** with `ProcessBuilder(binary, "<server.toml>")`; the provider is
  constructed with `mockk<Context> { every { filesDir } returns tempDir }` and a binary resolver
  returning the host binary; in `@BeforeEach` `mockkObject(RatholeTunnelProvider.Companion)` +
  `every { RatholeTunnelProvider.isSupportedAbi() } returns true` and `@AfterEach unmockkAll()`
  (`Build.SUPPORTED_ABIS` is null on JVM — without this the provider NPEs in `start()`);
  `@AfterEach`: `provider.stop()`, destroy the server process, close sockets/threads.
- `ServerConfig` for the provider: `ratholeServerAddr = "127.0.0.1:$portA"`,
  `ratholeServerPublicKey = <pub>`, `ratholeToken = "test-token"`,
  `ratholePublicUrl = "https://mcp.test.local"`.
- Class-level `@Timeout(120s)`; connect waits `withTimeout(30s)`.

| Test | Verifies |
|------|----------|
| `tunnel connects and proxies traffic to local test server` | `Connected` with `endpoints.single().url == "https://mcp.test.local"`, `providerType == RATHOLE`; HTTP GET `http://127.0.0.1:<portB>` returns the fixed body (full chain: provider client → Noise → rathole server → local backend); `stop()` → Disconnected |
| `wrong token sets auth error and kills the client` | `ratholeToken = "wrong"` → within 30s status `Error` containing `authentication failed`; a follow-up `start()` does not throw |
| `client without server stays Connecting (transient failures are not errors)` | No rathole server started → after ~5s status is still `Connecting` (not `Error`); `stop()` → Disconnected |

### Task 4.2 — CI test-unit job: install the host rathole binary

**Action 1 — modify** `.github/workflows/ci.yml` (test-unit job): after the `Install cloudflared`
step add:

```yaml
      - name: Install rathole (host, for JVM integration tests)
        run: |
          curl -fsSL https://github.com/rathole-org/rathole/releases/download/v0.5.0/rathole-x86_64-unknown-linux-gnu.zip -o /tmp/rathole.zip
          echo "3e7d0d0f365120cd3cd351d147d1a12ee960c8068b464d4dd533a3821873b80e  /tmp/rathole.zip" | sha256sum -c -
          unzip -o -q /tmp/rathole.zip -d /tmp/rathole
          install -m 0755 /tmp/rathole/rathole /usr/local/bin/rathole
          rathole --version
```

### Task 4.3 — Documentation

**Action 1 — modify** `README.md` (5 edits):

1. Line 60 bullet: `Remote access tunnels via Cloudflare Quick Tunnels or ngrok (public HTTPS URL)`
   → `Remote access tunnels via Cloudflare Quick Tunnels, ngrok, or a self-hosted rathole server (public HTTPS URL)`.
2. Line 76 bullet: `Remote access tunnel configuration (Cloudflare / ngrok)`
   → `Remote access tunnel configuration (Cloudflare / ngrok / rathole)`.
3. Line 339 table row: `(Cloudflare Quick Tunnels or ngrok)` → `(Cloudflare Quick Tunnels, ngrok, or self-hosted rathole)`.
4. In the numbered provider list (lines 374-375) add item 3 and a short setup subsection right
   after the list:

```markdown
3. **Self-hosted rathole** (your own VPS): Noise-encrypted tunnel to your own rathole server
   with a fixed public URL behind your own TLS reverse proxy. Requires a one-time VPS setup
   (below). Available on arm64-v8a devices only.

<details>
<summary><b>Self-hosted (rathole) — one-time VPS setup</b></summary>

1. Install `rathole` (v0.5.0 or newer) on your VPS and generate a key pair: `rathole --genkey`
   (write the output — you need the **public key** on the phone and the **private key** in the
   server config).
2. Create `/etc/rathole/rathole-server.toml`:

   ```toml
   [server]
   bind_addr = "0.0.0.0:2333"

   [server.transport]
   type = "noise"

   [server.transport.noise]
   local_private_key = "<PRIVATE_KEY_FROM_GENKEY>"

   [server.services.mcp]
   token = "<LONG_RANDOM_TOKEN>"
   bind_addr = "127.0.0.1:8081"
   ```

   Run it as a systemd service. Open port `2333` (tcp) in the firewall.
3. Put a TLS reverse proxy (Caddy/nginx) in front of `127.0.0.1:8081` on a hostname
   (e.g. `mcp.example.com`) — the app publishes this URL, so it must be reachable over HTTPS.
4. In the app: **Settings → Tunnel → Self-hosted (rathole)** and enter Server Address
   (`<vps-ip-or-host>:2333`), Server Public Key, Service Token, and Public URL
   (`https://mcp.example.com`).
</details>
```

5. ADB section: in the `adb broadcast` example (after the `ngrok_domain` line) add
   `--es rathole_server_addr "mcp.example.com:2333" \`, `--es rathole_server_public_key
   "BASE64_NOISE_PUBLIC_KEY" \`, `--es rathole_token "your-rathole-token" \`, `--es
   rathole_public_url "https://mcp.example.com" \`; in the extras table change the
   `tunnel_provider` row to `` `CLOUDFLARE`, `NGROK`, or `RATHOLE` `` and add four rows:
   `rathole_server_addr` (string, rathole server `host:port`), `rathole_server_public_key`
   (string, base64 Noise public key from `rathole --genkey`), `rathole_token` (string, service
   token matching the server config), `rathole_public_url` (string, public https:// URL).

**Action 2 — modify** `docs/PROJECT.md` (3 edits):

1. In the `services/tunnel/` file list (line 138) append: `RatholeTunnelProvider.kt`,
   `RatholeBinaryResolver.kt`, `AndroidRatholeBinaryResolver.kt`.
2. Line 633: `and **ngrok** (account required, optional custom domain)` → `and **ngrok**
   (account required, optional custom domain), plus a **self-hosted rathole** server
   (user's own VPS, Noise transport, fixed public URL)`.
3. After the ngrok bullet in the Remote Access / Tunnel section (line 643) add:

```markdown
- **Self-hosted rathole**: Runs the `rathole` client binary as a child process with Noise transport against the user's own VPS rathole server (service name `mcp`). The public URL is user-configured (the client cannot discover it). arm64-v8a devices only. The binary is the pinned v0.5.0 static `aarch64-unknown-linux-musl` release asset (sha256-pinned, downloaded — not compiled — by `make download-rathole`) packaged as `librathole.so`; the host `x86_64` asset is installed in CI for the JVM integration test.
```

   and in the defaults list (after the `ngrok Domain` line) add the four new defaults:
   `rathole Server Address` / `rathole Server Public Key` / `rathole Token` / `rathole Public
   URL` — all Empty (required when using rathole).

**Definition of Done (US4):** integration test passes locally (host `rathole` on PATH) and in
CI; README/PROJECT.md consistent with the implementation.

---

## Final quality gates (after ALL user stories)

- [ ] `make lint` (ktlint + detekt) — zero warnings/errors.
- [x] `make build` succeeds with no warnings (packages `librathole.so`).
- [ ] `make test-unit` (includes `RatholeTunnelIntegrationTest` with host binary installed) — all green.
- [x] No TODOs / dead code; no lint suppressions added beyond the two
  `@Suppress("TooGenericExceptionCaught")` / `@Suppress("BlockingMethodInNonBlockingContext")`
  patterns already used by `CloudflareTunnelProvider`.
- [x] `git diff main..HEAD` touches ONLY the scope-boundary files + the plan document.
- [ ] code-reviewer subagent (plan-compliance mode) run over the full implementation; all
  findings fixed; PR created per TOOLS.md conventions.

**Known limitations (documented, not defects):**
- rathole v0.5.0 is the latest release (2023-10-01) and is pinned; upgrading requires a new
  sha256 pin + re-verification of log markers.
- arm64-v8a only (no x86_64 Android binary exists upstream) — other ABIs get a graceful error.
- `ratholeServerAddr` accepts hostname or IPv4 only (no IPv6; IDN hostnames must be typed as
  punycode).
- The static musl binary resolves DNS via `/etc/resolv.conf`, which stock Android does not
  provide; if hostname resolution fails on-device, connect via IP (see US1 Manual QA Steps).
- The provider does not verify reachability of `ratholePublicUrl` (no network check in the
  provider, consistent with the cloudflared TOKEN-mode advisory model); the user verifies via the
  published URL.

---

## Review findings (plan-reviewer, resolved before implementation)

Single plan-reviewer pass against the full plan; the reviewer empirically ran the pinned v0.5.0
host binary (loopback server+client round-trip, wrong-token, no-server) to verify every rathole
claim. ALL findings addressed in the plan text above:

- **C1 (CRITICAL, fixed):** rathole logs to **stdout**, not stderr — provider now uses
  `redirectErrorStream(true)`, reader renamed `launchLogReader`, wording corrected.
- **C2 (CRITICAL, fixed):** server Noise key field is `local_private_key` (v0.5.0 rejects
  `server_private_key`) — fixed in the integration-test TOML and the README VPS setup.
- **C3 (CRITICAL, fixed):** `rathole --genkey` prints label and key on SEPARATE lines
  (`Private Key:` / `<base64>`) — parsing instruction corrected.
- **C4 (CRITICAL, fixed):** `RatholeBinaryResolver` interface needs a Hilt `@Binds` —
  `AppModule.kt` added to the scope boundary; Task 1.3 Action 2 added.
- **C5 (CRITICAL, fixed):** integration-test setup now mocks `RatholeTunnelProvider.Companion`
  (`isSupportedAbi() returns true`; `Build.SUPPORTED_ABIS` is null on JVM).
- **W1 (WARNING, fixed):** CI `build-release` job also gets the rathole cache+download steps
  (APK artifacts must not silently miss `librathole.so`).
- **W2 (WARNING, fixed):** Makefile verification uses `openssl dgst -sha256 -r` (no `sha256sum`
  on stock macOS).
- **W3 (WARNING, fixed):** KDoc claim corrected; `start()` rejects quote/backslash/control
  characters in all four fields (`containsUnsafeTomlChars`) + unit test.
- **W4 (WARNING, fixed by documentation):** IPv6 `remote_addr` not supported — UI help text
  "Hostname or IPv4 with port (IPv6 not supported)" + known limitation.
- **W5 (WARNING, fixed):** process-driven unit tests use `runBlocking` + real `withTimeout`
  (not `runTest` virtual time).
- **W6 (WARNING, fixed by documentation + Manual QA):** musl DNS-on-Android risk — US1 Manual QA
  Steps (IP and hostname on a real device) + known limitation + UI hint.
- **I2 (INFO, fixed):** Makefile variables now above their target (single insertion point).
- **I3 (INFO, fixed):** `isValidIpv4` tightened (ASCII digits only, no leading zeros).
- **I4 (INFO, fixed by documentation):** IDN/punycode noted in known limitations.
- **I5 (INFO, fixed):** secret-log test description corrected (no pre-existing mirror; virtual
  time must advance past the 2000 ms coalesce window).
- **I8 (INFO, fixed):** public-key help text hints that a wrong key leaves the tunnel in
  Connecting.
- I1/I6/I7/I9: verified correct, no action needed.

**Post-review implementation fix (make escaping):** the Makefile snippet's `awk '{print $1}'`
must be `awk '{print $$1}'` — in a make recipe a single `$1` is a (undefined) make variable,
so the hash check compared the whole `hash *filename` line. Applied in the Makefile; the plan
snippet above shows the pre-fix form.

**Post-review implementation fix (compile-atomicity, user-approved):** Task 1.1 also ships the
two `when (provider)` `RATHOLE` label/desc branches in `TunnelSettingsScreen.kt` plus the
`remote_access_provider_rathole` / `_desc` strings — otherwise the US1 commit does not compile
(exhaustive `when` over the enum) until US3. The remaining Task 3.2 work (field collection,
AnimatedVisibility block, `RatholeConfigFields`, other strings) stays in US3.

**Post-review implementation fix (test placement):** the Task 2.2 secret-log test lives in
`SettingsRepositoryLoggingTest` (the only fixture exposing the `RecordingServerLogRepository`
instance) instead of `SettingsRepositoryImplTest`; all other Task 2.2 tests are in
`SettingsRepositoryImplTest` as planned.





## Post-implementation review (code-reviewer, plan-compliance mode)

Single code-reviewer pass over the full implementation (`main..HEAD`, 8 commits). Plan compliance:
25 actions — 23 exact, 2 behavior-preserving restructurings (W1/W2 below), 0 missing, 0 extra;
plan-file protection OK; no out-of-scope committed changes. Findings and resolutions:

- **C1 (CRITICAL, fixed):** ktlint (`standard:function-signature`, limit 140) and detekt
  (`MaxLineLength`, limit 120) conflict on `containsUnsafeTomlChars` — the joined one-liner is
  133 chars (ktlint-mandated, detekt-rejected); the wrapped form ktlint rejects. Fixed by
  extracting companion `isUnsafeTomlChar(c: Char)` so the ktlint-preferred one-liner is 108
  chars and passes both tools.
- **W1 (WARNING, resolved by this record):** Task 2.1 Action 2 deviation — the four rathole
  updates + two validators are implemented as one-line delegations to a file-private
  `RatholeSettings(dataStore, settingsChangeLogger)` class, and the pre-existing private
  helpers `logToggle` / `editPrivacyConfig` were moved to file-private top-level. Cause: detekt
  `LargeClass` (limit 600) — the class was exactly 600 on main; US2 pushed it over. Behavior
  verified identical: same 4 DataStore keys, same log messages, same coalesce keys
  (`Preferences.Key.name` ≡ the literal strings).
- **W2 (WARNING, resolved by this record):** Task 1.3 Action 1 deviation — the inline
  preflight checks of `start()` (ABI → missing → unsafe → public key → binary) were extracted
  into `preflight()` / `abiErrorOrNull()` / `Companion.validateRatholeConfigFields()`. Cause:
  detekt `LongMethod` (73 > 60) + `ReturnCount` (5 > 2). Check order and all five error strings
  are verbatim-identical; `binaryResolver.resolve()` still runs only after the field checks.
- **I1 (INFO, recorded):** `RatholeTunnelProviderTest` uses explicit
  `(status as TunnelStatus.Error)` casts — Kotlin smart casts do not propagate out of
  `assertTrue`/`first { }` predicate arguments.
- **I2 (INFO, recorded):** `validateServerAddr` strengthened beyond the plan snippet with a
  strict dotted-quad IPv4 path — the snippet's `HOSTNAME_PATTERN.matches(host) || isValidIpv4(host)`
  would accept `256.1.1.1` (all-numeric labels are legal hostname labels), contradicting the
  plan's own test row `rejects bad host`.
- **I3–I6 (INFO, no action):** cosmetic test-name punctuation (intent + coverage match);
  Cloudflare egress + `NGROK_AUTHTOKEN` failures are environmental (pass in CI); the user's
  uncommitted WIP in `ScreenIntrospectionTools.kt` / `ScreenCaptureProvider.kt` (screenshot
  annotation removal) blocks 3 tests + 1 detekt finding — user decision (2026-09-09): leave the
  WIP as-is; those failures are expected and out of plan scope.
