package nl.tudelft.ipv8.messaging.tftp

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import mu.KotlinLogging
import org.apache.commons.net.tftp.*
import org.apache.commons.net.tftp.TFTPClient.DEFAULT_MAX_TIMEOUTS
import java.io.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

/***
 * The size to use for TFTP packet buffers. It's 4 + TFTPPacket.SEGMENT_SIZE, i.e. 516.
 */
private const val PACKET_SIZE = TFTPPacket.SEGMENT_SIZE + 4

class TFTPClient {
    private var _totalBytesSent = 0L
    private val _sendBuffer: ByteArray = ByteArray(PACKET_SIZE)

    suspend fun sendFile(
        filename: String,
        mode: Int,
        input: InputStream,
        host: InetAddress,
        port: Int,
        connectionId: Byte,
        socket: DatagramSocket
    ) {
        var block = 0
        var lastAckWait = false

        _totalBytesSent = 0L

        var sent: TFTPPacket = TFTPWriteRequestPacket(host, port, filename, mode)
        val data = TFTPDataPacket(host, port, 0, _sendBuffer, 4, 0)
        TFTPCommunity.tftpMapClient.putIfAbsent(port, ConcurrentHashMap())
        TFTPCommunity.tftpMapClient[port]!!.putIfAbsent(connectionId, Channel(Channel.UNLIMITED))

        do { // until eof
            // first time: block is 0, lastBlock is 0, send a request packet.
            // subsequent: block is integer starting at 1, send data packet.
            var wantReply = true
            var timeouts = 0
            do {
                try {
                    send(sent.newDatagram(), connectionId, socket)
                    logger.debug { "Waiting for receive... ($port:$connectionId)" }

                    val received = withTimeout(2000) { TFTPCommunity.tftpMapClient[port]!![connectionId]!!.receive() }

                    logger.debug { "!!! Received TFTP packet of type ${received.type} ($port:$connectionId)" }

                    val recdAddress = received.address
                    val recdPort = received.port

                    // Comply with RFC 783 indication that an error acknowledgment
                    // should be sent to originator if unexpected TID or host.
                    if (host == recdAddress && port == recdPort) {
                        when (received.type) {
//                            TFTPPacket.ERROR -> {
//                                val error = received as TFTPErrorPacket
//                                throw IOException(
//                                    "Error code " + error.error + " received: " + error.message
//                                )
//                            }
                            TFTPPacket.ACKNOWLEDGEMENT -> {
                                val lastBlock = (received).blockNumber
                                logger.warn { "ACK block: $lastBlock, expected: $block ($port):$connectionId" }
                                logger.debug { "lastBlock1: $lastBlock, block: $block, port: $port:$connectionId" }
                                if (lastBlock == block) {
                                    ++block
                                    logger.debug { "lastBlock2: $lastBlock, block: $block, port: $port:$connectionId" }
                                    if (block > 65535) {
                                        // wrap the block number
                                        logger.debug { "lastBlock3: $lastBlock, block: $block, port: $port:$connectionId" }
                                        block = 0
                                    }
                                    wantReply = false
                                } else {
                                    logger.debug { "discardPackets:$connectionId" }
                                }
                            }
                            else -> throw IOException("Received unexpected packet type.")
                        }
                    } else {
                        // wrong host or TID; send error
                        val error = TFTPErrorPacket(
                            recdAddress,
                            recdPort,
                            TFTPErrorPacket.UNKNOWN_TID,
                            "Unexpected host or port"
                        )
                        send(error.newDatagram(), connectionId, socket)
                    }
                } catch (e: SocketException) {
                    if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                        throw IOException("Connection timed out ($port:$connectionId)")
                    }
                } catch (e: InterruptedIOException) {
                    if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                        throw IOException("Connection timed out ($port:$connectionId)")
                    }
                } catch (e: TimeoutCancellationException) {
                    if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                        throw IOException("Connection timed out ($port:$connectionId)")
                    }
                } catch (e: TFTPPacketException) {
                    throw IOException("Bad packet: " + e.message)
                }
                // retry until a good ack
            } while (wantReply)
            if (lastAckWait) {
                break // we were waiting for this; now all done
            }
            var dataLength = TFTPPacket.SEGMENT_SIZE
            var offset = 4
            var totalThisPacket = 0
            var bytesRead = 0
            while (dataLength > 0 && input.read(_sendBuffer, offset, dataLength).also { bytesRead = it } > 0) {
                offset += bytesRead
                dataLength -= bytesRead
                totalThisPacket += bytesRead
            }
            if (totalThisPacket < TFTPPacket.SEGMENT_SIZE) {
                /* this will be our last packet -- send, wait for ack, stop */
                lastAckWait = true
            }
            data.blockNumber = block
            logger.debug { "Sending blockNumber: ${data.blockNumber} of $block ($port:$connectionId)"}
            data.setData(_sendBuffer, 4, totalThisPacket)
            sent = data
            _totalBytesSent += totalThisPacket.toLong()
        } while (true) // loops until after lastAckWait is set
        logger.debug { "sendFile finished ($port:$connectionId)" }
        TFTPCommunity.tftpMapClient[port]!!.remove(connectionId)
    }

    fun send(packet: DatagramPacket, connectionId: Byte, socket: DatagramSocket) {
        val tftpPacket = TFTPPacket.newTFTPPacket(packet)
        logger.debug {
            "Send TFTP packet of type ${tftpPacket.type} to " +
                "${packet.address.hostName}:${packet.port} (${packet.length} B) (:$connectionId)"
        }
        val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        val wrappedData = byteArrayOf(TFTPEndpoint.PREFIX_TFTP, connectionId) + data
        packet.setData(wrappedData, 0, wrappedData.size)
        socket.send(packet)
    }

    fun receivePacket(packet: TFTPAckPacket, connectionId: Byte) {
        TFTPCommunity.tftpMapClient[packet.port]!![connectionId]!!.offer(packet)
        logger.debug { "Stored on ${packet.port}:$connectionId the packet"}
    }
}
