package com.danielealbano.androidremotecontrolmcp.integration

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import com.danielealbano.androidremotecontrolmcp.services.tunnel.RatholeBinaryResolver
import com.danielealbano.androidremotecontrolmcp.services.tunnel.RatholeTunnelProvider
import com.danielealbano.androidremotecontrolmcp.testutil.RecordingServerLogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Integration test that runs the provider's rathole CLIENT against a real rathole SERVER
 * process on loopback (Noise transport, token auth).
 *
 * Requires the host `rathole` binary (v0.5.0 x86_64 asset) on PATH.
 * This test FAILs (not skips) if the binary is not found — CI always installs it.
 *
 * The provider under test uses the bundled-binary code path verbatim; only the binary resolver
 * points at the host binary and the Context's filesDir at a temp dir.
 */
@DisplayName("RatholeTunnelIntegrationTest")
@Timeout(value = TUNNEL_TEST_TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
class RatholeTunnelIntegrationTest {
    @TempDir
    lateinit var tempDir: File

    private lateinit var binaryPath: String
    private lateinit var provider: RatholeTunnelProvider

    private var serverProcess: Process? = null
    private var backendServer: ServerSocket? = null
    private var backendThread: Thread? = null
    private var boundBackendPort: Int = 0

    @BeforeEach
    fun setUp() {
        binaryPath =
            HostRatholeBinaryResolver().resolve()
                ?: fail(
                    "rathole binary not found on host. Download the pinned v0.5.0 x86_64 asset " +
                        "from https://github.com/rathole-org/rathole/releases and put `rathole` on PATH.",
                )

        // Build.SUPPORTED_ABIS is null on the JVM
        mockkObject(RatholeTunnelProvider.Companion)
        every { RatholeTunnelProvider.isSupportedAbi() } returns true

        val mockContext = mockk<Context> { every { filesDir } returns tempDir }
        provider =
            RatholeTunnelProvider(
                binaryResolver =
                    object : RatholeBinaryResolver {
                        override fun resolve(): String = binaryPath
                    },
                context = mockContext,
                serverLogRepository = RecordingServerLogRepository(),
            )
    }

    @AfterEach
    fun tearDown() {
        runBlocking {
            try {
                provider.stop()
            } catch (_: Exception) {
                // Ignore cleanup errors
            }
        }
        serverProcess?.let { p ->
            p.destroy()
            p.waitFor()
        }
        serverProcess = null
        backendThread?.interrupt()
        backendThread = null
        try {
            backendServer?.close()
        } catch (_: Exception) {
            // Ignore cleanup errors
        }
        backendServer = null
        unmockkAll()
    }

    @Test
    fun `tunnel connects and proxies traffic to local test server`() =
        runBlocking {
            val (privKey, pubKey) = generateKeyPair()
            val channelPort = freePort()
            val servicePort = freePort()
            startBackendHttpServer()
            startRatholeServer(channelPort, servicePort, TOKEN, privKey)

            val config = ratholeConfig(channelPort, pubKey, TOKEN)
            provider.start(boundBackendPort, config)

            val connected = provider.awaitStatus { it is TunnelStatus.Connected } as TunnelStatus.Connected
            assertEquals(TunnelProviderType.RATHOLE, connected.providerType)
            assertEquals("https://mcp.test.local", connected.endpoints.single().url)

            // Full chain: local fetch -> rathole service port -> Noise tunnel -> local backend
            val body = fetchWithRetry("http://127.0.0.1:$servicePort")
            assertTrue(
                body.contains(EXPECTED_RESPONSE_BODY),
                "Expected '$EXPECTED_RESPONSE_BODY' in response, got: $body",
            )

            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }

    @Test
    fun `wrong token sets auth error and kills the client`() =
        runBlocking {
            val (privKey, pubKey) = generateKeyPair()
            val channelPort = freePort()
            val servicePort = freePort()
            startBackendHttpServer()
            startRatholeServer(channelPort, servicePort, TOKEN, privKey)

            val config = ratholeConfig(channelPort, pubKey, "wrong")
            provider.start(boundBackendPort, config)

            val error = provider.awaitStatus { it is TunnelStatus.Error } as TunnelStatus.Error
            assertTrue(error.message.contains("authentication failed"))

            provider.stop()
            // Process guard is cleared — a fresh start must not throw
            provider.start(boundBackendPort, config)
            provider.stop()
        }

    @Test
    fun `client without server stays Connecting (transient failures are not errors)`() =
        runBlocking {
            val (privKey, pubKey) = generateKeyPair()
            val channelPort = freePort()
            // No rathole server started
            startBackendHttpServer()

            val config = ratholeConfig(channelPort, pubKey, TOKEN)
            provider.start(boundBackendPort, config)

            // Give the client time to attempt (and fail) the connection
            delay(5_000)
            val failedFast =
                withTimeoutOrNull(1_000) {
                    provider.status.first { it is TunnelStatus.Error }
                }
            assertEquals(null, failedFast, "Transient connect failures must not set Error")
            assertTrue(
                provider.status.value is TunnelStatus.Connecting,
                "Expected Connecting, got ${provider.status.value}",
            )

            provider.stop()
            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }

    private fun ratholeConfig(
        channelPort: Int,
        publicKey: String,
        token: String,
    ) = ServerConfig(
        ratholeServerAddr = "127.0.0.1:$channelPort",
        ratholeServerPublicKey = publicKey,
        ratholeToken = token,
        ratholePublicUrl = "https://mcp.test.local",
    )

    /** Generates a Noise key pair via `rathole --genkey` (label and key on separate lines). */
    private fun generateKeyPair(): Pair<String, String> {
        val proc = ProcessBuilder(binaryPath, "--genkey").redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        check(proc.waitFor() == 0) { "rathole --genkey failed: $output" }
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }

        fun keyAfter(label: String): String {
            val idx = lines.indexOfFirst { it == label }
            check(idx >= 0) { "rathole --genkey output missing '$label': $output" }
            return lines[idx + 1]
        }
        return keyAfter("Private Key:") to keyAfter("Public Key:")
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    /** Minimal HTTP/1.1 responder (same shape as CloudflareTunnelIntegrationTest). */
    private fun startBackendHttpServer() {
        val server = ServerSocket(0)
        boundBackendPort = server.localPort
        backendServer = server
        backendThread =
            Thread {
                while (!Thread.currentThread().isInterrupted) {
                    try {
                        val socket: Socket = server.accept()
                        handleTestRequest(socket)
                    } catch (_: java.net.SocketException) {
                        break
                    } catch (_: Exception) {
                        // Continue accepting
                    }
                }
            }.also {
                it.isDaemon = true
                it.start()
            }
    }

    private fun handleTestRequest(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val writer = PrintWriter(s.getOutputStream(), true)

            var line: String? = reader.readLine()
            while (line != null && line.isNotEmpty()) {
                line = reader.readLine()
            }

            val body = EXPECTED_RESPONSE_BODY
            writer.print("HTTP/1.1 200 OK\r\n")
            writer.print("Content-Type: text/plain\r\n")
            writer.print("Content-Length: ${body.length}\r\n")
            writer.print("Connection: close\r\n")
            writer.print("\r\n")
            writer.print(body)
            writer.flush()
        }
    }

    private fun startRatholeServer(
        channelPort: Int,
        servicePort: Int,
        token: String,
        privateKey: String,
    ) {
        val toml =
            File(tempDir, "server.toml").apply {
                writeText(
                    """
                    [server]
                    bind_addr = "127.0.0.1:$channelPort"

                    [server.transport]
                    type = "noise"

                    [server.transport.noise]
                    local_private_key = "$privateKey"

                    [server.services.mcp]
                    token = "$token"
                    bind_addr = "127.0.0.1:$servicePort"
                    """.trimIndent(),
                )
            }
        serverProcess = ProcessBuilder(binaryPath, toml.absolutePath).start()
    }

    private fun fetchWithRetry(url: String): String {
        var lastException: Exception? = null
        repeat(FETCH_MAX_RETRIES) { attempt ->
            try {
                val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = FETCH_CONNECT_TIMEOUT_MS
                connection.readTimeout = FETCH_READ_TIMEOUT_MS
                connection.requestMethod = "GET"

                val responseCode = connection.responseCode
                if (responseCode == HTTP_OK) {
                    return connection.inputStream.bufferedReader().readText()
                }
                lastException = Exception("HTTP $responseCode")
                Thread.sleep(FETCH_RETRY_DELAY_MS * (attempt + 1))
            } catch (e: Exception) {
                lastException = e
                Thread.sleep(FETCH_RETRY_DELAY_MS * (attempt + 1))
            }
        }
        fail<String>(
            "Failed to fetch $url after $FETCH_MAX_RETRIES attempts: ${lastException?.message}",
        )
        return ""
    }

    private suspend fun RatholeTunnelProvider.awaitStatus(predicate: (TunnelStatus) -> Boolean): TunnelStatus =
        withTimeout(CONNECT_TIMEOUT_MS) { status.first(predicate) }
}

/**
 * Resolves the rathole binary from the host system's PATH.
 */
class HostRatholeBinaryResolver : RatholeBinaryResolver {
    override fun resolve(): String? =
        try {
            val result = ProcessBuilder("which", "rathole").start()
            val path =
                result.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            if (result.waitFor() == 0 && path.isNotEmpty()) path else null
        } catch (_: Exception) {
            null
        }
}

private const val TUNNEL_TEST_TIMEOUT_SECONDS = 120L

/** Timeout waiting for the tunnel to connect / fail (milliseconds). */
private const val CONNECT_TIMEOUT_MS = 30_000L

private const val TOKEN = "test-token"
private const val EXPECTED_RESPONSE_BODY = "OK rathole integration test"
private const val FETCH_MAX_RETRIES = 5
private const val FETCH_CONNECT_TIMEOUT_MS = 5_000
private const val FETCH_READ_TIMEOUT_MS = 5_000
private const val FETCH_RETRY_DELAY_MS = 500L
private const val HTTP_OK = 200
