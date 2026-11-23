package com.deviceagent.adb;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * 24-byte ADB message header plus optional payload.
 *
 * {@code {cmd, arg0, arg1, data_length, data_check, magic=cmd^0xffffffff}} little-endian.
 * {@code data_check} is the unsigned byte-sum of the payload (AOSP {@code calculate_apacket_checksum});
 * for protocol ≥ {@code 0x01000001} senders may set it to 0.
 */
final class AdbMessage {
    static final int HEADER_LENGTH = 24;

    final int command;
    final int arg0;
    final int arg1;
    final int dataLength;
    final int dataCheck;
    final int magic;
    final byte[] data;

    AdbMessage(int command, int arg0, int arg1, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.data = data == null ? new byte[0] : data;
        this.dataLength = this.data.length;
        this.dataCheck = checksum(this.data);
        this.magic = command ^ 0xffffffff;
    }

    AdbMessage(int command, int arg0, int arg1, String data) {
        this(command, arg0, arg1, data == null ? null : data.getBytes(StandardCharsets.UTF_8));
    }

    AdbMessage(int command, int arg0, int arg1, int dataLength, int dataCheck, int magic, byte[] data) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.dataLength = dataLength;
        this.dataCheck = dataCheck;
        this.magic = magic;
        this.data = data == null ? new byte[0] : data;
    }

    static int checksum(byte[] data) {
        int sum = 0;
        if (data == null) {
            return 0;
        }
        for (byte b : data) {
            sum += b & 0xff;
        }
        return sum;
    }

    byte[] encode() {
        byte[] out = new byte[HEADER_LENGTH + data.length];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(command);
        buf.putInt(arg0);
        buf.putInt(arg1);
        buf.putInt(dataLength);
        buf.putInt(dataCheck);
        buf.putInt(magic);
        if (data.length > 0) {
            buf.put(data);
        }
        return out;
    }

    static AdbMessage decode(byte[] headerAndPayload) {
        if (headerAndPayload.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("ADB header too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(headerAndPayload).order(ByteOrder.LITTLE_ENDIAN);
        int command = buf.getInt();
        int arg0 = buf.getInt();
        int arg1 = buf.getInt();
        int dataLength = buf.getInt();
        int dataCheck = buf.getInt();
        int magic = buf.getInt();
        byte[] data = new byte[Math.max(0, headerAndPayload.length - HEADER_LENGTH)];
        if (data.length > 0) {
            buf.get(data);
        }
        AdbMessage msg = new AdbMessage(command, arg0, arg1, dataLength, dataCheck, magic, data);
        msg.validateOrThrow();
        return msg;
    }

    static AdbMessage decodeHeader(byte[] header24) {
        if (header24.length < HEADER_LENGTH) {
            throw new IllegalArgumentException("ADB header too short");
        }
        ByteBuffer buf = ByteBuffer.wrap(header24).order(ByteOrder.LITTLE_ENDIAN);
        return new AdbMessage(
                buf.getInt(),
                buf.getInt(),
                buf.getInt(),
                buf.getInt(),
                buf.getInt(),
                buf.getInt(),
                new byte[0]);
    }

    void validateOrThrow() {
        if (magic != (command ^ 0xffffffff)) {
            throw new IllegalArgumentException(
                    "bad ADB magic: cmd=" + AdbProtocol.cmdName(command) + " magic=0x"
                            + Integer.toHexString(magic));
        }
        if (data.length != 0 && dataLength != data.length) {
            throw new IllegalArgumentException("data_length mismatch");
        }
        int sum = checksum(data);
        // Protocol ≥ 0x01000001 may send checksum 0; accept either 0 or the real sum.
        if (dataCheck != 0 && dataCheck != sum) {
            throw new IllegalArgumentException("bad ADB checksum");
        }
    }

    String dataAsString() {
        if (data.length == 0) {
            return "";
        }
        int n = data.length;
        if (data[n - 1] == 0) {
            n--;
        }
        return new String(data, 0, n, StandardCharsets.UTF_8);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AdbMessage)) {
            return false;
        }
        AdbMessage m = (AdbMessage) o;
        return command == m.command
                && arg0 == m.arg0
                && arg1 == m.arg1
                && dataLength == m.dataLength
                && dataCheck == m.dataCheck
                && magic == m.magic
                && Arrays.equals(data, m.data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new int[] {command, arg0, arg1, dataLength, dataCheck, magic})
                * 31 + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "AdbMessage{cmd=" + AdbProtocol.cmdName(command)
                + " arg0=" + arg0 + " arg1=" + arg1
                + " len=" + dataLength + "}";
    }
}
