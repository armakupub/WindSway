package pzmod.windsway;

import java.util.IdentityHashMap;

import me.zed_0xff.zombie_buddy.Accessor;

import zombie.GameTime;
import zombie.iso.objects.ObjectRenderEffects;

// Tree wind and sway, stateless: every value comes from (clocks, position).
// w = max(slider, windTickFinal); each tree sees a local wind g from hash
// noise, leans through a per-tree response and swings on dg/dt. Tree clocks
// run on real seconds, the vanilla pools on the game clock.
public final class TreeSway {

    public static volatile double ampMax = 0.06;
    public static volatile double ampPow = 2.2;
    public static volatile double ampFloor = 0.62;
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
    public static volatile double plantBendPow = 1.5;
    public static volatile double plantShorten = 0.5;
    public static volatile double plantBladeCell = 24.0;
    public static volatile double plantBladeVar = 0.5;
    public static volatile double plantMean = 0.5;
    // Barrier cap R: toward a fence or wall the lean amplitude A becomes
    // A * R / (R + A), so a bent blade barely reaches the element beside it.
    public static volatile double plantBarrierCap = 16.0;
    // Storm accent, smoothstep from stormOnset to 1: amplitude gain and a
    // lifted low point so a storm crown swings around a bent pose.
    public static volatile double stormOnset = 0.5;
    public static volatile double stormGain = 0.5;
    public static volatile double stormHold = 0.4;
    public static volatile double periodBase = 2.8;
    public static volatile double periodSpread = 0.2;
    public static volatile double stormSpeedup = 0.15;
    // Local wind: mix weights sum to 1 (mean 0.5), contrast around the mean.
    public static volatile double turbLen1 = 28.0;
    public static volatile double turbLen2 = 10.0;
    public static volatile double turbLocalRate = 0.12;
    public static volatile double turbMix1 = 0.5;
    public static volatile double turbMix2 = 0.2;
    public static volatile double turbMixLocal = 0.3;
    public static volatile double turbContrast = 1.3;
    public static volatile double frontSpeed = 1.2;
    public static volatile double frontSpeedWind = 4.0;
    public static volatile double sensSpread = 0.35;
    public static volatile double thresholdMax = 0.25;
    // Response curve: above 1 the crown mostly rests, below 1 it stays near
    // the largest lean.
    public static volatile double responseCurve = 1.4;
    public static volatile double responseCurveStorm = 0.8;
    public static volatile double leanSmooth = 0.5;
    // The wind sets the largest lean; the crown moves between rest and it,
    // never held. meanLean adds a standing lean.
    public static volatile double meanLean = 0.0;
    // Swing: a lagged second sample lets it outlast the change.
    public static volatile double ringGain = 0.5;
    public static volatile double ringRate = 0.8;
    public static volatile double ringKnee = 0.05;
    public static volatile double ringMemory = 0.6;
    public static volatile double ringLag = 2.0;
    // ringWind * w * g keeps a storm crown swinging; ringRest = swing share
    // at rest.
    public static volatile double ringWind = 1.5;
    public static volatile double ringRest = 0.4;
    public static volatile double ringFast = 0.2;
    // Upwind cap: a crown swinging far past upright reads as the wind turning.
    public static volatile double upwindCap = 0.15;
    public static volatile double lobeHz = 1.0;
    public static volatile double maskRate = 0.15;
    // Lean direction follows the sign of vanilla's wind angle, eased.
    public static volatile double dirSmooth = 4.0;
    public static volatile double storm = 1.0;
    public static volatile double swayTempoCalm = 0.6;
    public static volatile double swayTempoStorm = 1.15;
    public static volatile double timeScale = 1.0;
    // Fast-forward and sleep push getTimeDelta far past a frame.
    private static final double MAX_DT = 0.1;
    private static final double TWO_PI = 2.0 * Math.PI;
    private static final double SQRT2 = Math.sqrt(2.0);

