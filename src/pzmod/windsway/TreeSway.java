package pzmod.windsway;

import java.util.IdentityHashMap;

import me.zed_0xff.zombie_buddy.Accessor;

import zombie.GameTime;
import zombie.iso.objects.ObjectRenderEffects;

// Tree wind and sway. Wind is one number, w = slider + (1 - slider) *
// windTickFinal, so the slider is a true floor and vanilla's slow wind
// noise only adds. Sway is stateless, evaluated from (clocks, position):
// per-tree phase, period and amplitude from a position hash, plus one
// gust wave travelling in the lean direction. Every wind-dependent rate
// is integrated into a clock; rate * absolute time would turn each wind
// change (and vanilla's constant wind noise) into a phase jump growing
// with the session. The vanilla pools (bushes, plants) get the same
// motion per pool index.
public final class TreeSway {

    // Crown top amplitude in ORE units at w = 1 and response exponent.
    public static volatile double ampMax = 0.06;
    public static volatile double ampPow = 0.6;
    // Bushes (the shared tree pools; trees on the batch path strip the
    // pool share in scaleTreeOre): fraction of sprite width at w = 1,
    // exponent, period.
    public static volatile double bushAmpMax = 0.3;
    public static volatile double bushAmpPow = 0.6;
    public static volatile double bushPeriod = 1.8;
    // Plant pools on the plants channel, stiffness per authored wind type
    // instead of vanilla's hard thresholds (0.2 / 0.6) that froze types 2
    // and 3.
    public static volatile double plantAmpMax = 0.35;
    public static volatile double plantAmpPow = 0.7;
    public static volatile double plantPeriod = 1.4;
    public static volatile double plantStiff2 = 0.6;
    public static volatile double plantStiff3 = 0.35;
    // Storm accent, smoothstep from stormOnset to 1: amplitude gain and a
    // lifted low point so a storm crown swings around a bent pose.
    public static volatile double stormOnset = 0.5;
    public static volatile double stormGain = 0.5;
    public static volatile double stormHold = 0.4;
    public static volatile double periodBase = 2.8;
    public static volatile double periodSpread = 0.2;
    public static volatile double stormSpeedup = 0.15;
    // Gust wave: height relative to the amplitude, wavelength in tiles,
    // speed in tiles/s at w = 0 and its growth with w.
    public static volatile double gustGain = 0.5;
    public static volatile double gustLength = 30.0;
    public static volatile double gustSpeed = 3.0;
    public static volatile double gustSpeedWind = 5.0;
    // Lean direction follows the sign of vanilla's wind angle, eased.
    public static volatile double dirSmooth = 4.0;
    public static volatile double storm = 1.0;
    // Two tempos, calm at w <= 0.1, storm at w = 1: swayTempo for lean,
    // breathing, gust wave and direction easing; tempo for the shader
    // layers. timeScale multiplies both.
    public static volatile double swayTempoCalm = 0.6;
    public static volatile double swayTempoStorm = 1.15;
    public static volatile double tempoCalm = 0.3;
    public static volatile double tempoStorm = 1.0;
    public static volatile double timeScale = 1.0;
    // Fast-forward and sleep push getTimeDelta far past a frame.
    private static final double MAX_DT = 0.1;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double SQRT2 = Math.sqrt(2.0);

    // Frame globals, written on the game thread once per frame, read on
    // the render thread. time and swayClock in seconds at the sway tempo
    // (swayClock with the storm speedup on top), branchClock and leafClock
    // in cycles at the current lobe and leaf frequency.
    public static volatile double time = 0.0;
    public static volatile double swayClock = 0.0;
    public static volatile double branchClock = 0.0;
    public static volatile double leafClock = 0.0;
    public static volatile double w = 0.0;
    public static volatile double wPlant = 0.0;
    public static volatile double raw = 0.0;
    public static volatile double dir = 1.0;
    public static volatile double wavePhase = 0.0;
    public static volatile double lastX = 0.0;

    private static boolean ok = true;
    // Pool -> type * 15 + index per family; plant pool [0][0] is the first
    // pool updateStatic touches each frame and advances the frame globals.
    private static IdentityHashMap<ObjectRenderEffects, Integer> pools;
    private static IdentityHashMap<ObjectRenderEffects, Integer> plantPools;
    private static final IdentityHashMap<ObjectRenderEffects, Boolean> foreign = new IdentityHashMap<>(16);
    private static ObjectRenderEffects clockPool;
    private static int rebuilds;

