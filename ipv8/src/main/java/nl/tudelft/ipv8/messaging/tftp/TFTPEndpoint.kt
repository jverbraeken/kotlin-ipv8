package nl.tudelft.ipv8.messaging.tftp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import nl.tudelft.ipv8.IPv4Address
import nl.tudelft.ipv8.messaging.Endpoint
import nl.tudelft.ipv8.messaging.Packet
import nl.tudelft.ipv8.messaging.tftp.TFTPEndpoint.Companion.PREFIX_TFTP
import org.apache.commons.net.DatagramSocketFactory
import org.apache.commons.net.tftp.*
import java.io.ByteArrayInputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

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
    private val tftpSockets = ConcurrentHashMap<IPv4Address, TFTPSocket>()
    private val tftpClients = ConcurrentHashMap<IPv4Address, TFTPClient>()
    internal var tftpServers = ConcurrentHashMap<IPv4Address, TFTPServer>()

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
        thread {
            startTransmission()
            val inputStream = ByteArrayInputStream(data)
            val inetAddress = Inet4Address.getByName(peer.ip)
            tftpClients[peer] = TFTPClient()
            tftpSockets[peer] = TFTPSocket()
            tftpClients[peer]!!.setDatagramSocketFactory(object : DatagramSocketFactory {
                override fun createDatagramSocket(): DatagramSocket {
                    return tftpSockets[peer]!!
                }

                override fun createDatagramSocket(port: Int): DatagramSocket {
                    throw IllegalStateException("Operation not supported")
                }

                override fun createDatagramSocket(port: Int, laddr: InetAddress?): DatagramSocket {
                    throw IllegalStateException("Operation not supported")
                }
            })
            tftpClients[peer]!!.open()
            try {
                tftpClients[peer]!!.sendFile(
                    TFTP_FILENAME,
                    TFTP.BINARY_MODE,
                    inputStream,
                    inetAddress,
                    peer.port
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                endTransmission()
            }
        }
    }

    /**
     * Should be invoked by UDPEndpoint when a new packet is coming from
     */
    fun onPacket(packet: DatagramPacket) {
        try {
            // Unwrap prefix
            val unwrappedData = packet.data.copyOfRange(1, packet.length)
            packet.setData(unwrappedData, 0, unwrappedData.size)
            val tftpPacket = TFTPPacket.newTFTPPacket(packet)

            logger.debug {
                "Received TFTP packet of type ${tftpPacket.type} (${packet.length} B) " +
                    "from ${packet.address.hostAddress}:${packet.port}"
            }

            val address = IPv4Address(tftpPacket.address.hostAddress, tftpPacket.port)
            if (tftpPacket is TFTPWriteRequestPacket) {
                tftpServers[address] = {
                    val instance = TFTPServer { packet ->
                        scope.launch(Dispatchers.IO) {
                            val datagram = packet.newDatagram()
                            val wrappedData = byteArrayOf() + PREFIX_TFTP + datagram.data
                            datagram.setData(wrappedData, 0, datagram.length + 1)
                            val socket = socket
                            if (socket != null) {
                                logger.debug {
                                    "Send TFTP packet of type ${packet.type} to client " +
                                        "${packet.address.hostName}:${packet.port} (${datagram.length} B)"
                                }
                                socket.send(datagram)
                            } else {
                                logger.error { "TFTP socket is missing" }
                            }
                        }
                    }
                    instance.onFileReceived = { data, address, port ->
                        val sourceAddress = IPv4Address(address.hostAddress, port)
                        val received = Packet(sourceAddress, data)
                        logger.debug("Received TFTP file (${data.size} B) from $sourceAddress")
                        notifyListeners(received)
                    }
                    instance
                }.invoke()
                val tftpServer = tftpServers[address]!!
                tftpServer.onPacket(tftpPacket)
            } else if (tftpPacket is TFTPDataPacket) {
                val tftpServer = tftpServers[address]!!
                tftpServer.onPacket(tftpPacket)
            } else if (tftpPacket is TFTPAckPacket || tftpPacket is TFTPErrorPacket) {
                tftpSockets[address]!!.buffer.offer(packet)
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

    /**
     * A socket that serves as a proxy between the actual DatagramSocket and TFTP implementation.
     */
    inner class TFTPSocket : DatagramSocket() {
        val buffer = Channel<DatagramPacket>(Channel.UNLIMITED)

        override fun send(packet: DatagramPacket) {
            val tftpPacket = TFTPPacket.newTFTPPacket(packet)
            logger.debug {
                "Send TFTP packet of type ${tftpPacket.type} to " +
                    "${packet.address.hostName}:${packet.port} (${packet.length} B)"
            }
            val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val wrappedData = byteArrayOf(PREFIX_TFTP) + data
            packet.setData(wrappedData, 0, wrappedData.size)

            val socket = socket
            if (socket != null) {
                socket.send(packet)
            } else {
                logger.error { "TFTP socket is missing" }
            }
        }

        override fun receive(packet: DatagramPacket) {
            runBlocking {
                try {
                    withTimeout(soTimeout.toLong()) {
                        val received = buffer.receive()
                        packet.address = received.address
                        packet.port = received.port
                        packet.setData(received.data, received.offset, received.length)
                        val tftpPacket = TFTPPacket.newTFTPPacket(packet)
                        logger.debug {
                            "Client received TFTP packet of type ${tftpPacket.type} " +
                                "from ${received.address.hostName}:${received.port} (${packet.length} B)"
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    throw SocketTimeoutException()
                }
            }
        }
    }

    companion object {
        private const val TFTP_FILENAME = "ipv8_packet.bin"
        const val PREFIX_TFTP: Byte = 69
    }
}
