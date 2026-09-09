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

        private fun requireEnv(name: String): String =
            System.getenv(name) ?: error("$name missing — checkPrerequisites should have skipped")

        private fun envConfigured(): Boolean =
            listOf("RATHOLE_SERVER_ADDR", "RATHOLE_SERVER_PUBLIC_KEY", "RATHOLE_TOKEN")
                .all { !System.getenv(it).isNullOrEmpty() }
    }
}
