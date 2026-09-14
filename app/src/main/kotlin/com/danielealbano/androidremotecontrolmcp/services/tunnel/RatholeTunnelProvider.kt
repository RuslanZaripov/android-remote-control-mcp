package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import android.os.Build
import android.util.Log
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
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
            private val procDir: File = File(DEFAULT_PROC_DIR),
        ) : TunnelProvider {
        private val _status = MutableStateFlow<TunnelStatus>(TunnelStatus.Disconnected)
        override val status: StateFlow<TunnelStatus> = _status.asStateFlow()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val mutex = Mutex()
        private var process: Process? = null
        private var logReaderJob: Job? = null

        override suspend fun start(
            localPort: Int,
            config: ServerConfig,
        ) {
            mutex.withLock {
                check(process == null) { "Tunnel is already running" }

                val binaryPath = preflight(config) ?: return

                _status.value = TunnelStatus.Connecting
                if (!killStaleClients()) return
                try {
                    val configPath = writeClientConfig(localPort, config)
                    // rathole logs to stdout — merge so the log reader sees every line
                    val pb = ProcessBuilder(binaryPath, "--client", configPath)
                    pb.redirectErrorStream(true)
                    val proc = pb.start()
                    process = proc

                    launchLogReader(proc) { line -> handleLine(line, config.ratholePublicUrl) }
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
         * Runs all preflight checks (ABI support, config completeness, unsafe characters,
         * public-key format, binary availability). Sets the error [TunnelStatus] and returns
         * null when a check fails; otherwise returns the resolved rathole binary path.
         */
        private fun preflight(config: ServerConfig): String? {
            val validationError =
                abiErrorOrNull() ?: Companion.validateRatholeConfigFields(config)
            if (validationError != null) {
                _status.value = TunnelStatus.Error(validationError)
                return null
            }
            val binaryPath = binaryResolver.resolve()
            if (binaryPath == null) _status.value = TunnelStatus.Error("rathole binary not found")
            return binaryPath
        }

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

        /** Returns an error message when the device ABI is unsupported, otherwise null. */
        private fun abiErrorOrNull(): String? {
            if (isSupportedAbi()) return null
            val abis = Build.SUPPORTED_ABIS?.joinToString() ?: "unknown"
            return "rathole is not supported on this device architecture ($abis). Use Cloudflare instead."
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
         * terminal (rathole would retry forever) → error + kill. `Control channel established`
         * → Connected with the user-configured public URL. Everything else (transient
         * connection retries) is ignored — rathole backs off and retries on its own.
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
         * Cancels the log reader job and tears down the process. Must be called while
         * holding [mutex]. No-op when no process is running.
         */
        private suspend fun teardownProcess() {
            val proc = process ?: return
            logReaderJob?.cancel()
            logReaderJob = null

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
                        // redirectErrorStream(true) merges stderr into stdout — read the merged stream
                        BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                            while (isActive) {
                                @Suppress("BlockingMethodInNonBlockingContext")
                                val line = reader.readLine() ?: break
                                Log.d(TAG, "rathole: $line")
                                onLine(line)
                            }
                        }
                        if (isActive) handleProcessExit(proc)
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        if (isActive) {
                            Log.w(TAG, "Error reading rathole logs", e)
                        }
                    }
                }
        }

        /**
         * Runs after the merged output stream hits EOF (the process exited). When the tunnel is
         * still supposed to be running, surfaces the exit as [TunnelStatus.Error] and tears down.
         * Only called from the log reader job when it is still active (no concurrent
         * [teardownProcess] cancelled it); the status guard also covers a stop() or auth-fail
         * teardown racing between the call site and the status check.
         */
        private suspend fun handleProcessExit(proc: Process) {
            @Suppress("BlockingMethodInNonBlockingContext")
            val exitCode = proc.waitFor()
            if (_status.value !is TunnelStatus.Disconnected &&
                _status.value !is TunnelStatus.Error
            ) {
                Log.w(TAG, "rathole process exited unexpectedly with code $exitCode")
                _status.value =
                    TunnelStatus.Error("rathole process exited unexpectedly (code $exitCode)")
                mutex.withLock { teardownProcess() }
            }
        }

        companion object {
            private const val TAG = "MCP:RatholeTunnel"

            /** rathole client log line emitted once the control channel is established. */
            internal const val MSG_CONNECTED = "Control channel established"

            /** rathole client log line emitted when the service token is rejected. */
            internal const val MSG_AUTH_FAILED = "Authentication failed"

            internal const val SHUTDOWN_TIMEOUT_MS = 5_000L

            internal const val DEFAULT_PROC_DIR = "/proc"

            internal const val SUPPORTED_ABI = "arm64-v8a"

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

            /** SIGKILL via the system `kill` (toybox on Android); true when signalled successfully. */
            @Suppress("BlockingMethodInNonBlockingContext")
            internal fun killProcess(pid: Int): Boolean =
                runCatching {
                    ProcessBuilder("kill", "-9", pid.toString()).start().waitFor() == 0
                }.getOrDefault(false)

            /** 44-char base64 of a 32-byte X25519 Noise public key. */
            internal val PUBLIC_KEY_REGEX = Regex("^[A-Za-z0-9+/]{43}=$")

            /** Pure ABI-membership check, separated from [Build] so it is unit-testable. */
            internal fun isAbiSupported(deviceAbis: Array<String>): Boolean = deviceAbis.any { it == SUPPORTED_ABI }

            internal fun isSupportedAbi(): Boolean = isAbiSupported(Build.SUPPORTED_ABIS)

            internal fun isValidPublicKey(publicKey: String): Boolean = PUBLIC_KEY_REGEX.matches(publicKey)

            private fun isUnsafeTomlChar(c: Char): Boolean = c == '"' || c == '\\' || c.isISOControl()

            /** True when the value cannot be embedded verbatim in a double-quoted TOML string. */
            internal fun containsUnsafeTomlChars(value: String): Boolean = value.any { isUnsafeTomlChar(it) }

            /**
             * Returns a human-readable error message for [config], or null when the config
             * is complete, safe to embed in TOML, and carries a well-formed public key.
             */
            internal fun validateRatholeConfigFields(config: ServerConfig): String? {
                val fields =
                    listOf(
                        config.ratholeServerAddr to "server address",
                        config.ratholeServerPublicKey to "server public key",
                        config.ratholeToken to "service token",
                        config.ratholePublicUrl to "public URL",
                    )
                val missing = fields.filter { it.first.isEmpty() }.joinToString(", ") { it.second }
                val unsafe =
                    fields.filter { containsUnsafeTomlChars(it.first) }.joinToString(", ") { it.second }
                return when {
                    missing.isNotEmpty() -> {
                        "rathole configuration is missing: $missing"
                    }

                    unsafe.isNotEmpty() -> {
                        "rathole configuration contains unsupported characters " +
                            "(quote, backslash, or control chars): $unsafe"
                    }

                    !isValidPublicKey(config.ratholeServerPublicKey) -> {
                        "rathole server public key must be the 44-char base64 Noise key " +
                            "printed by `rathole --genkey`"
                    }

                    else -> {
                        null
                    }
                }
            }

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
