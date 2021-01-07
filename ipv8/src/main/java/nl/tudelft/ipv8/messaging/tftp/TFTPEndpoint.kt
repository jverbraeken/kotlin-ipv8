package nl.tudelft.ipv8.messaging.tftp

import kotlinx.coroutines.*
import mu.KotlinLogging
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.tftp.TFTPEndpoint.Companion.PREFIX_TFTP
import org.apache.commons.net.tftp.*
import java.io.ByteArrayInputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Volatile
private var numTransmissions = 0

private fun startTransmission() {
    synchronized(numTransmissions) {
        numTransmissions++
        logger.debug { "increased numTransmissions to $numTransmissions" }
    }
}

private fun endTransmission() {
    synchronized(numTransmissions) {
        numTransmissions--
        logger.debug { "decreased numTransmissions to $numTransmissions" }
    }
}

/**
 * An endpoint that allows to send binary data blobs that are larger than UDP packet size over UDP.
 * It uses a TFTP-like protocol with adjusted client and server to share a single socket instead
 * of control + transfer sockets defined in the TFTP protocol.
 *
 * All packets are prefixed with a [PREFIX_TFTP] byte to allow it to be distinguished from regular
 * IPv8 UDP packets in UDPEndpoint which are prefixed with [Community.PREFIX_IPV8].
 */
class TFTPEndpoint : Endpoint<IPv4Address>() {
    private val tftpClients = ConcurrentHashMap<IPv4Address, ConcurrentHashMap<Byte, TFTPClient>>()
    internal var tftpServers = ConcurrentHashMap<IPv4Address, ConcurrentHashMap<Byte, TFTPServer>>()

    var socket: DatagramSocket? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    override fun isOpen(): Boolean {
        return socket?.isBound == true
    }

    fun getNumTransmissions(): Int {
        return numTransmissions
    }

    override fun send(peer: IPv4Address, data: ByteArray) {
        startTransmission()
        scope.launch(Dispatchers.IO) {
            logger.debug { "Sending to port ${peer.port}" }
            val inputStream = ByteArrayInputStream(data)
            val inetAddress = Inet4Address.getByName(peer.ip)
            var availableConnectionId = Byte.MIN_VALUE
            tftpClients.putIfAbsent(peer, ConcurrentHashMap())
            logger.debug { "send tftpClients: ${tftpClients[peer]!!.keys().toList()}" }
            while (tftpClients[peer]!!.containsKey(availableConnectionId)) {
                availableConnectionId++
            }
            tftpClients[peer]!![availableConnectionId] = TFTPClient()
            try {
                logger.debug { "Sending with ${peer.port}:$availableConnectionId" }
                tftpClients[peer]!![availableConnectionId]!!.sendFile(
                    TFTP_FILENAME,
                    TFTP.BINARY_MODE,
                    inputStream,
                    inetAddress,
                    peer.port,
                    availableConnectionId,
                    socket!!
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                endTransmission()
                tftpClients[peer]!!.remove(availableConnectionId)
                logger.debug { "Removed ${peer.port}" }
            }
        }
    }

    /**
     * Should be invoked by UDPEndpoint when a new packet is coming from
     */
    fun onPacket(packet: DatagramPacket) {
        try {
            val connectionId = packet.data[1]
            // Unwrap prefix and connection id
            val unwrappedData = packet.data.copyOfRange(2, packet.length)
            packet.setData(unwrappedData, 0, unwrappedData.size)
            val tftpPacket = TFTPPacket.newTFTPPacket(packet)

            logger.debug {
                "Received TFTP packet of type ${tftpPacket.type} (${packet.length} B) " +
                    "from ${packet.port}:$connectionId"
            }

            val address = IPv4Address(tftpPacket.address.hostAddress, tftpPacket.port)
            tftpServers.putIfAbsent(address, ConcurrentHashMap())
            if (tftpPacket is TFTPWriteRequestPacket) {
                tftpServers[address]!![connectionId] = {
                    val instance = TFTPServer()
                    logger.debug { "Created TFTPServer on ${address.port}:$connectionId" }
                    instance.onFileReceived = { data, address2, port ->
                        tftpServers[address]!!.remove(connectionId)
                        val sourceAddress = IPv4Address(address2.hostAddress, port)
                        val received = Packet(sourceAddress, data)
                        logger.debug("Received TFTP file (${data.size} B) from $sourceAddress")
                        notifyListeners(received)
                    }
                    instance
                }.invoke()
                val tftpServer = tftpServers[address]!![connectionId]!!
                tftpServer.onPacket(tftpPacket, connectionId, socket!!)
            } else if (tftpPacket is TFTPDataPacket) {
                logger.debug { "Packet is DataPacket => going to $address:$connectionId" }
                logger.debug { "tftpServers: ${tftpServers.keys().toList()}  ;  ${tftpServers[address]!!.keys().toList()}" }
                val tftpServer = tftpServers[address]!![connectionId]!!
                tftpServer.onPacket(tftpPacket, connectionId, socket!!)
            } else if (tftpPacket is TFTPAckPacket || tftpPacket is TFTPErrorPacket) {
                logger.debug { "Packet is AckPacket => going to $address:$connectionId" }
                logger.debug { "tftpClients: ${tftpClients.keys().toList()}  ;  ${tftpClients[address]!!.keys().toList()}" }
                tftpClients[address]!![connectionId]!!.receivePacket(tftpPacket as TFTPAckPacket, connectionId)
//                tftpSockets[address]!!.buffer.offer(packet)
            } else {
                // This is an unsupported packet (ReadRequest)
                logger.debug { "Unsupported TFTP packet type: ReadRequest" }
            }
        } catch (e: Exception) {
            logger.error(e) { "Failed to handle TFTP packet" }
        }
    }

    override fun open() {
        // Skip
    }

    override fun close() {
        // Skip
    }

    companion object {
        private const val TFTP_FILENAME = "ipv8_packet.bin"
        const val PREFIX_TFTP: Byte = 69
    }
}
