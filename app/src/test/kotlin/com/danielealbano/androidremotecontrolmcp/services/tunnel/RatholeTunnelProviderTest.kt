package com.danielealbano.androidremotecontrolmcp.services.tunnel

import android.content.Context
import com.danielealbano.androidremotecontrolmcp.data.model.ServerConfig
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelEndpoint
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelProviderType
import com.danielealbano.androidremotecontrolmcp.data.model.TunnelStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("RatholeTunnelProvider")
class RatholeTunnelProviderTest {
    @TempDir
    lateinit var tmpDir: File

    private val mockBinaryResolver = mockk<RatholeBinaryResolver>()
    private val mockContext =
        mockk<Context> {
            // `answers` defers the read until filesDir is called (after @TempDir injection)
            every { filesDir } answers { tmpDir }
        }

    private fun createProvider(): RatholeTunnelProvider = RatholeTunnelProvider(mockBinaryResolver, mockContext)

    /** Stubs the companion ABI check (Build.SUPPORTED_ABIS is null on the JVM). */
    private fun stubAbi(supported: Boolean = true) {
        mockkObject(RatholeTunnelProvider.Companion)
        every { RatholeTunnelProvider.isSupportedAbi() } returns supported
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    private fun ratholeConfig() =
        ServerConfig(
            ratholeServerAddr = "mcp.example.com:2333",
            ratholeServerPublicKey = "A" + "B".repeat(42) + "=",
            ratholeToken = "t",
            ratholePublicUrl = "https://mcp.example.com",
        )

    /**
     * Creates a fake rathole script that prints [stdoutLines] to stdout (like the real
     * binary — rathole logs to stdout) and then blocks like the real long-running process.
     */
    private fun fakeBinaryEmitting(vararg stdoutLines: String): String {
        val script = File.createTempFile("fake-rathole", ".sh")
        script.deleteOnExit()
        val sb = StringBuilder("#!/bin/sh\n")
        for (line in stdoutLines) {
            sb.append("printf '%s\\n' '").append(line).append("'\n")
        }
        sb.append("sleep 60\n")
        script.writeText(sb.toString())
        script.setExecutable(true)
        return script.absolutePath
    }

    /**
     * Creates a fake rathole script that prints [stdoutLines] to stdout and then exits
     * immediately (unlike [fakeBinaryEmitting], which blocks like the real process).
     */
    private fun fakeBinaryExiting(vararg stdoutLines: String): String {
        val script = File.createTempFile("fake-rathole-exit", ".sh")
        script.deleteOnExit()
        val sb = StringBuilder("#!/bin/sh\n")
        for (line in stdoutLines) {
            sb.append("printf '%s\\n' '").append(line).append("'\n")
        }
        script.writeText(sb.toString())
        script.setExecutable(true)
        return script.absolutePath
    }

    private suspend fun RatholeTunnelProvider.awaitStatus(predicate: (TunnelStatus) -> Boolean): TunnelStatus =
        withTimeout(AWAIT_TIMEOUT_MS) { status.first(predicate) }

    @Nested
    @DisplayName("start")
    inner class Start {
        @Test
        fun `start on unsupported ABI sets error with helpful message`() =
            runTest {
                stubAbi(supported = false)
                every { mockBinaryResolver.resolve() } returns fakeBinaryEmitting()
                val provider = createProvider()

                provider.start(8080, ratholeConfig())

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue((status as TunnelStatus.Error).message.contains("not supported"))
                assertTrue((status as TunnelStatus.Error).message.contains("Cloudflare"))
            }

        @Test
        fun `start with missing server address sets error`() =
            runTest {
                stubAbi()
                val provider = createProvider()
                val config = ratholeConfig().copy(ratholeServerAddr = "")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertEquals(
                    "rathole configuration is missing: server address",
                    (status as TunnelStatus.Error).message,
                )
            }

        @Test
        fun `start with all fields missing lists all of them`() =
            runTest {
                stubAbi()
                val provider = createProvider()

                provider.start(8080, ServerConfig())

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue((status as TunnelStatus.Error).message.contains("server address"))
                assertTrue((status as TunnelStatus.Error).message.contains("server public key"))
                assertTrue((status as TunnelStatus.Error).message.contains("service token"))
                assertTrue((status as TunnelStatus.Error).message.contains("public URL"))
            }

        @Test
        fun `start with unsafe characters in token sets error`() =
            runTest {
                stubAbi()
                val provider = createProvider()
                val config = ratholeConfig().copy(ratholeToken = "bad\"token")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue((status as TunnelStatus.Error).message.contains("unsupported characters"))
                assertTrue((status as TunnelStatus.Error).message.contains("service token"))
            }

        @Test
        fun `start with invalid public key sets error`() =
            runTest {
                stubAbi()
                val provider = createProvider()
                val config = ratholeConfig().copy(ratholeServerPublicKey = "A" + "B".repeat(41) + "=")

                provider.start(8080, config)

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertTrue((status as TunnelStatus.Error).message.contains("44-char base64 Noise key"))
            }

        @Test
        fun `start with missing binary sets error status`() =
            runTest {
                stubAbi()
                every { mockBinaryResolver.resolve() } returns null
                val provider = createProvider()

                provider.start(8080, ratholeConfig())

                val status = provider.status.value
                assertTrue(status is TunnelStatus.Error)
                assertEquals("rathole binary not found", (status as TunnelStatus.Error).message)
            }

        @Test
        fun `start when already running throws IllegalStateException`() =
            runTest {
                stubAbi()
                every { mockBinaryResolver.resolve() } returns fakeBinaryEmitting()
                val provider = createProvider()

                provider.start(8080, ratholeConfig())
                try {
                    val ex =
                        assertThrows<IllegalStateException> {
                            provider.start(8080, ratholeConfig())
                        }
                    assertEquals("Tunnel is already running", ex.message)
                } finally {
                    provider.stop()
                }
            }

        @Test
        fun `connected line transitions to Connected with configured public URL`() =
            runBlocking {
                stubAbi()
                every {
                    mockBinaryResolver.resolve()
                } returns fakeBinaryEmitting("Control channel established")
                val provider = createProvider()
                val config = ratholeConfig()

                provider.start(8080, config)
                val status = provider.awaitStatus { it is TunnelStatus.Connected }

                assertEquals(
                    TunnelStatus.Connected(
                        endpoints = listOf(TunnelEndpoint(url = "https://mcp.example.com", valid = true)),
                        providerType = TunnelProviderType.RATHOLE,
                    ),
                    status,
                )
                val toml = File(File(tmpDir, "rathole"), "client.toml").readText()
                assertTrue(toml.contains("remote_addr = \"mcp.example.com:2333\""))
                assertTrue(toml.contains("type = \"noise\""))
                assertTrue(toml.contains("[client.services.mcp]"))
                provider.stop()
            }

        @Test
        fun `auth failed line sets Error and kills process`() =
            runBlocking {
                stubAbi()
                every {
                    mockBinaryResolver.resolve()
                } returns fakeBinaryEmitting("Authentication failed: mcp")
                val provider = createProvider()

                provider.start(8080, ratholeConfig())
                val status = provider.awaitStatus { it is TunnelStatus.Error }

                assertEquals(
                    "rathole authentication failed — check the service token",
                    (status as TunnelStatus.Error).message,
                )
                provider.stop()
                // Process guard is cleared — a fresh start must not throw
                provider.start(8080, ratholeConfig())
                provider.stop()
            }

        @Test
        fun `stop after connected returns to Disconnected`() =
            runBlocking {
                stubAbi()
                every {
                    mockBinaryResolver.resolve()
                } returns fakeBinaryEmitting("Control channel established")
                val provider = createProvider()

                provider.start(8080, ratholeConfig())
                provider.awaitStatus { it is TunnelStatus.Connected }
                provider.stop()

                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }

        @Test
        fun `process exit without connected line sets Error and allows restart`() =
            runBlocking {
                stubAbi()
                every {
                    mockBinaryResolver.resolve()
                } returns fakeBinaryExiting()
                val provider = createProvider()

                provider.start(8080, ratholeConfig())
                val status = provider.awaitStatus { it is TunnelStatus.Error }

                assertEquals(
                    "rathole process exited unexpectedly (code 0)",
                    (status as TunnelStatus.Error).message,
                )
                provider.stop()
                // Process guard is cleared — a fresh start must not throw
                provider.start(8080, ratholeConfig())
                provider.stop()
            }

        @Test
        fun `process exit after stop does not set Error`() =
            runBlocking {
                stubAbi()
                every {
                    mockBinaryResolver.resolve()
                } returns fakeBinaryEmitting("Control channel established")
                val provider = createProvider()

                provider.start(8080, ratholeConfig())
                provider.awaitStatus { it is TunnelStatus.Connected }
                provider.stop()

                // Give the cancelled reader time to observe the EOF; the expected exit must
                // not surface as an error.
                delay(EXIT_SETTLE_MS)
                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }
    }

    @Nested
    @DisplayName("stop")
    inner class Stop {
        @Test
        fun `stop when not running is no-op`() =
            runTest {
                val provider = createProvider()

                provider.stop()

                assertEquals(TunnelStatus.Disconnected, provider.status.value)
            }
    }

    @Nested
    @DisplayName("status")
    inner class Status {
        @Test
        fun `initial status is Disconnected`() {
            val provider = createProvider()

            assertEquals(TunnelStatus.Disconnected, provider.status.value)
        }
    }

    @Nested
    @DisplayName("companion pure functions")
    inner class CompanionFunctions {
        @Test
        fun `isAbiSupported returns true only for arm64-v8a`() {
            assertTrue(RatholeTunnelProvider.isAbiSupported(arrayOf("arm64-v8a")))
            assertTrue(RatholeTunnelProvider.isAbiSupported(arrayOf("x86_64", "arm64-v8a")))
            assertFalse(RatholeTunnelProvider.isAbiSupported(arrayOf("x86_64")))
            assertFalse(RatholeTunnelProvider.isAbiSupported(emptyArray()))
        }

        @Test
        fun `isValidPublicKey accepts 44-char base64 and rejects others`() {
            assertTrue(RatholeTunnelProvider.isValidPublicKey("A" + "B".repeat(42) + "="))
            assertFalse(RatholeTunnelProvider.isValidPublicKey("A" + "B".repeat(41) + "="))
            assertFalse(RatholeTunnelProvider.isValidPublicKey("A" + "B".repeat(43) + "="))
            assertFalse(RatholeTunnelProvider.isValidPublicKey(""))
            assertFalse(RatholeTunnelProvider.isValidPublicKey("A" + "B".repeat(42)))
        }

        @Test
        fun `containsUnsafeTomlChars detects quote backslash and control chars`() {
            assertTrue(RatholeTunnelProvider.containsUnsafeTomlChars("a\"b"))
            assertTrue(RatholeTunnelProvider.containsUnsafeTomlChars("a\\b"))
            assertTrue(RatholeTunnelProvider.containsUnsafeTomlChars("a\nb"))
            assertTrue(RatholeTunnelProvider.containsUnsafeTomlChars("a\tb"))
            assertFalse(RatholeTunnelProvider.containsUnsafeTomlChars("mcp.example.com:2333"))
            assertFalse(RatholeTunnelProvider.containsUnsafeTomlChars("token-with-dashes_123"))
        }

        @Test
        fun `renderClientConfig emits noise transport mcp service and quoted values`() {
            val toml =
                RatholeTunnelProvider.renderClientConfig(
                    serverAddr = "mcp.example.com:2333",
                    publicKey = "PUB",
                    token = "tok",
                    localAddr = "127.0.0.1:8080",
                )

            assertTrue(toml.contains("[client]"))
            assertTrue(toml.contains("remote_addr = \"mcp.example.com:2333\""))
            assertTrue(toml.contains("[client.transport]"))
            assertTrue(toml.contains("type = \"noise\""))
            assertTrue(toml.contains("remote_public_key = \"PUB\""))
            assertTrue(toml.contains("[client.services.mcp]"))
            assertTrue(toml.contains("token = \"tok\""))
            assertTrue(toml.contains("local_addr = \"127.0.0.1:8080\""))
        }
    }
}

private const val AWAIT_TIMEOUT_MS = 10_000L
private const val EXIT_SETTLE_MS = 1_000L
