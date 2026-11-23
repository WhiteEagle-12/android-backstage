package com.deviceagent.shell;

import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** The real consumer must keep releasing buffers during a stalled display RPC. */
public class VirtualDisplayDrainTest {
    @Test public void drainsWhileDisplayReleaseWaits() throws Exception {
        VirtualDisplayHost host = new VirtualDisplayHost(null);
        Object slot = slot(host);
        VirtualDisplay display = mock(VirtualDisplay.class);
        ImageReader reader = mock(ImageReader.class);
        Image previous = mock(Image.class);
        Image next = mock(Image.class);
        put(slot, "display", display);
        put(slot, "reader", reader);
        put(slot, "latest", previous);
        when(reader.acquireLatestImage()).thenReturn(next);
        CountDownLatch releasing = new CountDownLatch(1);
        CountDownLatch allowRelease = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);
        doAnswer(call -> { drained.countDown(); return null; }).when(previous).close();
        doAnswer(call -> {
            releasing.countDown();
            if (!allowRelease.await(3, TimeUnit.SECONDS)) throw new AssertionError("test release timed out");
            return null;
        }).when(display).release();
        Object controlLock = field(VirtualDisplayHost.class, "lock").get(host);
        Method release = method(slot.getClass(), "release");
        Method onFrame = method(slot.getClass(), "onFrame", ImageReader.class);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            var stopping = workers.submit(() -> {
                synchronized (controlLock) { invoke(release, slot); }
            });
            assertTrue("display release entered", releasing.await(1, TimeUnit.SECONDS));
            var consuming = workers.submit(() -> invoke(onFrame, slot, reader));
            assertTrue("old buffer released while display service is blocked",
                    drained.await(1, TimeUnit.SECONDS));
            consuming.get(1, TimeUnit.SECONDS);
            allowRelease.countDown();
            stopping.get(1, TimeUnit.SECONDS);
            verify(previous, times(1)).close();
            verify(next, times(1)).close();
            verify(reader, times(1)).close();
        } finally {
            allowRelease.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    @Test public void ignoresLateCallbackFromReleasedReader() throws Exception {
        Object slot = slot(new VirtualDisplayHost(null));
        ImageReader old = mock(ImageReader.class);
        ImageReader current = mock(ImageReader.class);
        put(slot, "reader", current);
        invoke(method(slot.getClass(), "onFrame", ImageReader.class), slot, old);
        verifyNoInteractions(old, current);
    }

    /** One display's slot; each private session owns one. */
    private static Object slot(VirtualDisplayHost host) throws Exception {
        Class<?> type = Class.forName(VirtualDisplayHost.class.getName() + "$Slot");
        var ctor = type.getDeclaredConstructor(VirtualDisplayHost.class);
        ctor.setAccessible(true);
        return ctor.newInstance(host);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        Field f = type.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
    private static void put(Object target, String name, Object value) throws Exception {
        field(target.getClass(), name).set(target, value);
    }
    private static Method method(Class<?> type, String name, Class<?>... args) throws Exception {
        Method m = type.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }
    private static void invoke(Method m, Object host, Object... args) {
        try { m.invoke(host, args); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
}
