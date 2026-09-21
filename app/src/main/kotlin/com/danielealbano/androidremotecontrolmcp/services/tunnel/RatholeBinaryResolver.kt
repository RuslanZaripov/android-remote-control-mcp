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
