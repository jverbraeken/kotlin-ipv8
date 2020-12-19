package nl.tudelft.ipv8.automation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mu.KotlinLogging
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.Deserializable
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.Serializable
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream
import java.net.InetAddress

private const val PROPERTY_PORT = "port"
private const val DEFAULT_PORT = 55555
private val logger = KotlinLogging.logger {}
private val job = SupervisorJob()
private val scope = CoroutineScope(Dispatchers.Default + job)

fun main() {
    val port = System.getProperty(PROPERTY_PORT, DEFAULT_PORT.toString()).toIntOrNull() ?: DEFAULT_PORT
    val endpoint = EndpointAggregator(
        UdpEndpoint(port, InetAddress.getByName("0.0.0.0")),
        null
    )
    val config = IPv8Configuration(
        overlays = listOf(
            OverlayConfiguration(
                Overlay.Factory(AutomationCommunity::class.java),
                walkers = listOf(SimpleChurn.Factory()),
                maxPeers = Int.MAX_VALUE
            )
        )
    )
    val key = defaultCryptoProvider.generateKey()
    val myPeer = Peer(key)
    val ipv8 = IPv8(endpoint, config, myPeer)
    ipv8.start()

    logger.info { "Started automator" }

    while (ipv8.isStarted()) {
        Thread.sleep(1000)
    }
}
