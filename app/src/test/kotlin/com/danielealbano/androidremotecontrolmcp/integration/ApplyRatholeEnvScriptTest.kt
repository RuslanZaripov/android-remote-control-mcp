package com.danielealbano.androidremotecontrolmcp.integration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Tests the host-side `scripts/apply-rathole-env.sh` (Plan 67 US1) by spawning it via
 * ProcessBuilder. All cases use the `--dry-run` path (plus `bash -n`), so no adb device is
 * required. The script's env file is pointed at a temp file via RATHOLE_ENV_FILE.
 */
@DisplayName("ApplyRatholeEnvScriptTest")
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ApplyRatholeEnvScriptTest {
    @TempDir
    lateinit var tempDir: File

    private val script: File
        get() {
            val candidate = File(System.getProperty("user.dir"), "../scripts/apply-rathole-env.sh")
            if (!candidate.isFile) {
                fail(
                    "scripts/apply-rathole-env.sh not found at ${candidate.absolutePath} " +
                        "(test working dir: ${System.getProperty("user.dir")})",
                )
            }
            return candidate
        }

    private fun writeEnvFile(content: String): File {
        val envFile = File(tempDir, "test.env")
        envFile.writeText(content)
        return envFile
    }

    private fun runScript(envFile: File?, vararg args: String): ProcessResult {
        val command = mutableListOf("bash", script.absolutePath)
        if (envFile != null) {
            command.add("--dry-run")
        }
        command.addAll(args)
        val env = HashMap(System.getenv())
        if (envFile != null) {
            env["RATHOLE_ENV_FILE"] = envFile.absolutePath
        } else {
            env["RATHOLE_ENV_FILE"] = File(tempDir, "does-not-exist.env").absolutePath
        }
        val process =
            ProcessBuilder(command)
                .environment(env)
                .redirectErrorStream(false)
                .start()
        val stdout = process.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val stderr = process.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val exited = process.waitFor()
        return ProcessResult(exited, stdout, stderr)
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    @Test
    fun `script passes bash -n syntax check`() {
        val process =
            ProcessBuilder("bash", "-n", script.absolutePath)
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        assertEquals(0, exitCode, "bash -n failed:\n$output")
    }

    @Test
    fun `dry-run with full env prints configure command with masked secrets`() {
        val envFile =
            writeEnvFile(
                "RATHOLE_SERVER_ADDR=203.0.113.10:2333\n" +
                    "RATHOLE_SERVER_PUBLIC_KEY=${"A".repeat(44)}\n" +
                    "RATHOLE_TOKEN=super-secret-token-123\n" +
                    "RATHOLE_PUBLIC_URL=https://mcp.example.com\n",
            )
        val result = runScript(envFile)
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("ADB_CONFIGURE"), "stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("203.0.113.10:2333"), "stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("tunnel_provider RATHOLE"), "stdout: ${result.stdout}")
        assertTrue(result.stdout.contains("--es rathole_token '"), "stdout: ${result.stdout}")
        assertFalse(
            result.stdout.contains("super-secret-token-123"),
            "full token leaked in stdout: ${result.stdout}",
        )
        assertFalse(
            result.stdout.contains("A".repeat(44)),
            "full key leaked in stdout: ${result.stdout}",
        )
    }

    @Test
    fun `dry-run with --start also prints start-server command`() {
        val envFile =
            writeEnvFile(
                "RATHOLE_SERVER_ADDR=203.0.113.10:2333\n" +
                    "RATHOLE_SERVER_PUBLIC_KEY=${"A".repeat(44)}\n" +
                    "RATHOLE_TOKEN=tokentoken123456789\n" +
                    "RATHOLE_PUBLIC_URL=https://mcp.example.com\n",
            )
        val result = runScript(envFile, "--start")
        assertEquals(0, result.exitCode, "stderr: ${result.stderr}")
        assertTrue(result.stdout.contains("ADB_START_SERVER"), "stdout: ${result.stdout}")
    }

    @Test
    fun `dry-run fails when a variable is empty`() {
        val envFile =
            writeEnvFile(
                "RATHOLE_SERVER_ADDR=203.0.113.10:2333\n" +
                    "RATHOLE_SERVER_PUBLIC_KEY=${"A".repeat(44)}\n" +
                    "RATHOLE_TOKEN=\n" +
                    "RATHOLE_PUBLIC_URL=https://mcp.example.com\n",
            )
        val result = runScript(envFile)
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains("RATHOLE_TOKEN"), "stderr: ${result.stderr}")
    }

    @Test
    fun `dry-run fails when .env is missing`() {
        val result = runScript(null)
        assertEquals(1, result.exitCode)
        assertTrue(result.stderr.contains(".env"), "stderr: ${result.stderr}")
    }

    @Test
    fun `unknown option exits with usage`() {
        val result = runScript(null, "--bogus")
        assertEquals(2, result.exitCode)
        assertTrue(result.stderr.contains("usage"), "stderr: ${result.stderr}")
    }
}
