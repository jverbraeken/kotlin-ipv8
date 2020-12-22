package nl.tudelft.ipv8.messaging.utp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import mu.KotlinLogging
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.utp.channels.UtpSocketChannel
import nl.tudelft.ipv8.messaging.utp.channels.UtpSocketState
import nl.tudelft.ipv8.messaging.utp.channels.futures.UtpReadFuture
import nl.tudelft.ipv8.messaging.utp.channels.impl.receive.ConnectionIdTriplet
import nl.tudelft.ipv8.messaging.utp.data.UtpPacketUtils
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream


private val logger = KotlinLogging.logger("UTPEndpoint")

//@Volatile
//var busySending = false
//    private set

@Volatile
private var numTransmissions = 0
private const val MAX_NUM_TRANSMISSIONS = 4

fun canSend(): Boolean {
    synchronized(numTransmissions) {
        return numTransmissions < MAX_NUM_TRANSMISSIONS
    }
}

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

class UTPEndpoint : Endpoint<IPv4Address>() {
    var socket: DatagramSocket? = null
    private val connectionIds: MutableMap<Short, ConnectionIdTriplet> = ConcurrentHashMap()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun send(peer: IPv4Address, data: ByteArray) {
        if (canSend()) {
            startTransmission()
            logger.debug { "Sending with UTP to ${peer.ip}:${peer.port}" }
            scope.launch(Dispatchers.IO) {
                val compressedData: ByteArray = ByteArrayOutputStream().use { os ->
                    GZIPOutputStream(os).use { os2 ->
                        os2.write(data)
                    }
                    os.toByteArray()
                }
                logger.debug { "Opening channel ${peer.port}" }
                val channel = UtpSocketChannel.open(socket!!)
                logger.debug { "Connecting to channel to ${peer.ip}:${peer.port}" }
                channel.setupConnectionId()
                registerChannel(channel, peer.port)
                val connectFuture = channel.connect(InetSocketAddress(peer.ip, peer.port))
                logger.debug { "Blocking ${peer.port}" }
                connectFuture.block()
                if (connectFuture.isSuccessful) {
                    logger.debug { "Writing to ${peer.ip}:${peer.port}" }
                    val writeFuture = channel.write(ByteBuffer.wrap(compressedData))
                    logger.debug { "Blocking again to ${peer.ip}:${peer.port}" }
                    writeFuture.block()
                    if (!writeFuture.isSuccessful) {
                        logger.error { "Error writing data to ${peer.ip}:${peer.port}" }
                        endTransmission()
                    }
                    logger.debug { "Closing channel (${peer.ip}:${peer.port})" }
                    channel.close()
                    logger.debug { "Done (${peer.ip}:${peer.port})" }
                } else {
                    logger.error { "Error establishing connection to ${peer.ip}:${peer.port}" }
                }
                endTransmission()
            }
        } else {
            logger.warn { "Not sending UTP packet because still busy sending... ${peer.port}" }
            return
        }
    }

    fun onPacket(packet: DatagramPacket) {
        val unwrappedData = packet.data.copyOfRange(1, packet.length)
        packet.data = unwrappedData
        val utpPacket = UtpPacketUtils.extractUtpPacket(packet)
//        logger.debug("Received UTP packet. connectionId = ${utpPacket.connectionId}, seq=" + utpPacket.sequenceNumber + ", ack=" + utpPacket.ackNumber)

        scope.launch(Dispatchers.IO) {
            if (UtpPacketUtils.isSynPkt(packet)) {
                logger.debug { "syn received: ${packet.port}" }
                synReceived(packet)
            } else {
                val connectionId = connectionIds[utpPacket.connectionId]!!
                val channel = connectionId.channel!!
                channel.receivePacket(packet)
            }
        }
    }

    private fun synReceived(packet: DatagramPacket?) {
        if (handleDoubleSyn(packet)) {
            return
        }
        if (packet != null) {
            val channel = UtpSocketChannel.open(socket)
            channel.receivePacket(packet)
            registerChannel(channel, packet.port)

            scope.launch(Dispatchers.IO) {
                val sourceAddress = IPv4Address(packet.address.hostAddress, packet.port)
                val readFuture: UtpReadFuture = channel.read()
                logger.debug("Blocking readFuture: ${packet.port}")
                readFuture.block()
                logger.debug("Done blocking readFuture: ${packet.port}")
                if (readFuture.isSuccessful) {
                    val data = readFuture.data.toByteArray()
                    logger.debug { "Received UTP file (${data.size} B) from ${sourceAddress.ip}:${sourceAddress.port}" }
                    val uncompressedData: ByteArray = ByteArrayInputStream(data).use { stream ->
                        GZIPInputStream(stream).use { stream2 ->
                            stream2.readBytes()
                        }
                    }
                    notifyListeners(Packet(sourceAddress, uncompressedData))
                } else {
                    logger.error { "Error reading message from ${sourceAddress.ip}:${sourceAddress.port}" }
                }
            }
        }
    }

    /*
	 * handles double syn....
	 */
    private fun handleDoubleSyn(packet: DatagramPacket?): Boolean {
        val pkt = UtpPacketUtils.extractUtpPacket(packet)
        var connId = pkt.connectionId
        connId = (connId + 1).toShort()
        val triplet: ConnectionIdTriplet? = connectionIds[connId]
        if (triplet != null) {
            triplet.channel.receivePacket(packet)
            return true
        }
        return false
    }

    private fun registerChannel(channel: UtpSocketChannel, port: Int): Boolean {
        logger.debug { "Registering channel with connectionId = ${channel.connectionIdReceiving}, port: $port" }
        val triplet =
            ConnectionIdTriplet(channel, channel.connectionIdReceiving, channel.connectionIdSending)
        if (isChannelRegistrationNecessary(channel)) {
            logger.debug { "Channel registration was necessary: $port" }
            connectionIds[channel.connectionIdReceiving] = triplet
            return true
        } else {
            logger.debug { "Channel registration was NOT necessary: $port" }
        }

        /* Connection id collision found or not been able to ack.
		 *  ignore this syn packet */
        return false
    }

    /*
	 * true if channel reg. is required.
	 */
    private fun isChannelRegistrationNecessary(channel: UtpSocketChannel): Boolean {
        return (connectionIds[channel.connectionIdReceiving] == null
            && channel.state != UtpSocketState.SYN_ACKING_FAILED)
    }

    override fun isOpen(): Boolean {
        return false
    }

    override fun open() {
    }

    override fun close() {
    }

    companion object {
        const val PREFIX_UTP: Byte = 67
    }
}
