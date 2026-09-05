package pzmod.windsway;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Exposer;

import org.lwjgl.opengl.GL11;

import zombie.config.BooleanConfigOption;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.opengl.ShaderProgram;
import zombie.core.textures.Texture;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.ObjectRenderEffects;
import zombie.iso.objects.RenderEffectType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.WeatherPeriod;

// Kahlua global "WindSwayMod" (simple class name); console calls need
// the prefix.
@Exposer.LuaClass
public class WindSwayMod {

    public static volatile boolean enabled = true;

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    private static Field optionMapField;

    // The forced getter would put a tick in the options screen that Apply
    // writes back into options.ini, so it is no fallback either. The
    // option is looked up under its options.ini token, which survives a
    // field rename.
    public static boolean vanillaWindSpriteEffects() {
        try {
            Field f = optionMapField;
            if (f == null) {
                f = Accessor.findField(Core.class, "optionByName");
                if (f == null) throw new NoSuchFieldException("optionByName");
                f.setAccessible(true);
                optionMapField = f;
            }
            Object opt = ((Map<?, ?>) f.get(Core.getInstance())).get("doWindSpriteEffects");
            if (opt instanceof BooleanConfigOption) return ((BooleanConfigOption) opt).getValue();
            trace("vanilla wind option not found: " + opt);
        } catch (Throwable t) {
            trace("vanilla wind option read failed: " + t);
        }
        return false;
    }

    // The baseline slider: one remap base for the plant channel
    // (getWindTickFinal, Patch_ClimateManager) and the tree channel
    // (TreeSway). Vanilla wind sits near zero for hours on calm days.
    public static volatile double windFloor = 0.2;

    public static void setWindFloor(double v) {
        windFloor = Math.max(0.0, v);
    }

    public static volatile double treeWindFloor = 0.2;

    public static void setTreeWindFloor(double v) {
        treeWindFloor = Math.max(0.0, v);
    }

    // Upper bound of the calm-wind band, shared by both channels.
    public static volatile double windCeil = 0.4;

    public static void setWindCeil(double v) {
        windCeil = Math.max(0.0, v);
    }

    // This tick's wandering breeze (TreeSway), -1 before the first world
    // tick; the patches and the rustle path fall back to the sliders.
    public static volatile double breezeTree = -1.0;
    public static volatile double breezePlant = -1.0;

    public static double breezeTreeFloor() {
        double b = breezeTree;
        return b >= 0.0 ? b : treeWindFloor;
    }

    public static double breezePlantFloor() {
        double b = breezePlant;
        return b >= 0.0 ? b : windFloor;
    }

    // The gust field at the player (TreeSway), -1 without a player or model.
    public static volatile double gustSound = -1.0;

    // The wind ambience follows the raised wind (Patch_ParameterWindIntensity).
    public static volatile boolean windSound = true;

    public static void setWindSound(boolean v) {
        windSound = v;
    }

    // Share of the mod's wind band the ambience hears; the game's own wind
    // and the gusts on top stay as they are.
    public static volatile double windSoundLevel = 0.5;

    public static void setWindSoundLevel(double v) {
        windSoundLevel = Math.max(0.0, Math.min(1.0, v));
    }

    public static void setWeatherTakeover(boolean v) {
        TreeSway.weatherTakeover = v;
    }

    public static void setWeatherBlend(double seconds) {
        TreeSway.weatherBlend = seconds;
    }

    // Weather that takes the wind over: precipitation, fog or a storm. A
    // running period alone is not enough, a dry front sets no wind of its
    // own and would leave the world still under a grey sky.
    static boolean weatherActive() {
        ClimateManager cm = ClimateManager.getInstance();
        if (cm == null) return false;
        if (cm.getPrecipitationIntensity() > 0.01f || cm.getFogIntensity() > 0.15f) return true;
        WeatherPeriod wp = cm.getWeatherPeriod();
        return wp != null && (wp.isThunderStorm() || wp.isTropicalStorm() || wp.isBlizzard());
    }

    // Console tuning hooks.
    public static void setTreeWarp(boolean v) {
        TreeRenderer.warp = v;
    }

    // Workshop videos: no player see-through mask, trees never fade.
    public static volatile boolean videoMode = false;

    public static void setVideoMode(boolean v) {
        videoMode = v;
    }

    public static void setTreeSharp(double v) {
        TreeRenderer.sharp = v;
    }

    public static void setTreeFloorHack(double top, double bottom) {
        TreeRenderer.floorHackTop = top;
        TreeRenderer.floorHackBottom = bottom;
    }

    public static void setTreeTrunk(double v) {
        TreeRenderer.trunkFactor = v;
    }

    public static void setTreeBend(double pow, double powStorm, double trunkStorm) {
        TreeRenderer.bendPow = pow;
        TreeRenderer.bendPowStorm = powStorm;
        TreeRenderer.trunkStorm = trunkStorm;
    }

    public static void setTreeHeightPow(double v) {
        TreeRenderer.heightPow = v;
    }

    public static void setTreeLeaf(double ampPx, double hz, double cellPx) {
        TreeRenderer.leafAmp = ampPx;
        TreeRenderer.leafHz = hz;
        TreeRenderer.leafCell = cellPx;
    }

    public static void setTreeLeafStorm(double ampPx, double hz) {
        TreeRenderer.leafAmpStorm = ampPx;
        TreeRenderer.leafHzStorm = hz;
    }

    public static void setTreeLeafSize(double refH, double pow) {
        TreeRenderer.leafRefH = refH;
        TreeRenderer.leafSizePow = pow;
    }

    public static void setTreeBranch(double floorPx, double fracOfLean, double maxPx) {
        TreeRenderer.branchFloor = floorPx;
        TreeRenderer.branchFrac = fracOfLean;
        TreeRenderer.branchMax = maxPx;
    }

    public static void setTreeBranchStorm(double px) {
        TreeRenderer.branchStorm = px;
    }