    // Frame globals: game thread writes once per frame, render thread reads.
    // Clocks in seconds (time, swayClock, treeTime, ringClock) or cycles.
    public static volatile double time = 0.0;
    public static volatile double swayClock = 0.0;
    public static volatile double treeTime = 0.0;
    // Period-divided absolute time turned every wind drift into a phase run.
    public static volatile double ringClock = 0.0;
    public static volatile double advect = 0.0;
    public static volatile double branchClock = 0.0;
    public static volatile double leafClock = 0.0;
    public static volatile double maskClock = 0.0;
    public static volatile double w = 0.0;
    public static volatile double wPlant = 0.0;
    public static volatile double plantGateStart = 0.8;
    public static volatile double plantGateSlope = 0.25;
    public static volatile double raw = 0.0;
    public static volatile double dir = 1.0;
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

    static float hash(int seed, int salt) {
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
                GameTime gt = GameTime.getInstance();
                double dt = gt.getTimeDelta();
                if (dt > MAX_DT) dt = MAX_DT;
                if (dt < 0.0) dt = 0.0;
                dt *= timeScale;
                double dtReal = gt.getRealworldSecondsSinceLastUpdate();
                if (dtReal > MAX_DT) dtReal = MAX_DT;
                if (dtReal < 0.0) dtReal = 0.0;
                dtReal *= timeScale;
                double floor = WindSwayMod.treeWindFloor;
                double r = WindSwayMod.rawWindTick();
                raw = r;
                double wind = Math.max(floor, r);
                if (wind > 1.0) wind = 1.0;
                if (wind < 0.0) wind = 0.0;
                w = wind;
                double pf = WindSwayMod.windFloor;
                double wp = Math.max(pf, r);
                if (wp > 1.0) wp = 1.0;
                if (wp < 0.0) wp = 0.0;
                if (wp > plantGateStart) {
                    wp = plantGateStart + (wp - plantGateStart) * plantGateSlope;
                }
                wPlant = wp;
                double tw = (wind - 0.1) / 0.9;
                if (tw < 0.0) tw = 0.0;
                double dtSway = dt * (swayTempoCalm + (swayTempoStorm - swayTempoCalm) * tw);
                time = wrap(time + dtSway);
                double speedup = 1.0 - stormSpeedup * wind;
                swayClock = wrap(swayClock + dtSway / (speedup < 0.05 ? 0.05 : speedup));
                double ds = dirSmooth;
                dir += (angle - dir) * (ds > 0.0 ? Math.min(1.0, dtSway / ds) : 1.0);
                treeTime += dtReal;
                double ringSpeed = 1.0 - stormSpeedup * wind;
                ringClock += dtReal / (ringSpeed < 0.05 ? 0.05 : ringSpeed);
                advect += (dir < 0.0 ? -1.0 : 1.0) * (frontSpeed + frontSpeedWind * wind) * dtReal;
                branchClock = wrap(branchClock + lobeHz * dtReal);
                leafClock = wrap(leafClock
                        + (TreeRenderer.leafHz + (TreeRenderer.leafHzStorm - TreeRenderer.leafHz) * wind) * dtReal);
                maskClock = wrap(maskClock + maskRate * dtReal);
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
        if (wind <= 0.0) return 0.0;
        double s = wind / 0.08;
        if (s > 1.0) s = 1.0;
        s = s * s * (3.0 - 2.0 * s);
        double f = ampFloor;
        return ampMax * (f * s + (1.0 - f) * Math.pow(wind, ampPow)) * (1.0 + stormGain * accent(wind));
    }

    static double bushAmplitude(double wind) {
        return wind <= 0.0 ? 0.0 : bushAmpMax * Math.pow(wind, bushAmpPow) * (1.0 + stormGain * accent(wind));
    }

    // Shader plants: amplitude at the plants' wind and the reach per side;
    // the shader caps the lean softly at 1.3 downwind and at the upwind cap.
    static double plantCurve(double wind) {
        return wind <= 0.0 ? 0.0 : Math.pow(wind, plantAmpPow) * (1.0 + stormGain * accent(wind));
    }

    static float plantReach(float ampPx, boolean downwind) {
        double wind = wPlant;
        double cap = downwind ? 1.3 : Math.max(0.0, upwindCap);
        return (float) (ampPx * Math.abs(dir) * plantCurve(wind) * cap * (1.0 + 0.5 * plantBladeVar)) + 2.0f;
    }

