package nl.tudelft.ipv8.messaging.fasttftp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import mu.KotlinLogging
import org.apache.commons.net.tftp.*
import org.apache.commons.net.tftp.TFTPClient.DEFAULT_MAX_TIMEOUTS
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

class FastTFTPClient : TFTP() {
    companion object {
        /***
         * The size to use for TFTP packet buffers. It's 4 + TFTPPacket.SEGMENT_SIZE, i.e. 516.
         */
        private const val PACKET_SIZE = TFTPPacket.SEGMENT_SIZE + 4
        private const val BATCH_SIZE = 50
    }

    fun sendFile(
        filename: String,
        mode: Int,
        input: InputStream,
        host: InetAddress,
        port: Int
    ) = synchronized(this) {
        logger.debug { "Creating FastTFTP packets" }
        val packets: MutableMap<Int, TFTPDataPacket> = HashMap()
        val packetsToBeSent: MutableMap<Int, Int> = ConcurrentHashMap()  // block number => #tries
        val packetsUnderway: MutableMap<Int, Long> = ConcurrentHashMap() // block number => date on which it was sent
        val threads = ArrayList<Job>()
        var block = 1  // 0  is reserved for the request block
        var lastAckWait = false

        while (!lastAckWait) {
            var dataLength = TFTPPacket.SEGMENT_SIZE
            var offset = 4
            var totalThisPacket = 0
            var bytesRead = 0
            val _sendBuffer = ByteArray(PACKET_SIZE)
            while (dataLength > 0 &&
                input.read(_sendBuffer, offset, dataLength).also { bytesRead = it } > 0
            ) {
                offset += bytesRead
                dataLength -= bytesRead
                totalThisPacket += bytesRead
            }
            if (totalThisPacket < TFTPPacket.SEGMENT_SIZE) {
                /* this will be our last packet -- send, wait for ack, stop */
                lastAckWait = true
            }
            packets[block] = TFTPDataPacket(host, port, block, _sendBuffer, 4, totalThisPacket)
            packetsToBeSent[block] = 0
            block++
        }
        val packetChannels = arrayOfNulls<Channel<TFTPPacket>>(packets.size)
        for (i in 1 until packets.size) {
            packetChannels[i] = Channel()
        }
        logger.debug { "FastTFTP packets created" }

        GlobalScope.launch {
            send(TFTPWriteRequestPacket(host, port, filename, mode))
        }
        var iter = packetsToBeSent.iterator()

        val received = receive()

        logger.debug { "Initial FastTFTP packets was received with type ${received.type}" }

        for (i in 0 until BATCH_SIZE) {
            threads.add(GlobalScope.launch {
                loop@ while (true) {
                    var res: MutableMap.MutableEntry<Int, Int>
                    if (packetsToBeSent.isEmpty()) {
                        logger.debug { "NO PACKETS ANYMORE :):):)" }
                        break@loop
                    } else {
                        if (!iter.hasNext()) {
                            iter = packetsToBeSent.iterator()
                        }
                        res = iter.next()
                        GlobalScope.launch {
                            packetsToBeSent[res.key] = res.value + 1
                            packetsUnderway[res.key] = System.currentTimeMillis()
                            logger.debug { "Sending FastTFTP packet #${res.key}" }
                            send(packets[res.key])
                        }
                    }

                    var timeouts = 0
                    var received2: TFTPPacket? = null
                    logger.debug { "Waiting for receive for block${res.key}..." }
                    try {
                        received2 = try {
                            withTimeout(3000L) {
                                packetChannels[res.key]!!.receive()
                            }
                        } catch (e: TimeoutCancellationException) {
                            logger.debug { "timeout => sending block ${res.key} again" }
                            if (packetsToBeSent.isEmpty()) {
                                logger.debug { "NO PACKETS ANYMORE :):):)" }
                                break@loop
                            }
                            if (timeouts >= 5) {
                                throw e
                            }
                            GlobalScope.launch {
                                send(packets[res.key])
                            }
                            timeouts++
                            continue
                        }
                    } catch (e: SocketException) {
                        if (packetsToBeSent.isEmpty()) {
                            logger.debug { "NO PACKETS ANYMORE :):):)" }
                            break@loop
                        }
                        if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                            throw IOException("Connection timed out")
                        }
                    } catch (e: InterruptedIOException) {
                        if (packetsToBeSent.isEmpty()) {
                            logger.debug { "NO PACKETS ANYMORE :):):)" }
                            break@loop
                        }
                        if (++timeouts >= DEFAULT_MAX_TIMEOUTS) {
                            throw IOException("Connection timed out")
                        }
                    } catch (e: TFTPPacketException) {
                        if (packetsToBeSent.isEmpty()) {
                            logger.debug { "NO PACKETS ANYMORE :):):)" }
                            break@loop
                        }
                        throw IOException("Bad packet: " + e.message)
                    }
                    logger.debug { "Received ack FastTFTP packet of type ${received2!!.type}, #${res.key}" }

                    val recdAddress = received2!!.address
                    val recdPort = received2.port

                    // Comply with RFC 783 indication that an error acknowledgment
                    // should be sent to originator if unexpected TID or host.
                    if (host == recdAddress && port == recdPort) {
                        when (received2.type) {
                            TFTPPacket.ERROR -> {
                                val error = received2 as TFTPErrorPacket
                                throw IOException(
                                    "Error code " + error.error + " received: " + error.message
                                )
                            }
                            TFTPPacket.ACKNOWLEDGEMENT -> {
                                val lastBlock = (received2 as TFTPAckPacket).blockNumber
                                logger.warn { "ACK block: $lastBlock" }
                                packetsToBeSent.remove(lastBlock)
                                packetsUnderway.remove(lastBlock)
                            }
                            else -> {
                                throw IOException("Received unexpected packet type.")
                            }
                        }
                    } else {
                        // wrong host or TID; send error
                        val error = TFTPErrorPacket(
                            recdAddress,
                            recdPort,
                            TFTPErrorPacket.UNKNOWN_TID,
                            "Unexpected host or port"
                        )
                        send(error)
                    }
                }
            })
        }

        GlobalScope.launch {
            while (true) {
                val received2 = receive()
                GlobalScope.launch {
                    logger.debug { "received ack from block #${(received2 as TFTPAckPacket).blockNumber}" }
                    packetChannels[(received2 as TFTPAckPacket).blockNumber]!!.send(received2)
                }
            }
        }

        GlobalScope.launch {
            threads.joinAll()
            logger.debug { "sendFile finished" }
        }
    }
}