    public static void setTreeBranchCell(double fracOfWidth, double minPx) {
        TreeRenderer.branchCellFrac = fracOfWidth;
        TreeRenderer.branchCellMin = minPx;
    }

    public static void setTreeBranchGate(double onset, double full, double gustBase, double energyRate, double wind) {
        TreeRenderer.branchOnset = onset;
        TreeRenderer.branchFull = full;
        TreeRenderer.branchGustBase = gustBase;
        TreeRenderer.lobeRate = energyRate;
        TreeRenderer.lobeWind = wind;
    }

    public static void setTreeLobeRate(double hz, double refCellPx, double exponent, double spread) {
        TreeSway.lobeHz = hz;
        TreeRenderer.lobeRefCell = refCellPx;
        TreeRenderer.lobeRateExp = exponent;
        TreeRenderer.lobeFreqSpread = spread;
    }

    public static void setTreeLeafGust(double base, double rateSpread, double windFull, double windDens) {
        TreeRenderer.leafGustBase = base;
        TreeRenderer.leafRateSpread = rateSpread;
        TreeRenderer.leafWindFull = windFull;
        TreeRenderer.leafWindDens = windDens;
    }

    public static void setTreeLeafMask(double strength, double cellPx, double driftCellsPerSecond, double floor) {
        TreeRenderer.leafMaskStrength = strength;
        TreeRenderer.leafMaskCell = cellPx;
        TreeSway.maskRate = driftCellsPerSecond;
        TreeRenderer.leafMaskFloor = floor;
    }

    public static void setTreeTurbulence(double lengthTiles1, double lengthTiles2, double localRate) {
        TreeSway.turbLen1 = lengthTiles1;
        TreeSway.turbLen2 = lengthTiles2;
        TreeSway.turbLocalRate = localRate;
    }

    public static void setTreeTurbulenceMix(double octave1, double octave2, double local, double contrast) {
        TreeSway.turbMix1 = octave1;
        TreeSway.turbMix2 = octave2;
        TreeSway.turbMixLocal = local;
        TreeSway.turbContrast = contrast;
    }

    public static void setTreeFront(double speed, double speedWind) {
        TreeSway.frontSpeed = speed;
        TreeSway.frontSpeedWind = speedWind;
    }

    public static void setTreeResponse(double sensitivitySpread, double thresholdMax, double curve, double inertia,
                                       double curveStorm) {
        TreeSway.sensSpread = sensitivitySpread;
        TreeSway.thresholdMax = thresholdMax;
        TreeSway.responseCurve = curve;
        TreeSway.leanSmooth = inertia;
        TreeSway.responseCurveStorm = curveStorm;
    }

    public static void setTreeRing(double gain, double rate, double knee, double memory, double lagSeconds) {
        TreeSway.ringGain = gain;
        TreeSway.ringRate = rate;
        TreeSway.ringKnee = knee;
        TreeSway.ringMemory = memory;
        TreeSway.ringLag = lagSeconds;
    }

    public static void setTreeRingWind(double wind, double rest, double fast, double upwindCap) {
        TreeSway.ringWind = wind;
        TreeSway.ringRest = rest;
        TreeSway.ringFast = fast;
        TreeSway.upwindCap = upwindCap;
    }

    public static void setTreeLeafLook(double shade, double cellExp, double gustDens, double shadeRate) {
        TreeRenderer.leafShade = shade;
        TreeRenderer.leafCellExp = cellExp;
        TreeRenderer.leafGustDens = gustDens;
        TreeRenderer.leafShadeRate = shadeRate;
    }

    public static void setTreeCrown(double knee, double lobeYFrac) {
        TreeRenderer.crownKnee = knee;
        TreeRenderer.branchYFrac = lobeYFrac;
    }

    public static void setTreeCrownShape(double tail, double shorten, double tilt) {
        TreeRenderer.crownTail = tail;
        TreeRenderer.crownShorten = shorten;
        TreeRenderer.crownTilt = tilt;
    }

    public static void setTreeTrunkLean(double calm, double storm, double foot) {
        TreeRenderer.trunkLeanCalm = calm;
        TreeRenderer.trunkLeanStorm = storm;
        TreeRenderer.trunkFoot = foot;
    }

    public static void setTreeLeanMean(double meanPerWind) {
        TreeSway.meanLean = meanPerWind;
    }

    public static void setTreeSway(double ampMax, double ampPow, double ampFloor) {
        TreeSway.ampMax = ampMax;
        TreeSway.ampPow = ampPow;
        TreeSway.ampFloor = ampFloor;
    }

    public static void setBushSway(double ampMax, double pow, double periodSeconds) {
        TreeSway.bushAmpMax = ampMax;
        TreeSway.bushAmpPow = pow;
        TreeSway.bushPeriod = Math.max(0.05, periodSeconds);
    }

    public static void setBushStiffness(double type2, double type3) {
        TreeSway.poolStiff2 = type2;
        TreeSway.poolStiff3 = type3;
    }

    public static void setPlantSway(double ampMax, double pow, double periodSeconds) {
        TreeSway.plantAmpMax = ampMax;
        TreeSway.plantAmpPow = pow;
        TreeSway.plantPeriod = Math.max(0.05, periodSeconds);
    }

    public static void setPlantStiffness(double type2, double type3) {
        TreeSway.plantStiff2 = type2;
        TreeSway.plantStiff3 = type3;
    }

    public static void setPlantShape(double bendPow, double shorten, double bladeCellPx, double bladeVar) {
        TreeSway.plantBendPow = bendPow;
        TreeSway.plantShorten = shorten;
        TreeSway.plantBladeCell = bladeCellPx;
        TreeSway.plantBladeVar = bladeVar;
    }

    public static void setPlantMean(double meanPerWind) {
        TreeSway.plantMean = meanPerWind;
    }

    public static void setPlantBarrier(double capPx) {
        TreeSway.plantBarrierCap = capPx;
    }