    private TreeSway() {
    }

    static boolean isTreePool(ObjectRenderEffects ore) {
        IdentityHashMap<ObjectRenderEffects, Integer> m = pools;
        return m != null && m.containsKey(ore);
    }

    private static IdentityHashMap<ObjectRenderEffects, Integer> mapPools(String field) throws Exception {
        java.lang.reflect.Field f = Accessor.findField(ObjectRenderEffects.class, field);
        if (f == null) throw new NoSuchFieldException(field);
        f.setAccessible(true);
        ObjectRenderEffects[][] arr = (ObjectRenderEffects[][]) f.get(null);
        IdentityHashMap<ObjectRenderEffects, Integer> map = new IdentityHashMap<>(64);
        for (int t = 0; t < arr.length && t < 3; ++t) {
            for (int i = 0; i < arr[t].length; ++i) {
                map.put(arr[t][i], t * 15 + i);
            }
        }
        if (field.equals("WIND_EFFECTS")) clockPool = arr[0][0];
        return map;
    }

    private static void init() throws Exception {
        pools = mapPools("WIND_EFFECTS_TREES");
        plantPools = mapPools("WIND_EFFECTS");
        foreign.clear();
    }

    private static float hash(int seed, int salt) {
        int h = (seed + salt * 0x27d4eb2d) * 0x9E3779B1;
        h ^= h >>> 15;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        return ((h >>> 8) & 0xFFFF) / 65535.0f;
    }

    // ~90x per frame from woven advice; must not throw. True = pool
    // written, vanilla update skipped.
    public static boolean update(ObjectRenderEffects pool, float angle) {
        if (!WindSwayMod.enabled || !ok) return false;
        try {
            if (pools == null) init();
            boolean plant = false;
            Integer idx = pools.get(pool);
            if (idx == null) {
                idx = plantPools.get(pool);
                plant = idx != null;
            }
            if (idx == null) {
                // ObjectRenderEffects.init() replaces every pool object on
                // world load, so an unknown pool means the maps are stale.
                if (foreign.containsKey(pool)) return false;
                init();
                idx = pools.get(pool);
                if (idx == null) {
                    idx = plantPools.get(pool);
                    plant = idx != null;
                }
                if (idx == null) {
                    foreign.put(pool, Boolean.TRUE);
                    return false;
                }
                if (++rebuilds <= 3) {
                    System.out.println("[WindSway] wind pools re-created by the game, state rebuilt");
                }
            }

            if (pool == clockPool) {
                double dt = GameTime.getInstance().getTimeDelta();
                if (dt > MAX_DT) dt = MAX_DT;
                if (dt < 0.0) dt = 0.0;
                dt *= timeScale;
                double floor = WindSwayMod.treeWindFloor;
                double r = WindSwayMod.rawWindTick();
                raw = r;
                double wind = floor + (1.0 - floor) * r;
                if (wind > 1.0) wind = 1.0;
                if (wind < 0.0) wind = 0.0;
                w = wind;
                double pf = WindSwayMod.windFloor;
                double wp = pf + (1.0 - pf) * r;
                wPlant = wp > 1.0 ? 1.0 : (wp < 0.0 ? 0.0 : wp);
                double tw = (wind - 0.1) / 0.9;
                if (tw < 0.0) tw = 0.0;
                double dtSway = dt * (swayTempoCalm + (swayTempoStorm - swayTempoCalm) * tw);
                double dtFx = dt * (tempoCalm + (tempoStorm - tempoCalm) * tw);
                time = wrap(time + dtSway);
                double speedup = 1.0 - stormSpeedup * wind;
                swayClock = wrap(swayClock + dtSway / (speedup < 0.05 ? 0.05 : speedup));
                branchClock = wrap(branchClock
                        + (TreeRenderer.branchHz + (TreeRenderer.branchHzStorm - TreeRenderer.branchHz) * wind) * dtFx);
                leafClock = wrap(leafClock
                        + (TreeRenderer.leafHz + (TreeRenderer.leafHzStorm - TreeRenderer.leafHz) * wind) * dtFx);
                double ds = dirSmooth;
                dir += (angle - dir) * (ds > 0.0 ? Math.min(1.0, dtSway / ds) : 1.0);
                wavePhase += (gustSpeed + gustSpeedWind * wind) * dtSway;
            }

            int i = idx;
            double x = plant ? plantLean(i) : poolLean(i);
            pool.x1 = x;
            pool.x2 = x;
            pool.y1 = 0.0;
            pool.y2 = 0.0;
            pool.x3 = 0.0;
            pool.y3 = 0.0;
            pool.x4 = 0.0;
            pool.y4 = 0.0;
            if (i == 0 && !plant) lastX = x;
            return true;
        } catch (Throwable t) {
            ok = false;
            System.out.println("[WindSway] tree sway model disabled, trees follow vanilla: " + t);
            return false;
        }
    }

