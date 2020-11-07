package nl.tudelft.ipv8.messaging.utp.channels.impl.read;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import nl.tudelft.ipv8.messaging.utp.channels.impl.UtpSocketChannelImpl;
import nl.tudelft.ipv8.messaging.utp.channels.impl.UtpTimestampedPacketDTO;
import nl.tudelft.ipv8.messaging.utp.channels.impl.alg.UtpAlgConfiguration;
import nl.tudelft.ipv8.messaging.utp.data.MicroSecondsTimeStamp;
import nl.tudelft.ipv8.messaging.utp.data.SelectiveAckHeaderExtension;
import nl.tudelft.ipv8.messaging.utp.data.UtpPacket;

public class UtpReadingRunnable extends Thread implements Runnable {
    private final ByteArrayOutputStream bos = new ByteArrayOutputStream();
    private final UtpSocketChannelImpl channel;
    private final SkippedPacketBuffer skippedBuffer = new SkippedPacketBuffer();
    private final UtpReadFutureImpl future;
    private final long startReadingTimeStamp;
    private boolean exceptionOccurred = false;
    private boolean graceFullInterrupt;
    private boolean isRunning;
    private MicroSecondsTimeStamp timeStamper;
    private long totalPayloadLength = 0;
    private long lastPacketTimestamp;
    private int lastPayloadLength;
    private long nowTimeStamp;
    private long lastPackedReceived;
    private boolean gotLastPacket = false;
    // in case we ack every x-th packet, this is the counter.
    private int currentPackedAck = 0;

    public UtpReadingRunnable(UtpSocketChannelImpl channel, MicroSecondsTimeStamp timestamp, UtpReadFutureImpl future) {
        this.channel = channel;
        this.timeStamper = timestamp;
        this.future = future;
        this.lastPayloadLength = UtpAlgConfiguration.MAX_PACKET_SIZE;
        this.startReadingTimeStamp = timestamp.timeStamp();
    }

    @Override
    public void run() {
        UTPReadingRunnableLoggerKt.getLogger().debug("Starting reading");
        isRunning = true;
        IOException exp = null;
        BlockingQueue<UtpTimestampedPacketDTO> queue = channel.getReadingQueue();
        while (continueReading()) {
            UTPReadingRunnableLoggerKt.getLogger().debug("Getting data from queue");
            try {
                UtpTimestampedPacketDTO timestampedPair = queue.poll(UtpAlgConfiguration.TIME_WAIT_AFTER_LAST_PACKET / 2, TimeUnit.MICROSECONDS);
                UTPReadingRunnableLoggerKt.getLogger().debug("Got data from queue: " + (timestampedPair == null));
                nowTimeStamp = timeStamper.timeStamp();
                if (timestampedPair != null) {
                    UTPReadingRunnableLoggerKt.getLogger().debug("Got data from queue, seq=" + timestampedPair.utpPacket().getSequenceNumber());
                    currentPackedAck++;
                    lastPackedReceived = timestampedPair.stamp();
                    if (isLastPacket(timestampedPair)) {
                        gotLastPacket = true;
                        UTPReadingRunnableLoggerKt.getLogger().debug("GOT LAST PACKET");
                        lastPacketTimestamp = timeStamper.timeStamp();
                    }
                    if (isPacketExpected(timestampedPair.utpPacket())) {
                        UTPReadingRunnableLoggerKt.getLogger().debug("Handle expected packet: " + timestampedPair.utpPacket().getSequenceNumber());
                        handleExpectedPacket(timestampedPair);
                    } else {
                        UTPReadingRunnableLoggerKt.getLogger().debug("Handle UUNNNEXPECTED PACKET");
                        handleUnexpectedPacket(timestampedPair);
                    }
                    if (ackThisPacket()) {
                        currentPackedAck = 0;
                    }
                }

                /*TODO: How to measure Rtt here for dynamic timeout limit?*/
                if (isTimedOut()) {
                    UTPReadingRunnableLoggerKt.getLogger().error("Timed out");
                    if (!hasSkippedPackets()) {
                        gotLastPacket = true;
                        exp = new IOException("Timed out...");
                        UTPReadingRunnableLoggerKt.getLogger().error("ENDING READING, NO MORE INCOMING DATA");
                    } else {
                        UTPReadingRunnableLoggerKt.getLogger().error("now: " + nowTimeStamp + " last: " + lastPackedReceived + " = " + (nowTimeStamp - lastPackedReceived));
                        UTPReadingRunnableLoggerKt.getLogger().error("now: " + nowTimeStamp + " start: " + startReadingTimeStamp + " = " + (nowTimeStamp - startReadingTimeStamp));
                        throw new IOException();
                    }
//                    throw new IllegalArgumentException("Timed out");
                }

            } catch (IOException e) {
                UTPReadingRunnableLoggerKt.getLogger().error("IOException");
                exp = e;
                exp.printStackTrace();
                exceptionOccurred = true;
            } catch (InterruptedException e) {
                UTPReadingRunnableLoggerKt.getLogger().error("InterruptedException");
                exp = new IOException();
                e.printStackTrace();
                exceptionOccurred = true;
            } catch (ArrayIndexOutOfBoundsException e) {
                UTPReadingRunnableLoggerKt.getLogger().error("ArrayIndexOutOfBoundsException");
                e.printStackTrace();
                exp = new IOException();
                exceptionOccurred = true;
            }
        }
        isRunning = false;
        future.finished(exp, bos);


        UTPReadingRunnableLoggerKt.getLogger().debug("PAYLOAD LENGTH " + totalPayloadLength);
        UTPReadingRunnableLoggerKt.getLogger().debug("READER OUT");

        channel.returnFromReading();
    }

