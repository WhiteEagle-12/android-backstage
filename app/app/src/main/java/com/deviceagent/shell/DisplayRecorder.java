package com.deviceagent.shell;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;
import com.deviceagent.tools.Json;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Encodes virtual display frames to a short H.264 MP4 clip via MediaCodec's
 * input Surface + MediaMuxer, without interfering with ImageReader ownership.
 */
final class DisplayRecorder {
    final String recordingId;
    final int displayId;
    final String path;
    final int width;
    final int height;
    final int fps;
    final int maxSeconds;
    final long startedAt;

    private final Supplier<Bitmap> frameSource;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private volatile String state = "recording";
    private volatile String error;
    private volatile int frames;
    private volatile long endedAt;
    private volatile long bytes;
    private Thread thread;

    DisplayRecorder(int displayId, String path, int rawWidth, int rawHeight, int fps, int maxSeconds, Supplier<Bitmap> frameSource) {
        this.recordingId = "rec_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.displayId = displayId;
        this.path = path;
        this.width = align16(Math.max(128, rawWidth));
        this.height = align16(Math.max(128, rawHeight));
        this.fps = Math.max(1, Math.min(15, fps));
        this.maxSeconds = Math.max(1, Math.min(30, maxSeconds));
        this.startedAt = System.currentTimeMillis();
        this.frameSource = frameSource;
    }

    private static int align16(int v) {
        return Math.max(16, (v + 8) & ~15);
    }

    void start() {
        File outFile = new File(path);
        File parent = outFile.getParentFile();
        if (parent != null) parent.mkdirs();
        thread = new Thread(this::run, "backstage-vd-record-" + displayId);
        thread.setDaemon(true);
        thread.start();
    }

    boolean isRunning() {
        return "recording".equals(state);
    }

    void stopAndWait() {
        stopRequested.set(true);
        Thread t = thread;
        if (t != null && t != Thread.currentThread()) {
            t.interrupt();
            try {
                t.join(5_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    Map<String, Object> statusMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", !"failed".equals(state));
        m.put("recordingId", recordingId);
        m.put("displayId", displayId);
        m.put("state", state);
        m.put("path", path);
        m.put("width", width);
        m.put("height", height);
        m.put("fps", fps);
        m.put("frames", frames);
        long end = endedAt > 0 ? endedAt : System.currentTimeMillis();
        m.put("durationMs", Math.max(0L, end - startedAt));
        File f = new File(path);
        long sz = f.isFile() ? f.length() : bytes;
        if (sz > 0) m.put("bytes", sz);
        if (error != null && !error.isEmpty()) m.put("error", error);
        return m;
    }

    private void run() {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        Surface inputSurface = null;
        boolean muxerStarted = false;
        int trackIndex = -1;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(400_000, Math.min(2_500_000, width * height * 2)));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            inputSurface = codec.createInputSurface();
            codec.start();

            muxer = new MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            Rect dst = new Rect(0, 0, width, height);

            long frameIntervalMs = 1000L / fps;
            long deadline = startedAt + (maxSeconds * 1000L);

            while (!stopRequested.get() && System.currentTimeMillis() < deadline) {
                long tickStart = System.currentTimeMillis();
                Bitmap frame = frameSource.get();
                if (frame != null) {
                    try {
                        Canvas canvas = inputSurface.lockCanvas(null);
                        try {
                            canvas.drawColor(Color.BLACK);
                            canvas.drawBitmap(frame, null, dst, paint);
                        } finally {
                            inputSurface.unlockCanvasAndPost(canvas);
                        }
                        frames++;
                    } finally {
                        frame.recycle();
                    }
                }
                int[] trackRef = {trackIndex};
                boolean[] startedRef = {muxerStarted};
                drain(codec, muxer, info, trackRef, startedRef, false);
                trackIndex = trackRef[0];
                muxerStarted = startedRef[0];

                long sleepMs = frameIntervalMs - (System.currentTimeMillis() - tickStart);
                if (sleepMs > 0 && !stopRequested.get()) {
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }

            try {
                codec.signalEndOfInputStream();
            } catch (RuntimeException ignored) {
            }
            int[] trackRef = {trackIndex};
            boolean[] startedRef = {muxerStarted};
            drain(codec, muxer, info, trackRef, startedRef, true);
            muxerStarted = startedRef[0];

            state = "stopped";
        } catch (Throwable t) {
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
            state = "failed";
        } finally {
            endedAt = System.currentTimeMillis();
            if (codec != null) {
                try { codec.stop(); } catch (Throwable ignored) {}
                try { codec.release(); } catch (Throwable ignored) {}
            }
            if (inputSurface != null) {
                try { inputSurface.release(); } catch (Throwable ignored) {}
            }
            if (muxer != null) {
                if (muxerStarted) {
                    try { muxer.stop(); } catch (Throwable ignored) {}
                }
                try { muxer.release(); } catch (Throwable ignored) {}
            }
            File f = new File(path);
            if (f.isFile()) bytes = f.length();
            if ("stopped".equals(state) && bytes <= 0) {
                state = "failed";
                error = "No video frames were captured.";
            }
        }
    }

    private static void drain(MediaCodec codec, MediaMuxer muxer, MediaCodec.BufferInfo info,
                              int[] trackIndex, boolean[] muxerStarted, boolean endOfStream) {
        long deadline = System.currentTimeMillis() + (endOfStream ? 2_500L : 50L);
        while (System.currentTimeMillis() < deadline) {
            int idx = codec.dequeueOutputBuffer(info, endOfStream ? 10_000L : 0L);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) break;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (!muxerStarted[0]) {
                    trackIndex[0] = muxer.addTrack(codec.getOutputFormat());
                    muxer.start();
                    muxerStarted[0] = true;
                }
            } else if (idx >= 0) {
                ByteBuffer buf = codec.getOutputBuffer(idx);
                if (buf != null && info.size > 0 && muxerStarted[0]
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    muxer.writeSampleData(trackIndex[0], buf, info);
                }
                codec.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }
}
