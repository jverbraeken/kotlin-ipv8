package nl.tudelft.ipv8.messaging.tftp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import mu.KotlinLogging
import org.apache.commons.net.tftp.*
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

private val logger = KotlinLogging.logger {}

// TODO: Refactor to extend TFTP class and use proxy socket to remove the need for send listener
//  and an input buffer.
class TFTPServer {
    companion object {
        private const val MAX_TIMEOUT_RETRIES = 3
        private const val SO_TIMEOUT = 1000L
    }

    private var shutdownTransfer = false

    private val buffer = Channel<TFTPPacket>(Channel.UNLIMITED)

    var onFileReceived: ((ByteArray, InetAddress, Int) -> Unit)? = null

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    fun onPacket(packet: TFTPPacket,connectionId: Byte, socket: DatagramSocket) {
        if (packet is TFTPWriteRequestPacket) {
            scope.launch {
                handleWrite(packet, connectionId, socket)
            }
        } else {
            buffer.offer(packet)
        }
    }

    /**
     * Handles write requests. It loads all data packets into a memory-backed output stream.
     * Once the entire payload is received, [onFileReceived] listener is invoked with the payload,
     * source address, and port.
     *
     * In the future, the output stream should be exposed via API to allow clients to consume
     * the stream in real-time.
     *
     * @param twrp The first write request packet sent by the client.
     *
     * Inspired by https://github.com/apache/commons-net/blob/eb8ac04598710c952a41c3112877c1ff8e3b3cfa/src/test/java/org/apache/commons/net/tftp/TFTPServer.java
     */
    @Throws(IOException::class, TFTPPacketException::class)
    private suspend fun handleWrite(twrp: TFTPWriteRequestPacket,connectionId: Byte, socket: DatagramSocket) {
        logger.debug { "handleWrite (${twrp.address.hostName}:${twrp.port}:$connectionId)" }

        val bos = ByteArrayOutputStream()

        var lastBlock = 0
        var lastSentAck = TFTPAckPacket(twrp.address, twrp.port, 0)
        send(lastSentAck, connectionId, socket)
        while (true) {
            // get the response - ensure it is from the right place.
            var dataPacket: TFTPPacket? = null
            var timeoutCount = 0
            while (!shutdownTransfer && (dataPacket == null || dataPacket.address != twrp.address || dataPacket.port != twrp.port)) {
                // listen for an answer.
                if (dataPacket != null) {
                    // The data that we got didn't come from the
                    // expected source, fire back an error, and continue
                    // listening.
                    logger.debug("TFTP Server ignoring message from unexpected source.")
                    send(
                        TFTPErrorPacket(
                            dataPacket.address,
                            dataPacket.port, TFTPErrorPacket.UNKNOWN_TID,
                            "Unexpected Host or Port"
                        ), connectionId, socket
                    )
                }
                dataPacket = try {
                    withTimeout(SO_TIMEOUT) {
                        buffer.receive()
                    }
                } catch (e: TimeoutCancellationException) {
                    if (timeoutCount >= MAX_TIMEOUT_RETRIES) {
                        throw e
                    }
                    // It didn't get our ack. Resend it.
                    send(lastSentAck, connectionId, socket)
                    timeoutCount++
                    continue
                }
            }
            if (dataPacket != null && dataPacket is TFTPWriteRequestPacket) {
                // it must have missed our initial ack. Send another.
                lastSentAck = TFTPAckPacket(twrp.address, twrp.port, 0)
                send(lastSentAck, connectionId, socket)
            } else if (dataPacket == null || dataPacket !is TFTPDataPacket) {
                if (!shutdownTransfer) {
                    logger.debug("Unexpected response from tftp client during transfer ("
                        + dataPacket + ").  Transfer aborted.")
                }
                break
            } else {
                val block = dataPacket.blockNumber
                val data = dataPacket.data
                val dataLength = dataPacket.dataLength
                val dataOffset = dataPacket.dataOffset
                if (block > lastBlock || lastBlock == 65535 && block == 0) {
                    // it might resend a data block if it missed our ack
                    // - don't rewrite the block.
                    withContext(Dispatchers.IO) {
                        bos.write(data, dataOffset, dataLength)
                    }
                    lastBlock = block
                }
                lastSentAck = TFTPAckPacket(twrp.address, twrp.port, block)
                send(lastSentAck, connectionId, socket)
                if (dataLength < TFTPDataPacket.MAX_DATA_LENGTH) {
                    // end of stream signal - The tranfer is complete.
                    onFileReceived?.invoke(bos.toByteArray(), twrp.address, twrp.port)

                    // But my ack may be lost - so listen to see if I
                    // need to resend the ack.
                    for (i in 0 until MAX_TIMEOUT_RETRIES) {
                        dataPacket = try {
                            withTimeout(1000) {
                                buffer.receive()
                            }
                        } catch (e: TimeoutCancellationException) {
                            // this is the expected route - the client
                            // shouldn't be sending any more packets.
                            break
                        }
                        if (dataPacket?.address != twrp.address || dataPacket?.port != twrp.port) {
                            // make sure it was from the right client...
                            send(TFTPErrorPacket(dataPacket?.address, dataPacket!!.port,
                                TFTPErrorPacket.UNKNOWN_TID, "Unexpected Host or Port"),
                                connectionId,
                                socket
                            )
                        } else {
                            // This means they sent us the last
                            // data packet again, must have missed our
                            // ack. resend it.
                            send(lastSentAck, connectionId, socket)
                        }
                    }

                    // all done.
                    break
                }
            }
        }
        logger.debug { "handleWrite finished (${twrp.address.hostName}:${twrp.port}:$connectionId)" }
    }

    fun send(packet: TFTPPacket, connectionId: Byte, socket: DatagramSocket) {
        if (packet is TFTPAckPacket) {
            logger.debug { "Sending acknowledgement: ${packet.blockNumber}, ${packet.port}:$connectionId" }
        } else {
            logger.debug { "Sending error packet" }
        }
        val datagram = packet.newDatagram()
        val wrappedData = byteArrayOf(TFTPEndpoint.PREFIX_TFTP, connectionId) + datagram.data
        datagram.setData(wrappedData, 0, wrappedData.size)
//        logger.debug {
//            "Send TFTP packet of type ${packet.type} to client " +
//                "${packet.address.hostName}:${packet.port}:$connectionId (${datagram.length} B)"
//        }
        socket.send(datagram)
    }
}