    public static void setPlantCap(double knee) {
        TreeSway.plantCapKnee = knee;
    }

    public static void setPlantGate(double start, double slope) {
        TreeSway.plantGateStart = Math.max(0.0, Math.min(1.0, start));
        TreeSway.plantGateSlope = Math.max(0.0, Math.min(1.0, slope));
    }

    // Plant classes: 0 blades, 1 leafy, 2 rosette, 3 twig, 4 crown, 5 stalk,
    // 6 flower, 7 sparse.
    public static void setPlantClass(int cls, double lean, double period, double bendPow, double bladeVar,
                                     double leafAmp, double leafX, double leafY, double leafCellPx, double leafRate) {
        PlantClass.set(cls, lean, period, bendPow, bladeVar, leafAmp, leafX, leafY, leafCellPx, leafRate);
    }

    public static void setPlantLeaf(double ampPx, double ampStormPx, double refPx, double sizePow) {
        TreeSway.plantLeafAmp = ampPx;
        TreeSway.plantLeafAmpStorm = ampStormPx;
        TreeSway.plantLeafRefPx = Math.max(1.0, refPx);
        TreeSway.plantLeafSizePow = sizePow;
    }

    public static void setPlantLeafLook(double density, double gustBase, double rateSpread, double shade) {
        TreeSway.plantLeafDens = Math.max(0.0, Math.min(1.0, density));
        TreeSway.plantLeafGust = Math.max(0.0, Math.min(1.0, gustBase));
        TreeSway.plantLeafRateSpread = Math.max(0.0, Math.min(0.5, rateSpread));
        TreeSway.plantLeafShade = shade;
    }

    public static void setPlantPeriodHeight(double refUnits, double pow) {
        TreeSway.plantPeriodRefH = Math.max(0.05, refUnits);
        TreeSway.plantPeriodExp = pow;
    }

    public static void setPlantTip(double lead, double leadPow, double fast, double flick) {
        TreeSway.plantTipLead = lead;
        TreeSway.plantTipLeadPow = leadPow;
        TreeSway.plantTipFast = fast;
        TreeSway.plantTipFlick = flick;
    }

    public static void setPlantSheen(double calm, double storm, double heightPow) {
        TreeSway.plantSheenCalm = calm;
        TreeSway.plantSheenStorm = storm;
        TreeSway.plantSheenPow = heightPow;
    }

    public static void setPlantClassTip(int cls, double tip, double sheen) {
        PlantClass.setTip(cls, tip, sheen);
    }

    public static void setPlantClassBody(int cls, double block, double swing, double inertia, double lobe) {
        PlantClass.setBody(cls, block, swing, inertia, lobe);
    }

    public static void setPlantClassLeafLook(int cls, double clusterPx, double flick, double mask) {
        PlantClass.setLeafLook(cls, clusterPx, flick, mask);
    }

    public static void setPlantClassSteady(int cls, double steady) {
        PlantClass.setSteady(cls, steady);
    }

    public static void setBushGenus(int genus, double lean, double period, double swing, double steady,
            double leafAmp, double leafCell, double flick, double mask, double block, double bendPow) {
        BushGenus.set(genus, lean, period, swing, steady, leafAmp, leafCell, flick, mask, block, bendPow);
    }

    public static void setPlantSteadyRamp(double lo, double hi) {
        TreeSway.plantSteadyRampLo = Math.max(0.0, lo);
        TreeSway.plantSteadyRampHi = Math.max(lo + 0.001, hi);
    }

    public static void setPlantContrast(double calm, double storm) {
        TreeSway.plantContrastCalm = Math.max(0.0, calm);
        TreeSway.plantContrastStorm = Math.max(0.0, storm);
    }

    public static void setBreeze(double period, double periodFine, double fineWeight) {
        TreeSway.breezePeriod = Math.max(1.0, period);
        TreeSway.breezePeriodFine = Math.max(1.0, periodFine);
        TreeSway.breezeFineWeight = Math.max(0.0, Math.min(1.0, fineWeight));
    }

    public static void setWindSoundGust(double down, double up) {
        TreeSway.soundGustDown = Math.max(0.0, Math.min(1.0, down));
        TreeSway.soundGustUp = Math.max(0.0, up);
    }

    public static volatile boolean debugWindSound = false;

    public static void setDebugWindSound(boolean v) {
        debugWindSound = v;
    }

    private static long windSoundLogNanos;

    public static void logWindSound(float raw, double floor, double gust, float sent) {
        long now = System.nanoTime();
        if (now - windSoundLogNanos < 2_000_000_000L) return;
        windSoundLogNanos = now;
        trace(String.format(Locale.ROOT, "wind sound: raw %.3f breeze %.3f gust %.2f -> fmod %.3f",
                raw, floor, gust, sent));
    }

    public static void setPlantLeafOnset(double onset, double full, double clusterRatePow) {
        TreeSway.plantLeafOnset = Math.max(0.0, onset);
        TreeSway.plantLeafFull = Math.max(onset + 0.001, full);
        TreeSway.plantClusterRatePow = clusterRatePow;
    }

    public static void setPlantMask(double strength, double cellPx, double floor, double gustDens) {
        TreeSway.plantMaskStrength = strength;
        TreeSway.plantMaskCell = cellPx;
        TreeSway.plantMaskFloor = floor;
        TreeSway.plantMaskGustDens = gustDens;
    }

    public static void setPlantFlicker(double ampCalm, double ampStorm, double rate, double duty, double cellPx) {
        TreeSway.plantFlickAmp = ampCalm;
        TreeSway.plantFlickAmpStorm = ampStorm;
        TreeSway.plantFlickRate = rate;
        TreeSway.plantFlickDuty = duty;
        TreeSway.plantFlickCell = cellPx;
    }

