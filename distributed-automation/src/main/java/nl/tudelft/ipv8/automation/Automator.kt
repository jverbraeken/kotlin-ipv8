package nl.tudelft.ipv8.automation

import mu.KotlinLogging
import nl.tudelft.ipv8.*
import nl.tudelft.ipv8.keyvault.defaultCryptoProvider
import nl.tudelft.ipv8.messaging.EndpointAggregator
import nl.tudelft.ipv8.messaging.udp.UdpEndpoint
import java.net.InetAddress

private const val PROPERTY_PORT = "port"
private const val DEFAULT_PORT = 55555
private val logger = KotlinLogging.logger {}

fun main() {
    val port = System.getProperty(PROPERTY_PORT, DEFAULT_PORT.toString()).toIntOrNull() ?: DEFAULT_PORT
    val udpEndpoint = UdpEndpoint(port, InetAddress.getByName("0.0.0.0"), localNetworksSupportsUTP = true)
    val endpoint = EndpointAggregator(udpEndpoint, null)
    val automationOverlay = OverlayConfiguration(
        Overlay.Factory(AutomationCommunity::class.java),
        walkers = listOf(SimpleChurn.Factory()),
        maxPeers = Int.MAX_VALUE
    )
    val config = IPv8Configuration(overlays = listOf(automationOverlay))
    val key = defaultCryptoProvider.generateKey()
    val myPeer = Peer(key)
    val ipv8 = IPv8(endpoint, config, myPeer)
    ipv8.start()

    logger.info { "Started automator" }

    while (ipv8.isStarted()) {
        Thread.sleep(1000)
    }
}
