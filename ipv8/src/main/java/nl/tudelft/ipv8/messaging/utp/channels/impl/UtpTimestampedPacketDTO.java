package nl.tudelft.ipv8.messaging.utp.channels.impl;

import java.net.DatagramPacket;

import nl.tudelft.ipv8.messaging.utp.data.UtpPacket;

public class UtpTimestampedPacketDTO {

    private final UtpPacket utpPacket;
    private final int utpTimeStamp;
    private DatagramPacket packet;
    private Long timestamp;
    private int ackedAfterMeCounter = 0;
    private boolean isPacketAcked = false;
    private boolean reduceWindow;

    private boolean resendBecauseSkipped;
    private int resendCounter = 0;

    public UtpTimestampedPacketDTO(DatagramPacket p, UtpPacket u, Long s, int utpStamp) {
        this.timestamp = s;
        this.utpPacket = u;
        this.packet = p;
        this.utpTimeStamp = utpStamp;
    }

    public void incrementResendCounter() {
        resendCounter++;
    }

    public int getResendCounter() {
        return resendCounter;
    }

    public DatagramPacket dataGram() {
        return packet;
    }

    public void setDgPacket(DatagramPacket p) {
        this.packet = p;
    }

    public Long stamp() {
        return timestamp;
    }

    public void setStamp(long stamp) {
        this.timestamp = stamp;
    }

    public UtpPacket utpPacket() {
        return utpPacket;
    }

    public int utpTimeStamp() {
        return utpTimeStamp;
    }

    public boolean isPacketAcked() {
        return isPacketAcked;
    }

    public void setPacketAcked(boolean isPacketAcked) {
        this.isPacketAcked = isPacketAcked;
    }

    public int getAckedAfterMeCounter() {
        return ackedAfterMeCounter;
    }

    public void setAckedAfterMeCounter(int ackedAfterMeCounter) {
        this.ackedAfterMeCounter = ackedAfterMeCounter;
    }

    public void incrementAckedAfterMe() {
        ackedAfterMeCounter++;
    }

    public boolean reduceWindow() {
        return reduceWindow;
    }

    public void setReduceWindow(boolean val) {
        reduceWindow = val;
    }

    public boolean alreadyResendBecauseSkipped() {
        return resendBecauseSkipped;
    }

    public void setResendBecauseSkipped(boolean value) {
        resendBecauseSkipped = value;
    }

}
