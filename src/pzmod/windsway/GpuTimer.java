package pzmod.windsway;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.EXTTimerQuery;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;

// GL_TIME_ELAPSED around one draw path; only one query may be active at a
// time (the two paths never nest). Read back a frame or more later.
final class GpuTimer {

    private static final int TIME_ELAPSED = 0x88BF;
    private static final int RING = 1024;

    private static int support = -1;
    private static int variant;

    private final int[] ids = new int[RING];
    private final double[] areas = new double[RING];
    private int head;
    private int tail;
    private int outstanding;
    private boolean active;

    final AtomicLong totalNs = new AtomicLong();
    final AtomicLong maxNs = new AtomicLong();
    final AtomicInteger samples = new AtomicInteger();
    final AtomicInteger dropped = new AtomicInteger();

    // Calibration sums, render thread only; a sample counts when it was
    // begun with an area.
    double calNs;
    double calArea;
    int calSamples;

    static boolean supported() {
        if (support < 0) {
            GLCapabilities caps = Display.capabilities;
            if (caps != null && caps.OpenGL33) {
                support = 1;
                variant = 0;
            } else if (caps != null && caps.GL_ARB_timer_query) {
                support = 1;
                variant = 1;
            } else if (caps != null && caps.GL_EXT_timer_query) {
                support = 1;
                variant = 2;
            } else {
                support = 0;
            }
        }
        return support == 1;
    }

    // Off: the availability poll is a glGet, which syncs with a threaded driver.
    static volatile boolean enabled = true;

    boolean begin() {
        return begin(0.0);
    }

    boolean begin(double area) {
        if (!enabled || !supported()) return false;
        drain();
        if (outstanding == RING) {
            dropped.incrementAndGet();
            return false;
        }
        int id = ids[head];
        if (id == 0) {
            id = GL15.glGenQueries();
            ids[head] = id;
        }
        areas[head] = area;
        GL15.glBeginQuery(TIME_ELAPSED, id);
        active = true;
        return true;
    }

    void end() {
        if (!active) return;
        GL15.glEndQuery(TIME_ELAPSED);
        active = false;
        head = (head + 1) % RING;
        outstanding++;
    }

    void calReset() {
        calNs = 0.0;
        calArea = 0.0;
        calSamples = 0;
    }

    void drain() {
        while (outstanding > 0) {
            int id = ids[tail];
            if (GL15.glGetQueryObjecti(id, GL15.GL_QUERY_RESULT_AVAILABLE) == 0) break;
            long ns;
            if (variant == 0) ns = GL33.glGetQueryObjectui64(id, GL15.GL_QUERY_RESULT);
            else if (variant == 1) ns = ARBTimerQuery.glGetQueryObjectui64(id, GL15.GL_QUERY_RESULT);
            else ns = EXTTimerQuery.glGetQueryObjectui64EXT(id, GL15.GL_QUERY_RESULT);
            totalNs.addAndGet(ns);
            samples.incrementAndGet();
            long max = maxNs.get();
            while (ns > max && !maxNs.compareAndSet(max, ns)) {
                max = maxNs.get();
            }
            double area = areas[tail];
            if (area > 0.0) {
                calNs += ns;
                calArea += area;
                calSamples++;
            }
            tail = (tail + 1) % RING;
            outstanding--;
        }
    }

    // Game thread: totals since the last call.
    String report(int frames) {
        long ns = totalNs.getAndSet(0L);
        long max = maxNs.getAndSet(0L);
        int n = samples.getAndSet(0);
        int drop = dropped.getAndSet(0);
        if (support == 0) return "n/a";
        double f = Math.max(1, frames);
        return String.format("%.3f ms/frame (%.1f/frame, max %.3f ms%s)",
                ns / 1.0e6 / f, n / f, max / 1.0e6, drop > 0 ? ", dropped " + drop : "");
    }
}