    public static void setPlantFlickerDensity(double calm, double storm, double windOnset, double gust,
                                              double gustOnset, double gustFull, double outside) {
        TreeSway.plantFlickDensCalm = calm;
        TreeSway.plantFlickDensStorm = storm;
        TreeSway.plantFlickWindOnset = windOnset;
        TreeSway.plantFlickDensGust = gust;
        TreeSway.plantFlickGustOnset = gustOnset;
        TreeSway.plantFlickGustFull = gustFull;
        TreeSway.plantFlickOutside = outside;
    }

    public static void setPlantSnow(double lean, double leaf, double steady) {
        TreeSway.plantSnowLean = Math.max(0.0, lean);
        TreeSway.plantSnowLeaf = Math.max(0.0, leaf);
        TreeSway.plantSnowSteady = Math.max(0.0, Math.min(1.0, steady));
    }

    public static void setPlantBlock(double knee, double tail) {
        TreeSway.plantBlockKnee = knee;
        TreeSway.plantBlockTail = tail;
    }

    public static void setPlantLobe(double calmPx, double stormPx, double cellPx, double yShare, double onset, double full) {
        TreeSway.plantLobeCalm = Math.max(0.0, calmPx);
        TreeSway.plantLobeStorm = Math.max(0.0, stormPx);
        TreeSway.plantLobeCell = Math.max(2.0, cellPx);
        TreeSway.plantLobeY = Math.max(0.0, yShare);
        TreeSway.plantLobeOnset = Math.max(0.0, Math.min(1.0, onset));
        TreeSway.plantLobeFull = Math.max(TreeSway.plantLobeOnset + 0.01, Math.min(1.0, full));
    }

    // 1 = ring model, 0 = the standing sine with the energy envelope.
    public static void setPlantModel(int model) {
        TreeSway.plantModel = model == 0 ? 0 : 1;
    }

    public static void setPlantDamping(double zeta, double gain, double gateRate, double stormFade) {
        TreeSway.plantDamping = zeta;
        TreeSway.plantRingGain = gain;
        TreeSway.plantRingGate = gateRate;
        TreeSway.plantRingStormFade = stormFade;
    }

    public static void setPlantPeriodSpread(double spread) {
        TreeSway.plantPeriodSpread = spread;
    }

    public static void setPlantBendStorm(double addPow) {
        TreeSway.plantBendPowStorm = addPow;
    }

    public static void setPlantFlutter(double ampPx, double onset, double rateMul, double phaseSpread) {
        TreeSway.plantFlutterAmp = ampPx;
        TreeSway.plantFlutterOnset = onset;
        TreeSway.plantFlutterRate = rateMul;
        TreeSway.plantFlutterSpread = phaseSpread;
    }

    public static void setPlantHonami(double lenTiles, double speedFactor, double weight) {
        TreeSway.honamiLen = lenTiles;
        TreeSway.honamiSpeed = speedFactor;
        TreeSway.honamiMix = weight;
    }

    public static void setPlantTurbulence(double lengthTiles1, double lengthTiles2) {
        TreeSway.plantTurbLen1 = lengthTiles1;
        TreeSway.plantTurbLen2 = lengthTiles2;
    }

    public static void setPlantSwing(double gainF, double rateF, double stormFade) {
        TreeSway.plantSwingGain = gainF;
        TreeSway.plantSwingRate = rateF;
        TreeSway.plantRingStormFade = stormFade;
    }

    public static void setPlantLeanInertia(double lagSeconds, double share) {
        TreeSway.plantLeanLag = lagSeconds;
        TreeSway.plantLeanShare = share;
    }

    public static void setPlantCalm(double frontCalm, double localRate, double frontStorm) {
        TreeSway.plantFrontCalm = frontCalm;
        TreeSway.plantLocalRate = localRate;
        TreeSway.plantFrontStorm = frontStorm;
    }

    public static void setPlantCross(double lenTiles, double weight, double driftRate, double localRateStorm) {
        TreeSway.plantCrossLen = lenTiles;
        TreeSway.plantCrossMix = weight;
        TreeSway.plantCrossRate = driftRate;
        TreeSway.plantLocalRateStorm = localRateStorm;
    }

    public static void setPlantDry(double period, double lean, double damping, double flutter) {
        TreeSway.dryPeriod = period;
        TreeSway.dryLean = lean;
        TreeSway.dryDamping = damping;
        TreeSway.dryFlutter = flutter;
    }

    public static void setTreeStormSway(double onset, double gain, double hold) {
        TreeSway.stormOnset = Math.min(0.99, onset);
        TreeSway.stormGain = gain;
        TreeSway.stormHold = hold;
    }

    public static void setTreePeriod(double seconds, double spread, double stormSpeedup) {
        TreeSway.periodBase = seconds;
        TreeSway.periodSpread = spread;
        TreeSway.stormSpeedup = stormSpeedup;
    }

    public static void setTreeDir(double smoothSeconds) {
        TreeSway.dirSmooth = smoothSeconds;
    }

    public static void setTreeSwayTempo(double calm, double storm) {
        TreeSway.swayTempoCalm = calm;
        TreeSway.swayTempoStorm = storm;
    }

    public static void setTreeTime(double scale) {
        TreeSway.timeScale = scale;
    }

    public static void setTreeStorm(double v) {
        TreeSway.storm = v;
    }

    public static void setTreeGiant(double boost, double onset, double full, double fade) {
        TreeRenderer.giantBoost = boost;
        TreeRenderer.giantOnset = onset;
        TreeRenderer.giantFull = full;
        TreeRenderer.giantFade = fade;
    }

    public static void setTreeConifer(double leafAmp, double leafHz, double lobeAmp, double leafAmpStorm, double leafHzStorm) {
        TreeRenderer.coniferLeafAmp = leafAmp;
        TreeRenderer.coniferLeafHz = leafHz;
        TreeRenderer.coniferLobeAmp = lobeAmp;
        TreeRenderer.coniferLeafAmpStorm = leafAmpStorm;
        TreeRenderer.coniferLeafHzStorm = leafHzStorm;
    }

