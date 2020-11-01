package nl.tudelft.ipv8.messaging.utp.channels.impl.write;

import java.io.IOException;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import nl.tudelft.ipv8.messaging.utp.channels.impl.UtpSocketChannelImpl;
import nl.tudelft.ipv8.messaging.utp.channels.impl.UtpTimestampedPacketDTO;
import nl.tudelft.ipv8.messaging.utp.channels.impl.alg.UtpAlgorithm;
import nl.tudelft.ipv8.messaging.utp.data.MicroSecondsTimeStamp;
import nl.tudelft.ipv8.messaging.utp.data.UtpPacket;

import static nl.tudelft.ipv8.messaging.utp.data.UtpPacketUtils.extractUtpPacket;

public class UtpWritingRunnable extends Thread implements Runnable {
    private final ByteBuffer buffer;
    private final UtpSocketChannelImpl channel;
    private final UtpAlgorithm algorithm;
    private final MicroSecondsTimeStamp timeStamper;
    private final UtpWriteFutureImpl future;
    private volatile boolean graceFullInterrupt;
    private boolean isRunning = false;
    private IOException possibleException = null;

    public UtpWritingRunnable(UtpSocketChannelImpl channel, ByteBuffer buffer, MicroSecondsTimeStamp timeStamper, UtpWriteFutureImpl future) {
        this.buffer = buffer;
        this.channel = channel;
        this.timeStamper = timeStamper;
        this.future = future;
        algorithm = new UtpAlgorithm(timeStamper, channel.getRemoteAdress());
    }

    @Override
    public void run() {
        UTPWritingRunnableLoggerKt.getLogger().debug("Starting sending with sequence number: " + channel.getSequenceNumber());
        algorithm.initiateAckPosition(channel.getSequenceNumber());
        algorithm.setTimeStamper(timeStamper);
        algorithm.setByteBuffer(buffer);
        isRunning = true;
        IOException possibleExp = null;
        boolean exceptionOccurred = false;
        while (continueSending()) {
            try {
                if (!checkForAcks()) {
                    UTPWritingRunnableLoggerKt.getLogger().debug("No acks");
                    graceFullInterrupt = true;
                    break;
                }
                UTPWritingRunnableLoggerKt.getLogger().debug("Acks found");

                Queue<DatagramPacket> packetsToResend = algorithm.getPacketsToResend();
                for (DatagramPacket datagramPacket : packetsToResend) {
                    datagramPacket.setSocketAddress(channel.getRemoteAdress());
                    channel.sendPacket(datagramPacket);
                    UTPWritingRunnableLoggerKt.getLogger().debug("Resent packet: " + extractUtpPacket(datagramPacket).getSequenceNumber());
                }
            } catch (IOException exp) {
                exp.printStackTrace();
                graceFullInterrupt = true;
                possibleExp = exp;
                break;
            }

            if (algorithm.isTimedOut()) {
                UTPWritingRunnableLoggerKt.getLogger().debug("Timed out");
                graceFullInterrupt = true;
                possibleExp = new IOException("timed out");
                exceptionOccurred = true;
            }
            while (algorithm.canSendNextPacket() && !exceptionOccurred && !graceFullInterrupt && buffer.hasRemaining()) {
                try {
                    channel.sendPacket(getNextPacket());
                } catch (IOException exp) {
                    exp.printStackTrace();
                    graceFullInterrupt = true;
                    possibleExp = exp;
                    break;
                }
            }
            updateFuture();
        }

        if (possibleExp != null) {
            exceptionOccurred(possibleExp);
        }
        isRunning = false;
        future.finished(possibleExp, buffer.position());
        UTPWritingRunnableLoggerKt.getLogger().debug("WRITER OUT");
        channel.removeWriter();
    }

    private void updateFuture() {
        future.setBytesSend(buffer.position());
    }


    private boolean checkForAcks() {
        BlockingQueue<UtpTimestampedPacketDTO> queue = channel.getWritingQueue();
        try {
            waitAndProcessAcks(queue);
        } catch (InterruptedException ie) {
            return false;
        }
        return true;
    }

    private void waitAndProcessAcks(BlockingQueue<UtpTimestampedPacketDTO> queue) throws InterruptedException {
        long waitingTimeMicros = algorithm.getWaitingTimeMicroSeconds();
        UTPWritingRunnableLoggerKt.getLogger().debug("1");
        UtpTimestampedPacketDTO temp = queue.poll(waitingTimeMicros, TimeUnit.MICROSECONDS);
        if (temp != null) {
            UTPWritingRunnableLoggerKt.getLogger().debug("2");
            algorithm.ackReceived(temp);
            algorithm.removeAcked();
            if (queue.peek() != null) {
                processAcks(queue);
            }
            UTPWritingRunnableLoggerKt.getLogger().debug("3");
        }
    }

    private void processAcks(BlockingQueue<UtpTimestampedPacketDTO> queue) {
        UtpTimestampedPacketDTO pair;
        UTPWritingRunnableLoggerKt.getLogger().debug("4");
        while ((pair = queue.poll()) != null) {
            UTPWritingRunnableLoggerKt.getLogger().debug("5");
            algorithm.ackReceived(pair);
            algorithm.removeAcked();
        }
    }

    private DatagramPacket getNextPacket() {
        int packetSize = algorithm.sizeOfNextPacket();
        int remainingBytes = buffer.remaining();

        if (remainingBytes < packetSize) {
            packetSize = remainingBytes;
        }

        byte[] payload = new byte[packetSize];
        buffer.get(payload);
        UtpPacket utpPacket = channel.getNextDataPacket();
        utpPacket.setPayload(payload);

        int leftInBuffer = buffer.remaining();
        utpPacket.setWindowSize(leftInBuffer);
        byte[] utpPacketBytes = utpPacket.toByteArray();
        UTPWritingRunnableLoggerKt.getLogger().debug("Sending next packet: " + utpPacket.getSequenceNumber());
        DatagramPacket udpPacket = new DatagramPacket(utpPacketBytes, utpPacketBytes.length, channel.getRemoteAdress());
        algorithm.markPacketOnfly(utpPacket, udpPacket);
        return udpPacket;
    }


    private void exceptionOccurred(IOException exp) {
        possibleException = exp;
    }

    public IOException getException() {
        return possibleException;
    }

    private boolean continueSending() {
        UTPWritingRunnableLoggerKt.getLogger().debug("Continue sending: " + (!graceFullInterrupt && !allPacketsAckedSendAndAcked()) + " <=" + graceFullInterrupt + ", " + allPacketsAckedSendAndAcked());
        return !graceFullInterrupt && !allPacketsAckedSendAndAcked();
    }

    private boolean allPacketsAckedSendAndAcked() {
//		return finSend && algorithm.areAllPacketsAcked() && !buffer.hasRemaining();
        return algorithm.areAllPacketsAcked() && !buffer.hasRemaining();
    }


    public void graceFullInterrupt() {
        UTPWritingRunnableLoggerKt.getLogger().debug("GraceFullInterrupt()");
        graceFullInterrupt = true;
        throw new RuntimeException("GraceFullInterrupt error");
    }

    public boolean isRunning() {
        return isRunning;
    }
}