    static double accent(double wind) {
        double t = (wind - stormOnset) / (1.0 - stormOnset);
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    static double amplitude(double wind) {
        return wind <= 0.0 ? 0.0 : ampMax * Math.pow(wind, ampPow) * (1.0 + stormGain * accent(wind));
    }

    static double bushAmplitude(double wind) {
        return wind <= 0.0 ? 0.0 : bushAmpMax * Math.pow(wind, bushAmpPow) * (1.0 + stormGain * accent(wind));
    }

    // Swing shape in 0..1 with the storm hold applied.
    private static double swing(double wind, double ts, double t, double period, float p1, float p2, float p3, float p4) {
        double hold = stormHold * accent(wind);
        return hold + (1.0 - hold) * base(ts, t, period, p1, p2, p3, p4);
    }

    private static double wrap(double t) {
        return t > 65536.0 ? t - 65536.0 : t;
    }

    // Two sines between upright and full lean, breathing in amplitude.
    private static double base(double ts, double t, double period, float p1, float p2, float p3, float p4) {
        double c1 = ts / period + p1;
        double c2 = ts / (period * 1.83) + p2;
        double s = 0.55 * Math.sin(TWO_PI * c1) + 0.45 * Math.sin(TWO_PI * c2);
        double breathe = 0.75 + 0.25 * Math.sin(TWO_PI * (t / (9.0 + 6.0 * p3) + p4));
        return breathe * (0.5 + 0.5 * s);
    }

    private static double poolLean(int i) {
        double wind = w;
        double period = bushPeriod * (1.0 - periodSpread + 2.0 * periodSpread * hash(i, 4));
        return dir * bushAmplitude(wind) * swing(wind, swayClock, time, period, hash(i, 1), hash(i, 2), hash(i, 3), hash(i, 5));
    }

    private static double plantLean(int i) {
        double wind = wPlant;
        if (wind <= 0.0) return 0.0;
        int type = i / 15;
        double stiff = type == 0 ? 1.0 : (type == 1 ? plantStiff2 : plantStiff3);
        double amp = plantAmpMax * Math.pow(wind, plantAmpPow) * (1.0 + stormGain * accent(wind)) * stiff;
        double period = plantPeriod * (1.0 - periodSpread + 2.0 * periodSpread * hash(i, 4)) / (0.7 + 0.3 * stiff);
        return dir * amp * swing(wind, swayClock, time, period, hash(i + 64, 1), hash(i + 64, 2), hash(i + 64, 3), hash(i + 64, 5));
    }

    // Render thread. Lean in ORE units; sizeF 1 regular, 3/5/7 jumbo; h1..h6
    // position hash values in 0..1.
    static double lean(float tx, float ty, float sizeF, float h1, float h2, float h3, float h4, float h5, float h6) {
        double wind = w;
        if (wind <= 0.0) return 0.0;
        double d = dir;
        double period = periodBase * (1.0 + 0.12 * (sizeF - 1.0f))
                * (1.0 - periodSpread + 2.0 * periodSpread * h4);
        double b = swing(wind, swayClock, time, period, h1, h2, h5, h6);
        // Along-screen position in tiles, so the wave always travels the
        // way the crowns lean; a little per-tree offset frays the front.
        // Sign only: d also scales the lean, so the flip lands where the
        // lean is zero. The eased value would sweep s by 2|tx-ty|/sqrt2
        // tiles and race the wave through every crown for many seconds.
        double s = (d < 0.0 ? -1.0 : 1.0) * (tx - ty) / SQRT2;
        double phase = (s - wavePhase) / gustLength + 0.15 * (h3 - 0.5);
        double bump = Math.sin(TWO_PI * phase);
        bump = bump > 0.0 ? bump * bump * bump : 0.0;
        return d * amplitude(wind) * (b + gustGain * bump);
    }
}