    public static void setTreeConiferLobes(double xFactor, double yFrac, double tierAspect, double ramp, double yStorm) {
        TreeRenderer.coniferLobeX = xFactor;
        TreeRenderer.coniferLobeY = yFrac;
        TreeRenderer.coniferTierAspect = tierAspect;
        TreeRenderer.coniferLobeRamp = ramp;
        TreeRenderer.coniferLobeYStorm = yStorm;
    }

    public static void setTreeConiferTierMin(double px, double refH) {
        TreeRenderer.coniferLobeMinPx = px;
        TreeRenderer.coniferLobeMinRefH = refH;
    }

    public static void setTreeConiferTierShape(double trunk, double pow) {
        TreeRenderer.coniferTierTrunk = trunk;
        TreeRenderer.coniferTierPow = pow;
    }

    public static void setTreeConiferShape(double leanFactor, double start, double bendPow) {
        TreeRenderer.coniferLean = leanFactor;
        TreeRenderer.coniferStart = start;
        TreeRenderer.coniferBendPow = bendPow;
    }

    public static void setTreeBare(double leanFactor, double bendPow, double lobeFactor, double cellFactor) {
        TreeRenderer.bareLean = leanFactor;
        TreeRenderer.bareBendPow = bendPow;
        TreeRenderer.bareLobe = lobeFactor;
        TreeRenderer.bareCell = cellFactor;
    }

    public static void setTreeWoodLobes(double v) {
        TreeRenderer.woodLobe = v;
    }

    public static void setTreeWoodMask(double v) {
        TreeRenderer.woodMask = v < 0.0 ? 0.0 : v > 1.0 ? 1.0 : v;
    }

    public static void setTreeDebug(double mode) {
        TreeRenderer.debugMode = mode;
    }

    public static void setTreeSeason(double bloomLeaf, double barePeriod, double bareRing) {
        TreeRenderer.bloomLeaf = bloomLeaf;
        TreeRenderer.barePeriod = barePeriod;
        TreeRenderer.bareRing = bareRing;
    }

    public static void setTreeSparse(double leafAmp, double leafRate, double flick, double denseAt, double fullAt) {
        TreeRenderer.sparseLeafAmp = leafAmp;
        TreeRenderer.sparseLeafRate = leafRate;
        TreeRenderer.sparseFlick = flick;
        TreeRenderer.sparseDense = denseAt;
        TreeRenderer.sparseFull = fullAt;
    }

    public static void setTreePeriodHeight(double refH) {
        TreeSway.periodRefH = refH;
    }

    // Console only, this and the three below: the arrays are read by the
    // render thread without a fence, a change shows when it shows.
    // 0 cone (holly), 1 pendulous (hemlock), 2 pine, 3 fine (birch, redbud,
    // silverbell), 4 dense (maple, linden, yellowwood), 5 understory
    // (hawthorn, dogwood).
    public static void setTreeClass(int cls, double lean, double period, double periodExp, double ring, double lobe,
                                    double lobeCell, double lobeY, double leafAmp, double leafRate) {
        TreeClass.set(cls, lean, period, periodExp, ring, lobe, lobeCell, lobeY, leafAmp, leafRate);
    }

    // NatureTrees order: 0 holly, 1 hemlock, 2 pine, 3 birch, 4 hawthorn,
    // 5 dogwood, 6 silverbell, 7 yellowwood, 8 redbud, 9 maple, 10 linden.
    public static void setTreeSpecies(int species, double leafAmp, double leafRate, double leafCm, double flick,
                                      double paintPx) {
        TreeSpecies.set(species, leafAmp, leafRate, leafCm, flick, paintPx);
    }

    public static void setTreeSpeciesLobes(int species, double lobe, double lobeCell) {
        TreeSpecies.setLobes(species, lobe, lobeCell);
    }

    // Family order: regular, JUMBO, XL, XXL.
    public static void setTreePaintFamily(double regular, double jumbo, double xl, double xxl) {
        TreeRenderer.paintFamily[0] = regular;
        TreeRenderer.paintFamily[1] = jumbo;
        TreeRenderer.paintFamily[2] = xl;
        TreeRenderer.paintFamily[3] = xxl;
    }

    public static void setTreeCluster(double px, double paintLo, double paintHi, double ampPow, double ratePow,
                                      double snap) {
        TreeRenderer.clusterPx = px;
        TreeRenderer.clusterPaintLo = paintLo;
        TreeRenderer.clusterPaintHi = paintHi;
        TreeRenderer.clusterAmpPow = ampPow;
        TreeRenderer.clusterRatePow = ratePow;
        TreeRenderer.clusterSnap = snap;
    }

    public static void setTreeFlicker(double ampCalm, double ampStorm, double rate, double duty) {
        TreeRenderer.flickAmp = ampCalm;
        TreeRenderer.flickAmpStorm = ampStorm;
        TreeRenderer.flickRate = rate;
        TreeRenderer.flickDuty = duty;
    }

    public static void setTreeFlickerDensity(double calm, double storm, double windOnset, double gust, double gustOnset,
                                             double gustFull, double outsidePatches) {
        TreeRenderer.flickDensCalm = calm;
        TreeRenderer.flickDensStorm = storm;
        TreeRenderer.flickWindOnset = windOnset;
        TreeRenderer.flickDensGust = gust;
        TreeRenderer.flickGustOnset = gustOnset;
        TreeRenderer.flickGustFull = gustFull;
        TreeRenderer.flickOutside = outsidePatches;
    }

    public static void setTreeFlickerCell(double minPx, double r50Factor) {
        TreeRenderer.flickCellMin = minPx;
        TreeRenderer.flickCellK = r50Factor;
    }

    public static void setTreeTwig(double scale, double rate, double amp) {
        TreeRenderer.twigScale = scale;
        TreeRenderer.twigRate = rate;
        TreeRenderer.twigAmp = amp;
    }

    public static void setTreeLeafCellMin(double px) {
        TreeRenderer.leafCellMin = px;
    }

    public static void setTreeJitter(double v) {
        TreeRenderer.treeJitter = v;
    }

