package app.domik.deimic.client

import app.domik.deimic.protocol.ProtocolConstants

/** Network address of a controller in the local network. */
data class ControllerEndpoint(val host: String, val port: Int = ProtocolConstants.DEFAULT_PORT) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port out of range: $port" }
    }

    override fun toString(): String = if (':' in host) "[$host]:$port" else "$host:$port"
}

/**
 * A controller configured by the user. Storage (and the serial number, which is kept separately in encrypted
 * storage) is the app's responsibility – this is only the data model.
 *
 * @property id stable identifier (UUID string).
 */
data class ControllerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = ProtocolConstants.DEFAULT_PORT,
) {
    val endpoint: ControllerEndpoint get() = ControllerEndpoint(host, port)
}
