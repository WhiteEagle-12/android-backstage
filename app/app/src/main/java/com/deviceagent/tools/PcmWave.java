package com.deviceagent.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;

/** Bounded PCM WAV parsing; audio bytes are streamed, never loaded as one array. */
public final class PcmWave {
    public record Format(int rate, int channels, long offset, long bytes) {}
    private PcmWave() {}
    private static ByteBuffer read(FileChannel file, long offset, int count) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
        while (b.hasRemaining()) {
            int n = file.read(b, offset + b.position());
            if (n <= 0) throw new IOException("The WAV file is incomplete");
        }
        b.flip(); return b;
    }
    private static String tag(ByteBuffer b) {
        byte[] tag = new byte[4]; b.get(tag);
        return new String(tag, StandardCharsets.US_ASCII);
    }
    public static Format parse(FileChannel file) throws IOException {
        ByteBuffer head = read(file, 0, 12);
        if (!tag(head).equals("RIFF")) throw new IOException("Use a PCM WAV file");
        long end = Integer.toUnsignedLong(head.getInt()) + 8;
        if (!tag(head).equals("WAVE") || end > file.size()) throw new IOException("The WAV file is incomplete");
        int rate = 0, channels = 0;
        for (long at = 12; at + 8 <= end;) {
            ByteBuffer chunk = read(file, at, 8); String kind = tag(chunk);
            long size = Integer.toUnsignedLong(chunk.getInt()), data = at + 8;
            if (data + size > end) throw new IOException("The WAV chunk is incomplete");
            if (kind.equals("fmt ")) {
                if (size < 16) throw new IOException("The WAV format is incomplete");
                ByteBuffer fmt = read(file, data, 16);
                int encoding = Short.toUnsignedInt(fmt.getShort()); channels = Short.toUnsignedInt(fmt.getShort());
                rate = fmt.getInt(); int bytesPerSecond = fmt.getInt(); int align = Short.toUnsignedInt(fmt.getShort());
                if (encoding != 1 || fmt.getShort() != 16 || channels < 1 || channels > 2 || rate < 8000 || rate > 192000
                        || align != channels * 2 || bytesPerSecond != rate * align)
                    throw new IOException("Use 16-bit PCM WAV, mono or stereo, at 8–192 kHz");
            } else if (kind.equals("data")) {
                if (rate == 0 || size % (channels * 2) != 0) throw new IOException("The WAV audio format or frame size is invalid");
                return new Format(rate, channels, data, size);
            }
            at = data + size + (size & 1);
        }
        throw new IOException("The WAV file has no audio data");
    }
    public static void header(FileChannel file, int rate, int channels, long bytes) throws IOException {
        if (bytes < 0 || bytes > 0xffffffffL - 36) throw new IOException("WAV size limit reached; start another audio file");
        ByteBuffer h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int)(36 + bytes));
        h.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short)1).putShort((short)channels);
        h.putInt(rate).putInt(rate * channels * 2).putShort((short)(channels * 2)).putShort((short)16);
        h.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int)bytes).flip();
        while (h.hasRemaining()) file.write(h, h.position());
    }
}