    public static void setTreeLayers(boolean main, boolean branch, boolean leaf) {
        TreeRenderer.mainOn = main;
        TreeRenderer.branchOn = branch;
        TreeRenderer.leafOn = leaf;
    }

    public static void setTreeQuality(boolean lobes, boolean octave2, boolean leaves, boolean mask, boolean shade) {
        TreeRenderer.setQuality(lobes, octave2, leaves, mask, shade);
    }

    public static volatile boolean plantBendOn = true;
    public static volatile boolean plantPadOn = true;

    public static void setPlantQuality(boolean bend, boolean pad) {
        plantBendOn = bend;
        plantPadOn = pad;
    }

    private static Field rawWindTickField;

    // The unpatched wind value; the getter carries the plant remap and
    // must never stack under the tree channel.
    static double rawWindTick() throws Exception {
        Field f = rawWindTickField;
        if (f == null) {
            f = Accessor.findField(ClimateManager.class, "windTickFinal");
            if (f == null) throw new NoSuchFieldException("windTickFinal");
            f.setAccessible(true);
            rawWindTickField = f;
        }
        return f.getDouble(null);
    }

    private static boolean rustleOk = true;
    private static Field rrField;
    private static Field rrTypeField;
    private static Field rrTargetField;
    private static Field treePoolsField;
    private static Field dynFxField;
    private static Field oreTypeField;
    private static Field oreParentField;
    public static volatile double lastRustleGain = 1.0;

    // Rustle feedback is authored for static flora; layered on ambient
    // sway it reads as a glitch.
    private static double rustleGain(double n) {
        if (n <= 0.05) return 1.0;
        if (n >= 0.35) return 0.0;
        return (0.35 - n) / 0.30;
    }

    // Runs once per frame from woven advice; must not throw. Pool
    // values are recomputed every frame, so the undo never accumulates.
    public static void attenuateRustles() {
        if (!enabled || !rustleOk) return;
        try {
            if (rrField == null) {
                Field rr = Accessor.findField(ObjectRenderEffects.class, "randomRustle");
                Field rrType = Accessor.findField(ObjectRenderEffects.class, "randomRustleType");
                Field rrTarget = Accessor.findField(ObjectRenderEffects.class, "randomRustleTarget");
                Field pools = Accessor.findField(ObjectRenderEffects.class, "WIND_EFFECTS_TREES");
                Field dyn = Accessor.findField(ObjectRenderEffects.class, "DYNAMIC_EFFECTS");
                Field type = Accessor.findField(ObjectRenderEffects.class, "type");
                Field parent = Accessor.findField(ObjectRenderEffects.class, "parent");
                if (rr == null || rrType == null || rrTarget == null || pools == null
                        || dyn == null || type == null || parent == null) {
                    throw new NoSuchFieldException("randomRustle/DYNAMIC_EFFECTS/type/parent");
                }
                rr.setAccessible(true);
                rrType.setAccessible(true);
                rrTarget.setAccessible(true);
                pools.setAccessible(true);
                dyn.setAccessible(true);
                type.setAccessible(true);
                parent.setAccessible(true);
                rrTypeField = rrType;
                rrTargetField = rrTarget;
                treePoolsField = pools;
                dynFxField = dyn;
                oreTypeField = type;
                oreParentField = parent;
                rrField = rr;
            }
            double raw = rawWindTick();
            double tf = breezeTreeFloor();
            double pf = breezePlantFloor();
            double treeCh = Math.max(tf, raw);
            double plantCh = Math.max(pf, raw);
            double gT = rustleGain(Math.max(0.0, Math.min(1.0, (treeCh - 0.08) / 0.92)));
            double gP = rustleGain(Math.max(0.0, Math.min(1.0, (plantCh - 0.02) / 0.98)));
            lastRustleGain = gT;
            // randomRustle jitters flora with no visible cause; it only
            // ever feeds the tree-family pools, so undo it fully. Not on
            // vanilla's pools when the model is off.
            Object rr = rrField.get(null);
            if (rr != null && TreeSway.isOk()) {
                int t = rrTypeField.getInt(null);
                int i = rrTargetField.getInt(null);
                ObjectRenderEffects[][] pools = (ObjectRenderEffects[][]) treePoolsField.get(null);
                if (t >= 0 && t < pools.length && i >= 0 && i < pools[t].length) {
                    ObjectRenderEffects pool = pools[t][i];
                    ObjectRenderEffects r = (ObjectRenderEffects) rr;
                    pool.x1 -= r.x1;
                    pool.y1 -= r.y1;
                    pool.x2 -= r.x2;
                    pool.y2 -= r.y2;
                    pool.x3 -= r.x3;
                    pool.y3 -= r.y3;
                    pool.x4 -= r.x4;
                    pool.y4 -= r.y4;
                }
            }
            ArrayList<?> dyn = (ArrayList<?>) dynFxField.get(null);
            for (int idx = 0; idx < dyn.size(); ++idx) {
                ObjectRenderEffects e = (ObjectRenderEffects) dyn.get(idx);
                if (oreTypeField.get(e) != RenderEffectType.Vegetation_Rustle) continue;
                Object parentObj = oreParentField.get(e);
                if (!(parentObj instanceof IsoObject)) continue;
                IsoObject parent = (IsoObject) parentObj;
                IsoSprite ps = parent.getSprite();
                // Causeless crown jitter reads as broken: trees keep only
                // their wind part, grass and bushes keep the brush
                // feedback until ambient sway masks it.
                double g = parent instanceof IsoTree ? 0.0
                        : (ps != null && ps.isBush) ? gT : gP;
                if (g >= 1.0) continue;
                ObjectRenderEffects wind = parent.getWindRenderEffects();
                if (wind != null) {
                    e.x1 = wind.x1 + (e.x1 - wind.x1) * g;
                    e.y1 = wind.y1 + (e.y1 - wind.y1) * g;
                    e.x2 = wind.x2 + (e.x2 - wind.x2) * g;
                    e.y2 = wind.y2 + (e.y2 - wind.y2) * g;
                    e.x3 = wind.x3 + (e.x3 - wind.x3) * g;
                    e.y3 = wind.y3 + (e.y3 - wind.y3) * g;
                    e.x4 = wind.x4 + (e.x4 - wind.x4) * g;
                    e.y4 = wind.y4 + (e.y4 - wind.y4) * g;
                } else {
                    e.x1 *= g;
                    e.y1 *= g;
                    e.x2 *= g;
                    e.y2 *= g;
                    e.x3 *= g;
                    e.y3 *= g;
                    e.x4 *= g;
                    e.y4 *= g;
                }
            }
        } catch (Throwable t) {
            rustleOk = false;
            trace("rustle attenuation disabled: " + t);
        }
    }

