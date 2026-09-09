package com.danielealbano.androidremotecontrolmcp.data.model

/**
 * A single public endpoint exposed by a tunnel.
 *
 * @property url The public HTTPS base URL (e.g., "https://xxx.trycloudflare.com" or
 *   "https://mcp.example.com").
 * @property valid Whether the endpoint is correctly routed to the local MCP server. Always `true`
 *   for Free/ngrok/rathole; for a Cloudflare token tunnel it is `false` when the dashboard route's service
 *   does not point at `http://(localhost|127.0.0.1):<port>` (advisory — the tunnel keeps running).
 */
data class TunnelEndpoint(
    val url: String,
    val valid: Boolean,
)

/**
 * Represents the current state of the tunnel connection.
 *
 * Used as [kotlinx.coroutines.flow.StateFlow] value, observed by the UI
 * to display tunnel status and public URL.
 */
sealed class TunnelStatus {
    /** Tunnel is not active. */
    data object Disconnected : TunnelStatus()

    /** Tunnel is establishing a connection. */
    data object Connecting : TunnelStatus()

    /**
     * Tunnel is connected and running.
     *
     * @property endpoints The public endpoint(s). May be EMPTY for a Cloudflare token tunnel that
 *   has connected to the edge but has no public hostname configured yet ("no route configured").
 *   Free/ngrok/rathole always expose exactly one (always-valid) endpoint.
     * @property providerType The provider that created this tunnel.
     */
    data class Connected(
        val endpoints: List<TunnelEndpoint>,
        val providerType: TunnelProviderType,
    ) : TunnelStatus()

    /**
     * Tunnel encountered an error.
     *
     * @property message A human-readable error description.
     */
    data class Error(
        val message: String,
    ) : TunnelStatus()
}
