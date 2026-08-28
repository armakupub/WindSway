package pzmod.windsway;

import zombie.core.Core;

// Tree shader detail: 2 high (every field), 1 medium (second lobe octave,
// leaf mask and shade off), 0 low (crown bend only). Once per session the
// tree pass is timed against the quad area it shaded and logged; ns per
// pixel is a property of the card, not of the scene, and reads a report's
// console.txt. Nothing is switched on that number.
final class TreeDetail {

    static final int LOW = 0;
    static final int MEDIUM = 1;
    static final int HIGH = 2;

    static volatile int active = HIGH;

    // RTX 5070 Ti at 1440p, storm forest at 250 %, high: 0.065 ns/px.
    private static final double REF_NS_PER_PX = 0.065;

    private static final long WARM_NS = 2_000_000_000L;
    private static final long MEASURE_NS = 1_000_000_000L;
    private static final long TIMEOUT_NS = 120_000_000_000L;
    private static final double MIN_AREA = 40.0e6;
    private static final int MIN_SAMPLES = 30;
    private static final double LATE_AREA = 5.0e6;
    private static final int LATE_SAMPLES = 10;

    private static final int PENDING = 0;
    private static final int WARMING = 1;
    private static final int MEASURING = 2;
    private static final int DONE = 3;

    // Render thread only.
    private static int state = PENDING;
    private static long t0;
    private static long t1;

    private TreeDetail() {
    }

    // Any thread.
    static void set(int level) {
        int tier = Math.max(LOW, Math.min(HIGH, level));
        active = tier;
        TreeRenderer.qualLobes = tier >= MEDIUM;
        TreeRenderer.qualLeaves = tier >= MEDIUM;
        TreeRenderer.qualOctave2 = tier == HIGH;
        TreeRenderer.qualMask = tier == HIGH;
        TreeRenderer.qualShade = tier == HIGH;
    }

    // Render thread, before a list draw: the area to time the list with,
    // 0 outside the measuring window.
    static double timingArea(GpuTimer timer, double area) {
        if (state == DONE) return 0.0;
        long now = System.nanoTime();
        if (state == PENDING) {
            if (!GpuTimer.supported()) {
                state = DONE;
                return 0.0;
            }
            state = WARMING;
            t0 = now;
            return 0.0;
        }
        if (state == WARMING) {
            if (now - t0 < WARM_NS) return 0.0;
            state = MEASURING;
            t1 = now;
            timer.calReset();
        }
        return area;
    }

    // Render thread, after the draw.
    static void update(GpuTimer timer) {
        if (state != MEASURING) return;
        long now = System.nanoTime();
        boolean enough = timer.calArea >= MIN_AREA && timer.calSamples >= MIN_SAMPLES && now - t1 >= MEASURE_NS;
        boolean late = now - t0 > TIMEOUT_NS;
        if (!enough && !late) return;
        state = DONE;
        if (!enough && (timer.calArea < LATE_AREA || timer.calSamples < LATE_SAMPLES)) {
            WindSwayMod.trace("tree pass: too few trees to time");
            return;
        }
        double nsPerPx = timer.calNs / timer.calArea;
        double res = (double) Core.width * Core.height / (2560.0 * 1440.0);
        WindSwayMod.trace(String.format("tree pass: %.3f ns/px, %.1fx reference at %dx%d, %d lists, detail %s",
                nsPerPx, nsPerPx / REF_NS_PER_PX * res, Core.width, Core.height, timer.calSamples, name(active)));
    }

    static String name(int tier) {
        return tier == HIGH ? "high" : tier == MEDIUM ? "medium" : "low";
    }
}