    private boolean isTimedOut() {
        //TODO: extract constants...
        /* time out after 4sec, when eof not reached */
        boolean timedOut = nowTimeStamp - lastPackedReceived >= 4000000;
        /* but if remote socket has not received synack yet, he will try to reconnect
         * await that as well */
        boolean connectionReattemptAwaited = nowTimeStamp - startReadingTimeStamp >= 4000000;
        return timedOut && connectionReattemptAwaited;
    }

    private boolean isLastPacket(UtpTimestampedPacketDTO timestampedPair) {
        return (timestampedPair.utpPacket().getWindowSize()) == 0;
    }

    private void handleExpectedPacket(UtpTimestampedPacketDTO timestampedPair) throws IOException {
        if (hasSkippedPackets()) {
            bos.write(timestampedPair.utpPacket().getPayload());
            int payloadLength = timestampedPair.utpPacket().getPayload().length;
            lastPayloadLength = payloadLength;
            totalPayloadLength += payloadLength;
            Queue<UtpTimestampedPacketDTO> packets = skippedBuffer.getAllUntillNextMissing();
            short lastSeqNumber = 0;
            if (packets.isEmpty()) {
                lastSeqNumber = timestampedPair.utpPacket().getSequenceNumber();
            }
            UtpPacket lastPacket = null;
            for (UtpTimestampedPacketDTO p : packets) {
                bos.write(p.utpPacket().getPayload());
                payloadLength += p.utpPacket().getPayload().length;
                lastSeqNumber = p.utpPacket().getSequenceNumber();
                lastPacket = p.utpPacket();
            }
            skippedBuffer.reindex(lastSeqNumber);
            channel.setAckNumber(lastSeqNumber);
            //if still has skipped packets, need to selectively ack
            if (hasSkippedPackets()) {
                if (ackThisPacket()) {
                    SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
                    channel.selectiveAckPacket(headerExtension, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
                }

            } else {
                if (ackThisPacket()) {
                    channel.ackPacket(lastPacket, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
                }
            }
        } else {
            if (ackThisPacket()) {
                channel.ackPacket(timestampedPair.utpPacket(), getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
            } else {
                channel.setAckNumber(timestampedPair.utpPacket().getSequenceNumber());
            }
            bos.write(timestampedPair.utpPacket().getPayload());
            totalPayloadLength += timestampedPair.utpPacket().getPayload().length;
        }
    }

    private boolean ackThisPacket() {
        return currentPackedAck >= UtpAlgConfiguration.SKIP_PACKETS_UNTIL_ACK;
    }

    /**
     * Returns the average space available in the buffer in Bytes.
     *
     * @return bytes
     */
    public long getLeftSpaceInBuffer() throws IOException {
        return (skippedBuffer.getFreeSize()) * lastPayloadLength;
    }

    private int getTimestampDifference(UtpTimestampedPacketDTO timestampedPair) {
        return timeStamper.utpDifference(timestampedPair.utpTimeStamp(), timestampedPair.utpPacket().getTimestamp());
    }

    private void handleUnexpectedPacket(UtpTimestampedPacketDTO timestampedPair) throws IOException {
        short expected = getExpectedSeqNr();
        short seqNr = timestampedPair.utpPacket().getSequenceNumber();
        if (skippedBuffer.isEmpty()) {
            skippedBuffer.setExpectedSequenceNumber(expected);
        }
        //TODO: wrapping seq nr: expected can be 5 e.g.
        // but buffer can receive 65xxx, which already has been acked, since seq numbers wrapped.
        // current implementation puts this wrongly into the buffer. it should go in the else block
        // possible fix: alreadyAcked = expected > seqNr || seqNr - expected > CONSTANT;
        boolean alreadyAcked = expected > seqNr;

        boolean saneSeqNr = expected == skippedBuffer.getExpectedSequenceNumber();
        if (saneSeqNr && !alreadyAcked) {
            skippedBuffer.bufferPacket(timestampedPair);
            // need to create header extension after the packet is put into the incoming buffer.
            SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
            if (ackThisPacket()) {
                channel.selectiveAckPacket(headerExtension, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
            }
        } else if (ackThisPacket()) {
            SelectiveAckHeaderExtension headerExtension = skippedBuffer.createHeaderExtension();
            channel.ackAlreadyAcked(headerExtension, getTimestampDifference(timestampedPair), getLeftSpaceInBuffer());
        }
    }

    /**
     * True if this packet is expected.
     *
     * @param utpPacket packet
     */
    public boolean isPacketExpected(UtpPacket utpPacket) {
        short seqNumberFromPacket = utpPacket.getSequenceNumber();
        return getExpectedSeqNr() == seqNumberFromPacket;
    }

    private short getExpectedSeqNr() {
        short ackNumber = channel.getAckNumber();
        if (ackNumber == Short.MAX_VALUE) {
            return 1;
        }
        return (short) (ackNumber + 1);
    }

    private boolean hasSkippedPackets() {
        return !skippedBuffer.isEmpty();
    }

    public void graceFullInterrupt() {
        this.graceFullInterrupt = true;
    }

    private boolean continueReading() {
        return !graceFullInterrupt && !exceptionOccurred && (!gotLastPacket || hasSkippedPackets() || !timeAwaitedAfterLastPacket());
    }

    private boolean timeAwaitedAfterLastPacket() {
        return (timeStamper.timeStamp() - lastPacketTimestamp) > UtpAlgConfiguration.TIME_WAIT_AFTER_LAST_PACKET
            && gotLastPacket;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public MicroSecondsTimeStamp getTimestamp() {
        return timeStamper;
    }

    public void setTimestamp(MicroSecondsTimeStamp timestamp) {
        this.timeStamper = timestamp;
    }
}