    private static boolean treeOreScaleOk = true;
    private static ObjectRenderEffects treeOreScratch;
    private static boolean firstTreeScaleLogged = false;

    private static ObjectRenderEffects poolOf(ObjectRenderEffects ore) throws Exception {
        if (TreeSway.isTreePool(ore)) return ore;
        Field parent = oreParentField;
        if (parent == null) {
            parent = Accessor.findField(ObjectRenderEffects.class, "parent");
            if (parent == null) throw new NoSuchFieldException("parent");
            parent.setAccessible(true);
            oreParentField = parent;
        }
        Object p = parent.get(ore);
        return p instanceof IsoObject ? ((IsoObject) p).getWindRenderEffects() : null;
    }

    // Once per visible tree per frame from woven advice; must not throw.
    public static ObjectRenderEffects scaleTreeOre(Texture texture, Texture texture2, ObjectRenderEffects ore) {
        if (ore == null || !enabled || !treeOreScaleOk) return ore;
        try {
            int w = texture != null ? texture.getWidthOrig() : 0;
            if (texture2 != null) {
                w = Math.max(w, texture2.getWidthOrig());
            }
            double f;
            if (w == FBORenderChunk.JUMBO_XXL_WIDTH) {
                f = 7.0;
            } else if (w == FBORenderChunk.JUMBO_XL_WIDTH) {
                f = 5.0;
            } else if (w == FBORenderChunk.JUMBO_L_WIDTH) {
                f = 3.0;
            } else {
                f = 1.0;
            }
            // While the tree renderer draws, the shared pool sway is
            // replaced by the per-tree field; only per-object effects
            // (axe shudder) travel through the ORE. Chunk bakes build
            // their lists outside the translucent pass and vanilla draws
            // them: they keep the pool sway.
            ObjectRenderEffects pool = TreeRenderer.active() && FBORenderCell.instance.renderTranslucentOnly
                    ? poolOf(ore) : null;
            if (pool == ore) return null;
            if (pool == null && f == 1.0) return ore;
            ObjectRenderEffects scratch = treeOreScratch;
            if (scratch == null) {
                scratch = ObjectRenderEffects.alloc();
                treeOreScratch = scratch;
            }
            double px1 = 0.0, py1 = 0.0, px2 = 0.0, py2 = 0.0, px3 = 0.0, py3 = 0.0, px4 = 0.0, py4 = 0.0;
            if (pool != null) {
                px1 = pool.x1; py1 = pool.y1; px2 = pool.x2; py2 = pool.y2;
                px3 = pool.x3; py3 = pool.y3; px4 = pool.x4; py4 = pool.y4;
            }
            scratch.x1 = (ore.x1 - px1) * f;
            scratch.y1 = (ore.y1 - py1) * f;
            scratch.x2 = (ore.x2 - px2) * f;
            scratch.y2 = (ore.y2 - py2) * f;
            scratch.x3 = (ore.x3 - px3) * f;
            scratch.y3 = (ore.y3 - py3) * f;
            scratch.x4 = (ore.x4 - px4) * f;
            scratch.y4 = (ore.y4 - py4) * f;
            if (!firstTreeScaleLogged) {
                firstTreeScaleLogged = true;
                trace("first tree ORE rewritten, scale x" + (int) f + " (texW=" + w + ")");
            }
            return scratch;
        } catch (Throwable t) {
            treeOreScaleOk = false;
            trace("tree ORE scaling disabled: " + t);
            return ore;
        }
    }

    // Debug: tint batch quads red.
    public static volatile boolean debugTint = false;

    public static void setDebugTint(boolean v) {
        debugTint = v;
    }

    // Debug: next grass batch fails, plants must stay visible via vanilla.
    public static void setDebugGrassFail(boolean v) {
        WindSwayGrassDrawer.debugFail = v;
    }

    // World-space vertex lane: the vertex shader projects square + pixel
    // offset and computes the depth from camera uniforms; the capture skips
    // the per-object depth math. Off = the screen-space lane, for the pixel
    // A/B and as fallback.
    public static volatile boolean grassWorldPath = true;

    public static void setGrassWorldPath(boolean v) {
        grassWorldPath = v;
    }

    // Arms every latch again. Called per new world and from the console.
    public static void rearm() {
        WindSwayGrassDrawer.rearm();
        TreeRenderer.rearm();
        TreeSway.rearm();
        DepthAtlas.rearm();
        rustleOk = true;
        treeOreScaleOk = true;
        BatchSequencer.rearm();
        GrassCapture.rearm();
        PlantClass.clearCache();
        BushGenus.clearCache();
        TreeRenderer.requestClear();
        TreeSway.resetClocks();
        firstTreeScaleLogged = false;
        enqueueFailedLogged = false;
        TreeRenderer.pageMissLogged = 0;
        GlTrace.requestDump();
    }

    private static IsoWorld lastWorld;