    // Render thread, per batch: the nine windsway_grass uniforms (layout
    // contract).
    static final int PLANT_UNIFORMS = 36;

    static void fillPlantUniforms(float[] u) {
        double wind = wPlant;
        double wt = w;
        u[0] = (float) wind;
        u[1] = (float) plantCurve(wind);
        u[2] = (float) dir;
        u[3] = (float) advect;
        u[4] = (float) treeTime;
        u[5] = (float) ringClock;
        u[6] = (float) (frontSpeed + frontSpeedWind * wt);
        u[7] = (float) plantMean;
        u[8] = (float) turbLen1;
        u[9] = (float) turbLen2;
        u[10] = (float) turbLocalRate;
        u[11] = (float) turbContrast;
        u[12] = (float) turbMix1;
        u[13] = (float) turbMix2;
        u[14] = (float) turbMixLocal;
        u[15] = 0.0f;
        u[16] = (float) sensSpread;
        u[17] = (float) thresholdMax;
        u[18] = (float) responseCurve;
        u[19] = (float) responseCurveStorm;
        u[20] = (float) ringGain;
        u[21] = (float) ringRate;
        u[22] = (float) ringKnee;
        u[23] = (float) ringWind;
        u[24] = (float) ringRest;
        u[25] = (float) ringFast;
        u[26] = (float) upwindCap;
        u[27] = (float) periodSpread;
        u[28] = (float) plantBendPow;
        u[29] = (float) plantShorten;
        u[30] = (float) plantBladeCell;
        u[31] = (float) plantBladeVar;
        u[32] = (float) plantBarrierCap;
        u[33] = WindSwayMod.plantBendOn ? 1.0f : 0.0f;
        u[34] = (float) (1.0 / Math.max(plantBladeCell, 1.0));
        u[35] = 0.0f;
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

    // Render thread, per list: snapshot of the clocks and wind terms.
    private static double sW;
    private static double sAmp;
    private static double sTreeTime;
    private static double sRingClock;
    private static double sAdvect;
    private static double sAdvSign;
    private static double sAdvSpeed;
    private static double sDir;
    // Per tree after lean(): local wind, change energy, swing energy.
    static double localWind;
    static double localEnergyRaw;
    static double localEnergy;

    static void prepareList() {
        double wind = w;
        sW = wind;
        sAmp = amplitude(wind);
        sTreeTime = treeTime;
        sRingClock = ringClock;
        sAdvect = advect;
        sDir = dir;
        sAdvSign = sDir < 0.0 ? -1.0 : 1.0;
        sAdvSpeed = frontSpeed + frontSpeedWind * wind;
    }

    static double listWind() {
        return sW;
    }

    // 1D value noise, value and d/dx.
    private static double nv;
    private static double nd;

    private static void noise(double x, int salt) {
        double fl = Math.floor(x);
        int i = (int) fl;
        double f = x - fl;
        double a = hash(i, salt);
        double b = hash(i + 1, salt);
        nv = a + (b - a) * f * f * (3.0 - 2.0 * f);
        nd = (b - a) * 6.0 * f * (1.0 - f);
    }

    // Local wind g and dg/dt at s along the lean axis, lag seconds ago.
    private static double gv;
    private static double gd;

    private static void turbulence(double s, double phase, double rate, double lag) {
        double adv = sAdvect - sAdvSign * sAdvSpeed * lag;
        double t = sTreeTime - lag;
        double m1 = turbMix1;
        double m2 = turbMix2;
        double m3 = turbMixLocal;
        double l1 = turbLen1;
        double l2 = turbLen2;
        noise((s - adv) / l1, 11);
        double v = m1 * nv;
        double d = -m1 * nd * sAdvSign * sAdvSpeed / l1;
        noise((s - adv) / l2 + 7.7, 23);
        v += m2 * nv;
        d += -m2 * nd * sAdvSign * sAdvSpeed / l2;
        noise(t * rate + phase, 37);
        v += m3 * nv;
        d += m3 * nd * rate;
        // Smoothstep contrast keeps the derivative continuous (a clip kinks the
        // swing energy).
        double c = turbContrast;
        double u = (v - 0.5) * c + 0.5;
        if (u <= 0.0) {
            gv = 0.0;
            gd = 0.0;
        } else if (u >= 1.0) {
            gv = 1.0;
            gd = 0.0;
        } else {
            gv = u * u * (3.0 - 2.0 * u);
            gd = d * c * 6.0 * u * (1.0 - u);
        }
    }

    // Render thread, after prepareList(). Lean in ORE units; sizeF 1 regular,
    // 3/5/7 jumbo.
    static double lean(float tx, float ty, float sizeF, int seed) {
        double wind = sW;
        if (wind <= 0.0) {
            localWind = 0.0;
            localEnergyRaw = 0.0;
            localEnergy = 0.0;
            return 0.0;
        }
        double hSens = hash(seed, 11);
        double hThr = hash(seed, 12);
        double hRate = hash(seed, 13);
        double hPhase = hash(seed, 14);
        double hPeriod = hash(seed, 15);
        double hRing = hash(seed, 16);
        double hFray = hash(seed, 17);
        // Along-screen position, so the fronts travel the way the crowns lean; a
        // per-tree offset frays the front.
        double s = (tx - ty) / SQRT2 + 0.6 * turbLen2 * (hFray - 0.5);
        double rate = turbLocalRate * (0.7 + 0.6 * hRate);
        double phase = hPhase * 4096.0;
        turbulence(s, phase, rate, 0.0);
        double g0 = gv;
        double d0 = gd;
        double g1 = g0;
        double d1 = 0.0;
        double lag = ringLag;
        if (lag > 0.0) {
            turbulence(s, phase, rate, lag);
            g1 = gv;
            d1 = gd;
        }
        // Leaves and lobes see the wind as it is; the crown has inertia.
        localWind = g0;
        double g = g0 + (g1 - g0) * leanSmooth;
        // Soft knee x^2 / (x + k): small wobbles quenched without a corner.
        double rawEnergy = d0 * d0 + ringMemory * d1 * d1;
        localEnergyRaw = rawEnergy;
        double rr = ringRate;
        double energy = 0.0;
        if (rr > 0.0) {
            double x = rawEnergy / (rr * rr);
            double k = ringKnee;
            double xk = k > 0.0 ? x * x / (x + k) : x;
            energy = 1.0 - Math.exp(-(xk + ringWind * wind * g));
        }
        localEnergy = energy;

        double sens = 1.0 - sensSpread + 2.0 * sensSpread * hSens;
        double thr = thresholdMax * hThr * (1.0 - wind);
        double e = thr < 1.0 ? (g - thr) / (1.0 - thr) : 0.0;
        if (e < 0.0) e = 0.0;
        double curve = responseCurve + (responseCurveStorm - responseCurve) * wind;
        if (curve != 1.0 && e > 0.0) e = Math.pow(e, curve);
        e *= sens;
        if (e > 1.0) e = 1.0;
        // Soft cap at 1.3 of the largest lean (the quad reach assumes it).
        double period = periodBase * (1.0 + 0.12 * (sizeF - 1.0f))
                * (1.0 - periodSpread + 2.0 * periodSpread * hPeriod);
        double rest = ringRest + (1.0 - ringRest) * wind;
        double swing = ringGain * energy * sens * (rest + (1.0 - rest) * e);
        double ph = sRingClock / period;
        double ring = swing * (Math.sin(TWO_PI * (ph + hRing))
                + ringFast * wind * Math.sin(TWO_PI * (ph / 0.55 + 3.7 * hRing)));
        double total = e + ring;
        if (total > 1.0) {
            double o = total - 1.0;
            total = 1.0 + o / (1.0 + o / 0.3);
        } else if (total < 0.0) {
            double cap = upwindCap;
            total = cap > 0.0 ? total / (1.0 - total / cap) : 0.0;
        }
        return sDir * sAmp * (meanLean * wind + total);
    }
}
