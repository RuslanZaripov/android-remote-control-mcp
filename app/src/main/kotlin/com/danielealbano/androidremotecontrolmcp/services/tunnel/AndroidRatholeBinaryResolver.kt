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
