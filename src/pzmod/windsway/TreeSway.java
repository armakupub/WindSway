package pzmod.windsway;

import java.util.IdentityHashMap;

import me.zed_0xff.zombie_buddy.Accessor;

import zombie.GameTime;
import zombie.characters.IsoPlayer;
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
    // pool share in scaleTreeOre).
    public static volatile double bushAmpMax = 0.3;
    public static volatile double bushAmpPow = 0.6;
    public static volatile double bushPeriod = 1.8;
    // Plant pools on the plants channel, stiffness per authored wind type
    // instead of vanilla's hard thresholds (0.2 / 0.6) that froze types 2
    // and 3.
    public static volatile double plantAmpMax = 0.35;
    public static volatile double plantAmpPow = 0.8;
    public static volatile double plantPeriod = 1.1;
    public static volatile double plantStiff2 = 0.6;
    public static volatile double plantStiff3 = 0.35;
    public static volatile double plantBendPow = 2.0;
    public static volatile double plantShorten = 0.5;
    public static volatile double plantBladeCell = 24.0;
    public static volatile double plantBladeVar = 0.5;
    public static volatile double plantMean = 0.55;
    // Barrier cap R: toward a fence or wall the lean amplitude A becomes
    // A * R / (R + A), so a bent blade barely reaches the element beside it.
    public static volatile double plantBarrierCap = 16.0;
    public static volatile double plantLeafAmp = 0.6;
    public static volatile double plantLeafAmpStorm = 1.5;
    public static volatile double plantLeafRefPx = 96.0;
    public static volatile double plantLeafSizePow = 0.5;
    public static volatile double plantLeafDens = 0.5;
    public static volatile double plantLeafGust = 0.35;
    public static volatile double plantLeafRateSpread = 0.15;
    public static volatile double plantLeafShade = 0.03;
    // Period law over the content height: f ~ 1/H for self-similar blades,
    // +-spread per object.
    public static volatile double plantPeriodRefH = 1.2;
    public static volatile double plantPeriodExp = 0.5;
    public static volatile double plantPeriodSpread = 0.2;
    // Ring model (windsway_grass_static.vert, plantModel 1): the swing is
    // the impulse response of a damped oscillator at the object's own
    // period to the local wind's change. Default 0: the standing sine with
    // the energy envelope stays the shipped look.
    public static volatile int plantModel = 0;
    public static volatile double plantDamping = 0.25;
    public static volatile double plantRingGain = 0.6;
    // The ring is gated by the front energy of the slow octaves over its
    // window (1 - exp(-E / rate^2)): a passing front rings the tuft, between
    // fronts it settles. Without the gate a fine octave near f0 is a
    // metronome and the tuft beats against it.
    public static volatile double plantRingGate = 0.3;
    // Share of the ring taken away at w 1: a storm is carried by the lean
    // and the gust pushes, not by the ringing.
    public static volatile double plantRingStormFade = 0.1;
    // Plant octave lengths in tiles, shorter than the trees': gusts scale
    // with the vegetation, tree-sized ones read as a steady blower.
    public static volatile double plantTurbLen1 = 12.0;
    public static volatile double plantTurbLen2 = 4.0;
    // Plant swing (model 0): the shorter octaves' derivative is ~2.4x the
    // trees' and threw the swing too often; a storm presses, it does not
    // rock.
    public static volatile double plantSwingGain = 1.0;
    public static volatile double plantSwingRate = 1.0;
    // Lean inertia: the lean follows a mix of the wind now and lagSeconds
    // ago (the trees' leanSmooth); the swing carries the fast part.
    public static volatile double plantLeanLag = 0.5;
    public static volatile double plantLeanShare = 0.3;
    // Light wind must still stir: the plants' fronts run faster than the
    // trees' at calm (factor 1 + calm * (1 - w), own accumulator) and the
    // per-object term breathes at its own rate (trees 0.12 Hz).
    public static volatile double plantFrontCalm = 0.5;
    public static volatile double plantLocalRate = 0.25;
    // And faster than the trees' at storm too (factor 1 + storm * w): the
    // pauses between gusts at w 1 felt too long.
    public static volatile double plantFrontStorm = 0.5;
    // Turbulence at storm: the per-object rate grows by localRateStorm * w,
    // and a crosswind octave (noise across the lean axis, drifting in
    // time) lets neighbours across the wind differ, the fronts alone are
    // one-dimensional.
    public static volatile double plantLocalRateStorm = 1.5;
    // Soft cap of the plant lean at 1 + knee of the largest lean (the quad
    // reach follows it).
    public static volatile double plantCapKnee = 0.7;
    public static volatile double plantCrossLen = 6.0;
    public static volatile double plantCrossMix = 0.25;
    public static volatile double plantCrossRate = 0.15;
    // Bend exponent grows with the wind: a blade in a storm is stiff at
    // the base and streams at the tip.
    public static volatile double plantBendPowStorm = 1.0;
    // Flutter: the galloping mode at a whole multiple of the swing rate.
    // Off by default: at 6x the swing rate it reads as hectic.
    public static volatile double plantFlutterAmp = 0.0;
    public static volatile double plantFlutterOnset = 0.35;
    public static volatile double plantFlutterRate = 6.0;
    public static volatile double plantFlutterSpread = 0.5;
    // Honami octave, plants only: the coherent wave over a meadow runs at
    // 2-9 plant heights and 1.4-1.8x the wind at crown height.
    public static volatile double honamiLen = 3.0;
    public static volatile double honamiSpeed = 1.0;
    public static volatile double honamiMix = 0.15;
    // Dry grass (tan and orange sets, dead stalks, dry weeds): 5x the
    // stiffness at a fifth of the mass; factors on period, lean, damping
    // and flutter.
    public static volatile double dryPeriod = 0.5;
    public static volatile double dryLean = 0.7;
    public static volatile double dryDamping = 0.6;
    public static volatile double dryFlutter = 1.5;
    // Blade tip physics and the gust sheen, in windsway_grass.frag.
    public static volatile double plantTipLead = 0.12;
    public static volatile double plantTipLeadPow = 2.0;
    public static volatile double plantTipFast = 1.0;
    public static volatile double plantTipFlick = 0.2;
    public static volatile double plantSheenCalm = 0.03;
    public static volatile double plantSheenStorm = 0.08;
    public static volatile double plantSheenPow = 1.5;
    // Woody body (bush crowns, bare bushes; PlantClass.block): the stems
    // pivot at the foot as straight lines up to the knee, above it the
    // crown rides as one piece with a residual shear of tail; a crown that
    // shears over its whole height reads as rubber. Lobes on the crown
    // parts: the trees' branch lattice on the branch clock, px at calm and
    // at w 1, gated over w onset..full, breathing with the local wind.
    public static volatile double plantBlockKnee = 0.35;
    public static volatile double plantBlockTail = 0.25;
    // Off by default: even at twig scale the lattice warp read as wobble
    // on a bush crown; the leaf layer and the mask patches carry it.
    public static volatile double plantLobeCalm = 0.0;
    public static volatile double plantLobeStorm = 0.0;
    public static volatile double plantLobeCell = 16.0;
    public static volatile double plantLobeY = 0.35;
    public static volatile double plantLobeOnset = 0.12;
    public static volatile double plantLobeFull = 0.45;
    // Leaf look as on the trees, crown parts by class: gated leaf layer,
    // cluster cell for fine paint, drifting mask patches, brightness
    // flicker.
    public static volatile double plantLeafOnset = 0.04;
    public static volatile double plantLeafFull = 0.6;
    // The class steady floors return toward storm (a bush at w 0.8 holds
    // its lean between the gusts), and the plants' turbulence contrast
    // falls with the wind: steep scarce fronts in light air, filled
    // valleys in a storm.
    public static volatile double plantSteadyRampLo = 0.45;
    public static volatile double plantSteadyRampHi = 0.9;
    public static volatile double plantContrastCalm = 1.5;
    public static volatile double plantContrastStorm = 1.0;
    // Breeze: the mod's calm-wind simulation. The floor wanders inside
    // the configured band (two slow noise octaves in real seconds);
    // vanilla weather above the band wins through the max. Ceil <= floor
    // = a static floor, both 0 = vanilla wind.
    public static volatile double breezePeriod = 240.0;
    public static volatile double breezePeriodFine = 60.0;
    public static volatile double breezeFineWeight = 0.3;
    // The wind sound swings around the wind with the gust field at the
    // player: down * w in a lull, up * w in a gust, capped at 1. The wind
    // bed is a step ladder without crossfades (0.4/0.7/0.9), a gust is
    // heard when it crosses a step; the plateau at the cap is the top step.
    public static volatile double soundGustDown = 0.3;
    public static volatile double soundGustUp = 0.3;
    // Weather takes the wind over: the band fades out while a weather
    // period, precipitation or fog runs, back in when the sky clears.
    public static volatile boolean weatherTakeover = true;
    public static volatile double weatherBlend = 20.0;
    private static double weatherGate = 0.0;
    // Random start: the hash is deterministic, a zero start would replay
    // the same breeze every fresh game.
    private static double breezeClock = Math.random() * 1.0e7;
    public static volatile double plantClusterRatePow = 0.35;
    public static volatile double plantMaskStrength = 1.0;
    public static volatile double plantMaskCell = 32.0;
    public static volatile double plantMaskFloor = 0.4;
    public static volatile double plantMaskGustDens = 0.6;
    // Storm end below the trees': fast cell cycles over most of a crown
    // read as a strobe.
    public static volatile double plantFlickAmp = 0.04;
    public static volatile double plantFlickAmpStorm = 0.05;
    public static volatile double plantFlickRate = 1.0;
    public static volatile double plantFlickDuty = 0.55;
    public static volatile double plantFlickCell = 3.0;
    public static volatile double plantFlickDensCalm = 0.05;
    public static volatile double plantFlickDensStorm = 0.5;
    public static volatile double plantFlickWindOnset = 0.1;
    public static volatile double plantFlickDensGust = 0.4;
    public static volatile double plantFlickGustOnset = 0.2;
    public static volatile double plantFlickGustFull = 0.7;
    public static volatile double plantFlickOutside = 0.2;
    // Snow-laden flora (the engine swaps the texture on the same object,
    // isUseSnowSprite; the sprite name stays the green one): factors on the
    // lean, the leaf layer and the steady floors — frozen wood barely
    // moves in light wind, the rest rides the gust fronts.
    public static volatile double plantSnowLean = 0.3;
    public static volatile double plantSnowLeaf = 0.05;
    public static volatile double plantSnowSteady = 0.5;
    // Storm accent, smoothstep from stormOnset to 1: amplitude gain and a
    // lifted low point so a storm crown swings around a bent pose.
    public static volatile double stormOnset = 0.5;
    public static volatile double stormGain = 0.5;
    public static volatile double stormHold = 0.4;
    // Ring period at periodRefH world units of content height, scaling
    // with (height / ref)^TreeClass.periodExp; physical periods are 2-4x
    // faster and read as straws in game.
    public static volatile double periodBase = 2.8;
    public static volatile double periodRefH = 3.5;
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
    public static volatile double advectPlant = 0.0;
    private static volatile double speedPlant = 0.0;
    public static volatile double branchClock = 0.0;
    public static volatile double leafClock = 0.0;
    // Evergreen needles run their own clock: a wind-dependent factor on a
    // per-tree rate times an absolute clock would turn wind drift into a
    // phase run.
    public static volatile double coniferLeafClock = 0.0;
    public static volatile double maskClock = 0.0;
    // The plants' per-object octave rate grows with the wind, so it
    // integrates too: the shader's rate times treeTime turned every wind
    // drift into a phase run that grew with the session.
    public static volatile double plantLocalClock = 0.0;
    public static volatile double w = 0.0;
    public static volatile double wPlant = 0.0;
    public static volatile double plantGateStart = 0.8;
    public static volatile double plantGateSlope = 0.25;
    public static volatile double raw = 0.0;
    public static volatile double dir = 1.0;
    public static volatile double lastX = 0.0;

    private static volatile boolean ok = true;
    // Pool -> type * 15 + index per family; plant pool [0][0] is the first
    // pool updateStatic touches each frame and advances the frame globals.
    private static IdentityHashMap<ObjectRenderEffects, Integer> pools;
    private static IdentityHashMap<ObjectRenderEffects, Integer> plantPools;
    private static final IdentityHashMap<ObjectRenderEffects, Boolean> foreign = new IdentityHashMap<>(16);
    private static ObjectRenderEffects clockPool;
    private static int rebuilds;

    private TreeSway() {
    }

    static void rearm() {
        if (ok) return;
        ok = true;
        WindSwayMod.trace("tree sway model re-armed");
    }

    static boolean isOk() {
        return ok;
    }

    // Game thread, per world. treeTime, advect and advectPlant have no
    // in-session wrap: they drive octaves of incommensurable lengths, so
    // no period is seamless for all of them; as floats they lose their
    // per-frame step after some hours, which a world change re-bases. The
    // pop lands in the loading screen.
    static void resetClocks() {
        time = 0.0;
        swayClock = 0.0;
        treeTime = 0.0;
        ringClock = 0.0;
        advect = 0.0;
        advectPlant = 0.0;
        branchClock = 0.0;
        leafClock = 0.0;
        coniferLeafClock = 0.0;
        maskClock = 0.0;
        plantLocalClock = 0.0;
        breezeClock = Math.random() * 1.0e7;
    }

    static boolean isTreePool(ObjectRenderEffects ore) {
        IdentityHashMap<ObjectRenderEffects, Integer> m = pools;
        return m != null && m.containsKey(ore);
    }

    // Pools per wind type (ObjectRenderEffects.EFFECTS_COUNT); the index is
    // type * stride + pool.
    private static int poolStride = 15;

    private static IdentityHashMap<ObjectRenderEffects, Integer> mapPools(String field) throws Exception {
        java.lang.reflect.Field f = Accessor.findField(ObjectRenderEffects.class, field);
        if (f == null) throw new NoSuchFieldException(field);
        f.setAccessible(true);
        ObjectRenderEffects[][] arr = (ObjectRenderEffects[][]) f.get(null);
        java.lang.reflect.Field count = Accessor.findField(ObjectRenderEffects.class, "EFFECTS_COUNT");
        if (count == null) throw new NoSuchFieldException("EFFECTS_COUNT");
        count.setAccessible(true);
        poolStride = Math.max(1, count.getInt(null));
        IdentityHashMap<ObjectRenderEffects, Integer> map = new IdentityHashMap<>(64);
        for (int t = 0; t < arr.length && t < 3; ++t) {
            for (int i = 0; i < arr[t].length && i < poolStride; ++i) {
                map.put(arr[t][i], t * poolStride + i);
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
        return ((h >>> 8) & 0xFFFF) / 65536.0f;
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
                breezeClock += dtReal;
                double fw = breezeFineWeight;
                double bn = (1.0 - fw) * breezeNoise(breezeClock / Math.max(1.0, breezePeriod), 71)
                        + fw * breezeNoise(breezeClock / Math.max(1.0, breezePeriodFine) + 3.9, 73);
                double gTarget = weatherTakeover && WindSwayMod.weatherActive() ? 1.0 : 0.0;
                double gStep = weatherBlend > 0.0 ? dtReal / weatherBlend : 1.0;
                weatherGate = gTarget > weatherGate ? Math.min(gTarget, weatherGate + gStep)
                        : Math.max(gTarget, weatherGate - gStep);
                double gKeep = 1.0 - weatherGate;
                double floor = breezeAt(WindSwayMod.treeWindFloor, bn) * gKeep;
                WindSwayMod.breezeTree = floor;
                double r = WindSwayMod.rawWindTick();
                raw = r;
                double wind = Math.max(floor, r);
                if (wind > 1.0) wind = 1.0;
                if (wind < 0.0) wind = 0.0;
                w = wind;
                double pf = breezeAt(WindSwayMod.windFloor, bn) * gKeep;
                WindSwayMod.breezePlant = pf;
                double wp = Math.max(pf, r);
                if (wp > 1.0) wp = 1.0;
                if (wp < 0.0) wp = 0.0;
                if (wp > plantGateStart) {
                    wp = plantGateStart + (wp - plantGateStart) * plantGateSlope;
                }
                wPlant = wp;
                plantLocalClock = wrap(plantLocalClock + plantLocalRate * (1.0 + plantLocalRateStorm * wp) * dtReal);
                double tw = (wind - 0.1) / 0.9;
                if (tw < 0.0) tw = 0.0;
                double dtSway = dt * (swayTempoCalm + (swayTempoStorm - swayTempoCalm) * tw);
                time += dtSway;
                double speedup = 1.0 - stormSpeedup * wind;
                swayClock = wrapSway(swayClock + dtSway / (speedup < 0.05 ? 0.05 : speedup));
                double ds = dirSmooth;
                dir += (angle - dir) * (ds > 0.0 ? Math.min(1.0, dtSway / ds) : 1.0);
                treeTime += dtReal;
                double ringSpeed = 1.0 - stormSpeedup * wind;
                ringClock += dtReal / (ringSpeed < 0.05 ? 0.05 : ringSpeed);
                advect += (dir < 0.0 ? -1.0 : 1.0) * (frontSpeed + frontSpeedWind * wind) * dtReal;
                WindSwayMod.gustSound = gustAtPlayer();
                double sp = (frontSpeed + frontSpeedWind * wp) * (1.0 + plantFrontCalm * (1.0 - wp) + plantFrontStorm * wp);
                speedPlant = sp;
                advectPlant += (dir < 0.0 ? -1.0 : 1.0) * sp * dtReal;
                branchClock = wrap(branchClock + lobeHz * dtReal);
                double leafRateNow = TreeRenderer.leafHz + (TreeRenderer.leafHzStorm - TreeRenderer.leafHz) * wind;
                leafClock = wrap(leafClock + leafRateNow * dtReal);
                coniferLeafClock = wrap(coniferLeafClock + leafRateNow
                        * (TreeRenderer.coniferLeafHz + (TreeRenderer.coniferLeafHzStorm - TreeRenderer.coniferLeafHz) * wind) * dtReal);
                maskClock = wrapMask(maskClock + maskRate * dtReal);
                double rk = Math.abs(dir) * plantCurve(wp) * (1.0 + 0.5 * plantBladeVar);
                reachDown = (float) (rk * (1.0 + Math.max(0.05, plantCapKnee)));
                reachUp = (float) (rk * Math.max(0.0, upwindCap));
                reachTip = (float) (Math.max(0.0, plantTipFlick) * plantCurve(wp));
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
            // The clocks stop here; the renderers would keep drawing a
            // frozen pose at full cost. The breeze stops too, the sliders
            // take over as the floor (vanilla's update then sees the plant
            // floor on the tree pools as well, treeWindFloor is out).
            WindSwayMod.breezeTree = -1.0;
            WindSwayMod.breezePlant = -1.0;
            WindSwayMod.gustSound = -1.0;
            TreeRenderer.disable("wind model disabled");
            WindSwayGrassDrawer.fail("wind model disabled");
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

    // Per-frame factors from the clock block; the capture calls this per part.
    private static volatile float reachDown;
    private static volatile float reachUp;
    private static volatile float reachTip;

    static float plantReach(float ampPx, boolean downwind) {
        return ampPx * (downwind ? reachDown : reachUp) + 2.0f;
    }

    // Largest snap lift of the tip in sprite px, for the quad's top pad.
    static float plantTipLift(float ampPx) {
        return ampPx * reachTip;
    }

    // Render thread, per batch: the windsway_grass uniforms (layout
    // contract).
    static final int PLANT_UNIFORMS = 100;

    static void fillPlantUniforms(float[] u) {
        double wind = wPlant;
        double wt = w;
        u[0] = (float) wind;
        u[1] = (float) plantCurve(wind);
        u[2] = (float) dir;
        u[3] = (float) advect;
        u[4] = (float) treeTime;
        u[5] = (float) (ringClock % SWAY_WRAP);
        u[6] = (float) (frontSpeed + frontSpeedWind * wt);
        u[7] = (float) plantMean;
        u[8] = (float) plantSteadyRampLo;
        u[9] = (float) plantSteadyRampHi;
        u[10] = (float) plantLocalRate;
        u[11] = (float) (plantContrastCalm + (plantContrastStorm - plantContrastCalm) * wind);
        u[12] = (float) turbMix1;
        u[13] = (float) turbMix2;
        u[14] = (float) turbMixLocal;
        u[15] = (float) plantLocalClock;
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
        u[35] = (float) plantLeafShade;
        double leafCycles = Math.floor(leafClock);
        u[36] = (float) (leafCycles % 64.0);
        u[37] = (float) plantLeafDens;
        u[38] = (float) plantLeafGust;
        u[39] = (float) plantLeafRateSpread;
        u[40] = (float) (leafClock - leafCycles);
        double branchCycles = Math.floor(branchClock);
        u[41] = (float) (branchCycles % 64.0);
        u[42] = (float) (branchClock - branchCycles);
        u[43] = 0.0f;
        u[44] = (float) plantTipLead;
        u[45] = (float) Math.max(plantTipLeadPow, 0.01);
        u[46] = (float) plantTipFast;
        u[47] = (float) Math.max(0.0, plantTipFlick);
        u[48] = (float) (plantSheenCalm + (plantSheenStorm - plantSheenCalm) * wind);
        u[49] = (float) Math.max(plantSheenPow, 0.01);
        u[50] = 0.0f;
        u[51] = 0.0f;
        u[52] = plantModel == 0 ? 0.0f : 1.0f;
        u[53] = (float) plantBendPowStorm;
        u[54] = (float) Math.max(0.0, plantDamping);
        u[55] = (float) plantRingGain;
        u[56] = (float) Math.max(0.0, plantFlutterAmp);
        u[57] = (float) plantFlutterOnset;
        u[58] = (float) Math.max(1.0, Math.round(plantFlutterRate));
        u[59] = (float) plantFlutterSpread;
        u[60] = (float) Math.max(0.1, honamiLen);
        u[61] = (float) honamiSpeed;
        u[62] = (float) Math.max(0.0, honamiMix);
        u[63] = (float) Math.max(0.0, Math.min(0.9, plantPeriodSpread));
        // Honami wavelength is set at the reference period and grows with
        // the object's period (~ height): a bush sees a longer wave, and the
        // ring's T/8 sampling never aliases on it.
        u[64] = (float) Math.max(0.05, plantPeriod);
        u[65] = (float) Math.max(0.001, plantRingGate);
        u[66] = (float) Math.max(0.0, Math.min(1.0, plantRingStormFade));
        u[67] = (float) Math.max(0.05, plantCapKnee);
        u[68] = (float) Math.max(0.5, plantTurbLen1);
        u[69] = (float) Math.max(0.5, plantTurbLen2);
        u[70] = (float) Math.max(0.0, plantSwingGain);
        u[71] = (float) Math.max(0.05, plantSwingRate);
        u[72] = (float) Math.max(0.0, plantLeanLag);
        u[73] = (float) Math.max(0.0, Math.min(1.0, plantLeanShare));
        u[74] = (float) advectPlant;
        u[75] = (float) speedPlant;
        u[76] = (float) Math.max(0.5, plantCrossLen);
        u[77] = (float) Math.max(0.0, plantCrossMix);
        u[78] = (float) plantCrossRate;
        u[79] = (float) Math.max(0.0, plantLocalRateStorm);
        u[80] = (float) Math.max(0.05, Math.min(0.95, plantBlockKnee));
        u[81] = (float) Math.max(0.0, plantBlockTail);
        u[82] = (float) (1.0 / Math.max(plantLobeCell, 2.0));
        u[83] = (float) Math.max(0.0, plantLobeY);
        u[84] = (float) maskClock;
        u[85] = (float) (1.0 / Math.max(plantMaskCell, 4.0));
        u[86] = (float) Math.max(0.0, Math.min(1.0, plantMaskStrength));
        u[87] = (float) Math.max(0.0, Math.min(1.0, plantMaskFloor));
        double densLow = plantFlickDensCalm + (plantFlickDensStorm - plantFlickDensCalm) * smooth(plantFlickWindOnset, 1.0, wind);
        u[88] = (float) Math.max(0.25, plantFlickRate);
        u[89] = (float) plantFlickDuty;
        u[90] = (float) densLow;
        u[91] = (float) Math.max(densLow, plantFlickDensGust);
        u[92] = (float) plantFlickGustOnset;
        u[93] = (float) Math.max(plantFlickGustOnset + 0.01, plantFlickGustFull);
        u[94] = (float) Math.max(0.0, Math.min(1.0, plantFlickOutside));
        u[95] = (float) (1.0 / Math.max(plantFlickCell, 1.0));
        u[96] = (float) Math.max(0.0, Math.min(1.0, plantMaskGustDens));
        u[97] = (float) Math.pow(32.0 / Math.max(plantLobeCell, 2.0), 0.35);
        u[98] = 0.0f;
        u[99] = 0.0f;
    }

    private static double smooth(double a, double b, double x) {
        double t = (x - a) / Math.max(1e-6, b - a);
        if (t <= 0.0) return 0.0;
        if (t >= 1.0) return 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    // Leaf layer ramp from onset to full wind (a gust at low w stirs the
    // leaves, it does not swing the whole crown), flicker amplitude at the
    // plants' wind before the class contrast.
    static double plantLeafGate(double wind) {
        return smooth(plantLeafOnset, Math.max(plantLeafOnset + 0.001, plantLeafFull), wind);
    }

    static float plantFlickPx(double wind) {
        return (float) ((plantFlickAmp + (plantFlickAmpStorm - plantFlickAmp) * wind) * plantLeafGate(wind));
    }

    // Lobe amplitude of a crown part at the plants' wind, before the class
    // factor and the local wind (vertex).
    static float plantLobePx(double wind) {
        double t = (wind - plantLobeOnset) / Math.max(0.001, plantLobeFull - plantLobeOnset);
        if (t <= 0.0) return 0.0f;
        if (t > 1.0) t = 1.0;
        t = t * t * (3.0 - 2.0 * t);
        return (float) ((plantLobeCalm + (plantLobeStorm - plantLobeCalm) * wind) * t);
    }

    // Swing shape in 0..1 with the storm hold applied.
    private static double swing(double wind, double ts, double t, double period, float p1, float p2, float p3, float p4) {
        double hold = stormHold * accent(wind);
        return hold + (1.0 - hold) * base(ts, t, period, p1, p2, p3, p4);
    }

    private static double wrap(double t) {
        return t > 65536.0 ? t - 65536.0 : t;
    }

    // The mask field repeats every 64 cells along the drift and 24 across
    // (vnoiseMask in the fragment shaders), so the clock wraps there
    // seamlessly and stays exact as a float.
    static final double MASK_WRAP = 64.0;

    private static double wrapMask(double t) {
        return t >= MASK_WRAP ? t - MASK_WRAP : t;
    }

    // The swing clocks wrap at SWAY_WRAP seconds and every swing period is
    // SWAY_WRAP / (11 m): the wrap is then 11 m cycles of the main sine and
    // 6 m / 20 m of the 11/6 and 0.55 companions, all whole, so the phase
    // is seamless and the clock stays small enough for a float.
    static final double SWAY_WRAP = 2048.0;

    private static double wrapSway(double t) {
        return t >= SWAY_WRAP ? t - SWAY_WRAP : t;
    }

    static double quantPeriod(double period) {
        double m = Math.max(1.0, Math.round(SWAY_WRAP / (11.0 * period)));
        return SWAY_WRAP / (11.0 * m);
    }

    // Two sines between upright and full lean, breathing in amplitude.
    private static double base(double ts, double t, double period, float p1, float p2, float p3, float p4) {
        double c1 = ts / period + p1;
        double c2 = ts / (period * 11.0 / 6.0) + p2;
        double s = 0.55 * Math.sin(TWO_PI * c1) + 0.45 * Math.sin(TWO_PI * c2);
        double breathe = 0.75 + 0.25 * Math.sin(TWO_PI * (t / (9.0 + 6.0 * p3) + p4));
        return breathe * (0.5 + 0.5 * s);
    }

    // Vanilla stiffens wind types 2 and 3 to a half and a quarter.
    public static volatile double poolStiff2 = 0.6;
    public static volatile double poolStiff3 = 0.35;

    private static double poolLean(int i) {
        double wind = w;
        int type = i / poolStride;
        double stiff = type == 0 ? 1.0 : (type == 1 ? poolStiff2 : poolStiff3);
        double period = quantPeriod(bushPeriod * (1.0 - periodSpread + 2.0 * periodSpread * hash(i, 4)));
        return dir * bushAmplitude(wind) * stiff * swing(wind, swayClock, time, period, hash(i, 1), hash(i, 2), hash(i, 3), hash(i, 5));
    }

    private static double plantLean(int i) {
        double wind = wPlant;
        if (wind <= 0.0) return 0.0;
        int type = i / poolStride;
        double stiff = type == 0 ? 1.0 : (type == 1 ? plantStiff2 : plantStiff3);
        double amp = plantAmpMax * Math.pow(wind, plantAmpPow) * (1.0 + stormGain * accent(wind)) * stiff;
        double period = quantPeriod(plantPeriod * (1.0 - periodSpread + 2.0 * periodSpread * hash(i, 4)) / (0.7 + 0.3 * stiff));
        return dir * amp * swing(wind, swayClock, time, period, hash(i + 64, 1), hash(i + 64, 2), hash(i + 64, 3), hash(i + 64, 5));
    }

    // Render thread, per list: snapshot of the clocks and wind terms.
    private static double sW;
    private static double sAmp;
    private static double sTreeTime;
    static double sRingClock;
    static double sAdvect;
    private static double sAdvSign;
    private static double sAdvSpeed;
    static double sDir;
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

    // Value-only twin of noise() for the game-thread breeze: nv/nd belong
    // to the render thread's turbulence.
    private static double breezeNoise(double x, int salt) {
        double fl = Math.floor(x);
        int i = (int) fl;
        double f = x - fl;
        double a = hash(i, salt);
        double b = hash(i + 1, salt);
        return a + (b - a) * f * f * (3.0 - 2.0 * f);
    }

    // The tree field's two spatial octaves at the player, the per-tree
    // term at its mean; game thread, so the value-only noise.
    private static double gustAtPlayer() {
        IsoPlayer p = IsoPlayer.getInstance();
        if (p == null) return -1.0;
        double s = (p.getX() - p.getY()) / SQRT2;
        double v = turbMix1 * breezeNoise((s - advect) / turbLen1, 11)
                + turbMix2 * breezeNoise((s - advect) / turbLen2 + 7.7, 23)
                + turbMixLocal * 0.5;
        double u = (v - 0.5) * turbContrast + 0.5;
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1.0;
        return u * u * (3.0 - 2.0 * u);
    }

    private static double breezeAt(double level, double n) {
        double hi = WindSwayMod.windCeil;
        if (hi < level) hi = level;
        if (hi <= 0.0) return 0.0;
        double b = level + (hi - level) * n;
        return b > 1.0 ? 1.0 : b;
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

    // Render thread, after prepareList(). Lean in ORE units; periodFactor
    // and ringFactor come from the tree's height, class and foliage.
    static double lean(float tx, float ty, double periodFactor, double ringFactor, int seed) {
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
        double period = periodBase * periodFactor
                * (1.0 - periodSpread + 2.0 * periodSpread * hPeriod);
        double rest = ringRest + (1.0 - ringRest) * wind;
        double swing = ringGain * ringFactor * energy * sens * (rest + (1.0 - rest) * e);
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