    // One-time loads and the two shader compiles, from OnGameBoot (Lua)
    // and per new world: in the loading screen instead of the first tree
    // list's frame.
    public static void warmUp() {
        try {
            StencilHole.load();
            TreeRenderer.initHandles();
            TreeProfile.warm();
            if (SpriteRenderer.instance != null) {
                WindSwayGrassDrawer.probe();
                SpriteRenderer.instance.drawGeneric(new TreeRenderer.WarmDrawer());
            }
        } catch (Throwable t) {
            trace("warm-up failed", t);
        }
    }

    // Debug: 5s counters plus reject/flush-trigger names in the console.
    public static volatile boolean debugLog = false;

    public static void setDebugLog(boolean v) {
        debugLog = v;
    }

    // Console: the engine's state trackers against GL around every draw of
    // ours, and the raw state a draw left changed.
    public static void setDebugGlTrace(boolean v) {
        GlTrace.enabled = v;
        if (v) GlTrace.requestDump();
    }

    public static void setTreeLod(double px) {
        TreeRenderer.lodMinPx = Math.max(0.0, px);
    }

    // 0 low, 1 medium, 2 high.
    public static void setTreeDetail(int level) {
        TreeDetail.set(level);
    }

    public static void setGpuTimer(boolean v) {
        GpuTimer.enabled = v;
    }

    public static void setGrassVao(boolean v) {
        WindSwayGrassDrawer.useVao = v;
    }

    public static void setTreeVao(boolean v) {
        TreeRenderer.useVao = v;
    }

    public static void setGrassSlots(int n) {
        WindSwayGrassDrawer.slotsWanted = Math.max(1, Math.min(WindSwayGrassDrawer.MAX_SLOTS, n));
    }

    // mode -1 auto, 0 bypass, 1 copy_image, 2 blit; a change rebuilds the atlas.
    public static void setDepthAtlas(int mode, int size) {
        int m = Math.max(-1, Math.min(2, mode));
        boolean rebuild = (m > 0 && m != DepthAtlas.modeWanted) || (size > 0 && size != DepthAtlas.sizeWanted);
        DepthAtlas.modeWanted = m;
        if (size > 0) DepthAtlas.sizeWanted = size;
        if (rebuild) DepthAtlas.reinit = true;
    }

    public static void setDepthAtlas(int mode) {
        setDepthAtlas(mode, 0);
    }

    private static volatile boolean enqueueFailedLogged = false;

    // Render thread: GL errors the probes caught.
    static int glErrors;

    private static long heartbeatMs;

    // Every five minutes one line with what a truncated console.txt loses:
    // the GPU and the counters that name a failing part.
    private static void heartbeat() {
        long now = System.currentTimeMillis();
        if (heartbeatMs == 0L) {
            heartbeatMs = now - 240000L;
            return;
        }
        if (now - heartbeatMs < 300000L) return;
        heartbeatMs = now;
        status();
    }

    private static void status() {
        trace("status: " + TreeRenderer.glInfo + " | enabled=" + enabled
                + " | trees " + TreeRenderer.statusLine()
                + " | grass " + WindSwayGrassDrawer.statusLine()
                + " | atlas " + DepthAtlas.statusLine()
                + " | glErrors=" + glErrors);
    }

    // A session shorter than the heartbeat still leaves one status line.
    public static void onLuaReload() {
        if (enabled && heartbeatMs != 0L) status();
        enabled = false;
        heartbeatMs = 0L;
    }

    public static void setFlushPrecise(boolean v) {
        BatchSequencer.flushPrecise = v;
    }

    public static void setWallCapture(boolean on) {
        GrassCapture.wallCapture = on;
    }

    // The drain and the per-world re-arm run whatever the switch says: a
    // world entered with the mod off must find fresh latches and compiled
    // shaders when the options screen turns it on.
    public static void onTranslucentPassDone(int playerIndex, int z) {
        try {
            long t0 = debugLog ? System.nanoTime() : 0L;
            BatchSequencer.passDone();
            WindSwayGrassDrawer.onPassDone();
            if (t0 != 0L) DebugStats.gtPassNs += System.nanoTime() - t0;
            IsoWorld world = IsoWorld.instance;
            if (world != lastWorld) {
                lastWorld = world;
                rearm();
                warmUp();
            }
            heartbeat();
            if (!enabled) return;

            DebugStats.onPassEnd();
        } catch (Throwable t) {
            if (!enqueueFailedLogged) {
                enqueueFailedLogged = true;
                trace("enqueue failed", t);
            }
        }
    }

    // Render thread. GL errors never throw; the error flag is sampled
    // around the first few batches of a session and every few seconds after
    // that. Never per batch: the query can sync with the driver thread.
    static final class GlProbe {
        private int samples;
        private long lastMs;

        boolean begin() {
            if (samples >= 8 && System.currentTimeMillis() - lastMs < 5000L) return false;
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
            }
            return true;
        }

        int end() {
            samples++;
            lastMs = System.currentTimeMillis();
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) ++glErrors;
            return err;
        }

        void reset() {
            samples = 0;
        }
    }

    // Render thread. The engine compiles with DebugType.Shader at Off:
    // unit info logs go through debugln, link errors through error(), both
    // gated by that severity, so a failed compile leaves no text in
    // console.txt. Recompile once with the severity opened so the driver's
    // message lands in the log.
    static boolean recompileShaderWithLog(ShaderProgram program) {
        LogSeverity prev = DebugType.Shader.getLogSeverity();
        boolean open = !DebugType.Shader.isEnabled(LogSeverity.Debug);
        if (open) DebugType.Shader.setLogSeverity(LogSeverity.Debug);
        try {
            trace("shader " + program.getName() + " failed to compile, retrying with the shader log open");
            program.compile();
        } catch (Throwable t) {
            trace("shader retry failed", t);
        } finally {
            if (open) DebugType.Shader.setLogSeverity(prev);
        }
        return program.isCompiled();
    }

    public static void trace(String msg) {
        System.out.println("[WindSway] " + msg);
    }

    public static void trace(String msg, Throwable t) {
        System.out.println("[WindSway] " + msg);
        t.printStackTrace(System.out);
    }
}
