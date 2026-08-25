package pzmod.windsway;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import me.zed_0xff.zombie_buddy.Accessor;
import me.zed_0xff.zombie_buddy.Exposer;

import org.lwjgl.opengl.GL11;

import zombie.GameTime;
import zombie.config.BooleanConfigOption;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderSettings;
import zombie.core.opengl.Shader;
import zombie.core.opengl.ShaderProgram;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.characters.IsoGameCharacter;
import zombie.iso.PlayerCamera;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.fboRenderChunk.FBORenderTrees;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderObjectHighlight;
import zombie.iso.fboRenderChunk.FBORenderObjectOutline;
import zombie.iso.fboRenderChunk.ObjectRenderInfo;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoCarBatteryCharger;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireplace;
import zombie.iso.objects.IsoMolotovCocktail;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTrap;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.IsoZombieGiblets;
import zombie.iso.objects.ObjectRenderEffects;
import zombie.iso.objects.RenderEffectType;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.weather.ClimateManager;
import zombie.iso.weather.fx.WeatherFxMask;
import zombie.popman.ObjectPool;
import zombie.tileDepth.TileDepthMapManager;
import zombie.tileDepth.TileDepthTexture;
import zombie.tileDepth.TileDepthTextureManager;

// Kahlua global "WindSwayMod" (simple class name); console calls need
// the prefix.
@Exposer.LuaClass
public class WindSwayMod {

    public static volatile boolean enabled = true;

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    private static Field windOptionField;

    // The forced getter would put a tick in the options screen that Apply
    // writes back into options.ini.
    public static boolean vanillaWindSpriteEffects() {
        try {
            Field f = windOptionField;
            if (f == null) {
                f = Accessor.findField(Core.class, "optionDoWindSpriteEffects");
                if (f == null) throw new NoSuchFieldException("optionDoWindSpriteEffects");
                f.setAccessible(true);
                windOptionField = f;
            }
            return ((BooleanConfigOption) f.get(Core.getInstance())).getValue();
        } catch (Throwable t) {
            trace("vanilla wind option read failed: " + t);
            return Core.getInstance().getOptionDoWindSpriteEffects();
        }
    }

    // The two option sliders: remap bases for the plant channel
    // (getWindTickFinal, Patch_ClimateManager) and the tree channel
    // (TreeSway). Vanilla wind sits near zero for hours on calm days.
    public static volatile double windFloor = 0.2;

    public static void setWindFloor(double v) {
        windFloor = v;
    }

    public static volatile double treeWindFloor = 0.2;

    public static void setTreeWindFloor(double v) {
        treeWindFloor = v;
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
        TreeSway.bushPeriod = periodSeconds;
    }

    public static void setPlantSway(double ampMax, double pow, double periodSeconds) {
        TreeSway.plantAmpMax = ampMax;
        TreeSway.plantAmpPow = pow;
        TreeSway.plantPeriod = periodSeconds;
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

    public static void setPlantGate(double start, double slope) {
        TreeSway.plantGateStart = Math.max(0.0, Math.min(1.0, start));
        TreeSway.plantGateSlope = Math.max(0.0, Math.min(1.0, slope));
    }

    public static void setTreeStormSway(double onset, double gain, double hold) {
        TreeSway.stormOnset = onset;
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

    public static void setTreeGiant(double boost, double onset, double full) {
        TreeRenderer.giantBoost = boost;
        TreeRenderer.giantOnset = onset;
        TreeRenderer.giantFull = full;
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

    public static void setTreeJitter(double v) {
        TreeRenderer.treeJitter = v;
    }

    public static void setTreeLayers(boolean main, boolean branch, boolean leaf) {
        TreeRenderer.mainOn = main;
        TreeRenderer.branchOn = branch;
        TreeRenderer.leafOn = leaf;
    }

    public static void setTreeQuality(boolean lobes, boolean octave2, boolean leaves, boolean mask, boolean shade) {
        TreeRenderer.qualLobes = lobes;
        TreeRenderer.qualOctave2 = octave2;
        TreeRenderer.qualLeaves = leaves;
        TreeRenderer.qualMask = mask;
        TreeRenderer.qualShade = shade;
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
            double tf = treeWindFloor;
            double pf = windFloor;
            double treeCh = Math.max(tf, raw);
            double plantCh = Math.max(pf, raw);
            double gT = rustleGain(Math.max(0.0, Math.min(1.0, (treeCh - 0.08) / 0.92)));
            double gP = rustleGain(Math.max(0.0, Math.min(1.0, (plantCh - 0.02) / 0.98)));
            lastRustleGain = gT;
            // randomRustle jitters flora with no visible cause; it only
            // ever feeds the tree-family pools, so undo it fully.
            Object rr = rrField.get(null);
            if (rr != null) {
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
            // (axe shudder) travel through the ORE.
            ObjectRenderEffects pool = TreeRenderer.active() ? poolOf(ore) : null;
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

    // Arms every latch again. Called per new world and from the console.
    public static void rearm() {
        WindSwayGrassDrawer.rearm();
        TreeRenderer.rearm();
        TreeSway.rearm();
        DepthAtlas.rearm();
        rustleOk = true;
        treeOreScaleOk = true;
        mergeOk = true;
        treePoolOk = true;
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
                SpriteRenderer.instance.drawGeneric(new WindSwayGrassDrawer());
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

    public static void setTreeLod(double px) {
        TreeRenderer.lodMinPx = Math.max(0.0, px);
    }

    public static void setGpuTimer(boolean v) {
        GpuTimer.enabled = v;
    }

    public static void setGrassVao(boolean v) {
        WindSwayGrassDrawer.useVao = v;
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
    private static volatile boolean captureFailedLogged = false;
    private static volatile boolean firstCaptureLogged = false;

    // Game-thread only. Drained mid-pass (onVanillaTranslucentDraw) and
    // at pass end (onTranslucentPassDone).
    private static ArrayList<WindSwayGrassDrawer.GrassQuad> pendingQuads = new ArrayList<>();
    private static long lastWindLog = 0L;
    private static int diagAlphaSkips = 0;
    private static int lastFrameCount = -1;
    private static int frames5s = 0;

    // Diagnostic: counts and names objects handed back to vanilla.
    private static final HashMap<String, Integer> rejectCounts = new HashMap<>();
    private static final ArrayList<String> rejectSeen = new ArrayList<>();
    private static final HashSet<String> rejectSeenSet = new HashSet<>();
    private static int rejectSeenPrinted = 0;

    private static boolean reject(String reason, IsoSprite sprite) {
        if (!debugLog) return false;
        rejectCounts.merge(reason, 1, Integer::sum);
        if (rejectSeenSet.size() < 80) {
            String entry = reason + ":" + (sprite != null && sprite.name != null ? sprite.name : "?");
            if (rejectSeenSet.add(entry)) {
                rejectSeen.add(entry);
            }
        }
        return false;
    }

    // Batch order = capture order = vanilla paint order; against no-depth-
    // write translucents (fences, doors, handed-back objects) it is kept by
    // flushing before any such draw that can overlap the pending bounds.
    // Depth cannot stand in for paint order: neighbouring squares'
    // [zNear,zFar] ranges overlap and blade depth interleaves across them.
    private static boolean pendBoundsValid = false;
    private static float pendMinX;
    private static float pendMinY;
    private static float pendMaxX;
    private static float pendMaxY;

    // Screen reach of a tile sprite from its square anchor in scene ortho
    // pixels (largest tile sprites: 256x512 plus offsets). False positive
    // = one extra flush; false negative = grass over a fence.
    private static final float OVERLAP_PAD = 768.0f;

    private static int flushCount5s = 0;
    private static int flushQuads5s = 0;
    private static int maxBatch5s = 0;

    // Diagnostic: flush causes for the 5s log.
    private static int flushDoor5s = 0;
    private static int flushObj5s = 0;
    private static int flushTree5s = 0;
    private static int flushPass5s = 0;
    private static int gateSkip5s = 0;
    private static final ArrayList<String> flushSeen = new ArrayList<>();
    private static final HashSet<String> flushSeenSet = new HashSet<>();
    private static int flushSeenPrinted = 0;

    private static void noteFlushTrigger(IsoObject object, boolean doorOrWall) {
        if (!debugLog) return;
        if (doorOrWall) {
            flushDoor5s++;
        } else {
            flushObj5s++;
        }
        if (flushSeenSet.size() < 40) {
            IsoSprite spr = object != null ? object.getSprite() : null;
            String entry = (doorOrWall ? "door:" : "obj:")
                    + (spr != null && spr.name != null ? spr.name
                            : object != null ? object.getClass().getSimpleName() : "null");
            if (flushSeenSet.add(entry)) {
                flushSeen.add(entry);
            }
        }
    }

    private static void extendPendingBounds(WindSwayGrassDrawer.GrassQuad q) {
        float x0 = q.ox - q.padL + Math.min(q.ox1, q.ox4) * q.w;
        float x1 = q.ox + q.w + q.padR + Math.max(q.ox2, q.ox3) * q.w;
        float y0 = q.oy + Math.min(q.oy1, q.oy2) * q.h;
        float y1 = q.oy + q.h + Math.max(q.oy3, q.oy4) * q.h;
        if (!pendBoundsValid) {
            pendBoundsValid = true;
            pendMinX = x0;
            pendMaxX = x1;
            pendMinY = y0;
            pendMaxY = y1;
        } else {
            pendMinX = Math.min(pendMinX, x0);
            pendMaxX = Math.max(pendMaxX, x1);
            pendMinY = Math.min(pendMinY, y0);
            pendMaxY = Math.max(pendMaxY, y1);
        }
    }

    static volatile boolean flushPrecise = true;

    public static void setFlushPrecise(boolean v) {
        flushPrecise = v;
    }

    private static final float[] objRect = new float[4];
    private static final float OBJ_SLACK = 96.0f;

    // Tile frame of a vanilla-drawn object, x1 y1 x2 y2 in offscreen px: anchor
    // minus sprite offsets, untrimmed texture size, a margin for attachments.
    private static boolean objectRect(IsoObject object, IsoGridSquare square, float[] out) {
        IsoSprite sprite = object.getSprite();
        if (sprite == null) return false;
        IsoSpriteInstance inst = sprite.def;
        float ix = inst != null ? inst.offX : 0.0f;
        float iy = inst != null ? inst.offY : 0.0f;
        float iz = inst != null ? inst.offZ : 0.0f;
        float sx = IsoUtils.XToScreen(square.x + ix, square.y + iy, square.z + iz, 0)
                - IsoCamera.frameState.offX - object.offsetX;
        float sy = IsoUtils.YToScreen(square.x + ix, square.y + iy, square.z + iz, 0)
                - IsoCamera.frameState.offY - (object.offsetY + object.getRenderYOffset() * (float) Core.tileScale);
        float w = 64.0f * Core.tileScale;
        float h = 128.0f * Core.tileScale;
        Texture tex = sprite.getTextureForCurrentFrame(object.getDir(), object);
        if (tex != null) {
            w = Math.max(w, tex.getWidthOrig());
            h = Math.max(h, tex.getHeightOrig());
        }
        out[0] = sx - OBJ_SLACK;
        out[1] = sy - OBJ_SLACK;
        out[2] = sx + w + OBJ_SLACK;
        out[3] = sy + h + OBJ_SLACK;
        return true;
    }

    private static boolean rectsHitPending(float[] r, int n) {
        if (!pendBoundsValid) return false;
        for (int i = 0; i < n; ++i) {
            if (r[i * 4] < pendMaxX && r[i * 4 + 2] > pendMinX
                    && r[i * 4 + 1] < pendMaxY && r[i * 4 + 3] > pendMinY) {
                return true;
            }
        }
        return false;
    }

    private static boolean rectsHitQuads(float[] r, int n) {
        float ux0 = Float.MAX_VALUE;
        float uy0 = Float.MAX_VALUE;
        float ux1 = -Float.MAX_VALUE;
        float uy1 = -Float.MAX_VALUE;
        for (int i = 0; i < n; ++i) {
            ux0 = Math.min(ux0, r[i * 4]);
            uy0 = Math.min(uy0, r[i * 4 + 1]);
            ux1 = Math.max(ux1, r[i * 4 + 2]);
            uy1 = Math.max(uy1, r[i * 4 + 3]);
        }
        for (int k = 0; k < pendingQuads.size(); ++k) {
            WindSwayGrassDrawer.GrassQuad q = pendingQuads.get(k);
            float x0 = q.ox - q.padL + Math.min(q.ox1, q.ox4) * q.w;
            float x1 = q.ox + q.w + q.padR + Math.max(q.ox2, q.ox3) * q.w;
            float y0 = q.oy + Math.min(q.oy1, q.oy2) * q.h;
            float y1 = q.oy + q.h + Math.max(q.oy3, q.oy4) * q.h;
            if (ux0 >= x1 || ux1 <= x0 || uy0 >= y1 || uy1 <= y0) continue;
            for (int i = 0; i < n; ++i) {
                if (r[i * 4] < x1 && r[i * 4 + 2] > x0 && r[i * 4 + 1] < y1 && r[i * 4 + 3] > y0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void flushPending() {
        if (pendingQuads.isEmpty()) return;
        int n = pendingQuads.size();
        WindSwayGrassDrawer grass = new WindSwayGrassDrawer();
        grass.set(pendingQuads);
        pendingQuads = new ArrayList<>();
        pendBoundsValid = false;
        SpriteRenderer.instance.drawGeneric(grass);
        if (debugLog) {
            flushCount5s++;
            flushQuads5s += n;
            if (n > maxBatch5s) {
                maxBatch5s = n;
            }
        }
    }

    // Flush before a no-depth-write vanilla translucent that can touch the
    // pending batches: held trees first (paint order), then the grass. Trees
    // and character models write depth and need none.
    public static void onVanillaTranslucentDraw(IsoObject object, boolean doorOrWall) {
        try {
            if (pendingQuads.isEmpty() && pendingTrees == null) return;
            if (!FBORenderCell.instance.renderTranslucentOnly) return;
            if (object instanceof IsoTree) return;
            IsoGridSquare square = object != null ? object.getSquare() : null;
            if (square == null) {
                flushPendingTrees(FLUSH_TREES_OBJ);
                noteFlushTrigger(object, doorOrWall);
                flushPending();
                return;
            }
            boolean precise = flushPrecise && objectRect(object, square, objRect);
            if (!precise) {
                float ax = IsoUtils.XToScreen(square.x, square.y, square.z, 0) - IsoCamera.frameState.offX;
                float ay = IsoUtils.YToScreen(square.x, square.y, square.z, 0) - IsoCamera.frameState.offY;
                objRect[0] = ax - TREE_OVERLAP_PAD;
                objRect[1] = ay - TREE_OVERLAP_PAD;
                objRect[2] = ax + TREE_OVERLAP_PAD;
                objRect[3] = ay + TREE_OVERLAP_PAD;
            }
            // Union box first: the per-tree and per-quad scans are the
            // expensive part and the miss is the common case.
            if (pendingTrees != null) {
                boolean hit = rectHitsTreeUnion(objRect) && (!precise || rectHitsTrees(objRect));
                if (hit) {
                    flushPendingTrees(FLUSH_TREES_OBJ);
                } else if (debugLog) {
                    treeGateSkip5s++;
                }
            }
            if (pendingQuads.isEmpty()) return;
            if (!precise) {
                float ax = 0.5f * (objRect[0] + objRect[2]);
                float ay = 0.5f * (objRect[1] + objRect[3]);
                objRect[0] = ax - OVERLAP_PAD;
                objRect[1] = ay - OVERLAP_PAD;
                objRect[2] = ax + OVERLAP_PAD;
                objRect[3] = ay + OVERLAP_PAD;
            }
            boolean hitGrass = rectsHitPending(objRect, 1) && (!precise || rectsHitQuads(objRect, 1));
            if (!hitGrass) {
                if (debugLog) gateSkip5s++;
                return;
            }
            noteFlushTrigger(object, doorOrWall);
            flushPending();
        } catch (Throwable t) {
            // Ordering beats batching: if the bounds test dies, draw what
            // we have.
            flushPendingTrees(FLUSH_TREES_OBJ);
            flushPending();
        }
    }

    // Tree list merge. Vanilla cuts FBORenderTrees.current at every non-tree
    // translucent in paint order (hundreds of one-tree lists per frame in a
    // forest). Lists are held (drawGeneric skipped) and merged into one
    // pending list, queued when something that must paint after them arrives.
    // Valid because everything between two opaque lists depth-tests against
    // the trees and writes no depth. A see-through tree breaks that for the
    // grass captured before it: such a list flushes trees and grass first and
    // becomes the new pending list. Tree objects come from one ownerless pool.
    private static FBORenderTrees pendingTrees;
    private static int pendingTreeFrame = -1;
    private static boolean treeFlushing;
    private static float treeMinX;
    private static float treeMinY;
    private static float treeMaxX;
    private static float treeMaxY;
    // Screen reach from the SE anchor: an XXL frame is 896 x 1024 scene px
    // plus the pad.
    private static final float TREE_OVERLAP_PAD = 1600.0f;
    private static final int FLUSH_TREES_OBJ = 0;
    private static final int FLUSH_TREES_SEE = 1;
    private static final int FLUSH_TREES_PASS = 2;
    private static Field treePoolField;
    private static boolean treePoolOk = true;
    private static boolean mergeOk = true;
    private static int held5s;
    private static int merged5s;
    private static int treeFlushObj5s;
    private static int treeFlushSee5s;
    private static int treeFlushPass5s;
    private static int treeGateSkip5s;
    private static int mergedTrees5s;
    private static int mergedMax5s;
    private static int seeStencil5s;
    private static int seeTransp5s;
    private static int seeFade5s;
    private static int seeCut5s;
    // Dry run of the see-through flush skip.
    private static int seeLists5s;
    private static int seeSkipRect5s;
    private static int seeSkipBbox5s;
    private static int seeSkipQuad5s;
    private static final float[] holeRects = new float[8];
    private static final float[] seeRects = new float[64 * 4];

    // skipOn advice on SpriteRenderer.drawGeneric: true = the list is held.
    public static boolean onTreeListDraw(Object drawer) {
        if (!(drawer instanceof FBORenderTrees)) return false;
        if (treeFlushing) return false;
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return false;
            if (pendingQuads.isEmpty() && pendingTrees == null
                    && (!enabled || !mergeOk || !TreeRenderer.active())) {
                return false;
            }
            FBORenderTrees list = (FBORenderTrees) drawer;
            int seeKind = TreeRenderer.seeThroughKind(list);
            boolean seeThrough = seeKind != 0;
            // A see-through list needs the grass flush only where its see-through
            // pixels meet a pending quad.
            boolean seeFlush = seeThrough;
            if (seeThrough && flushPrecise) {
                int holeN = StencilHole.rects(holeRects);
                int n = TreeRenderer.seeThroughRects(list, holeRects, holeN, seeRects);
                boolean bboxHit = n != 0 && (n < 0 || rectsHitPending(seeRects, n));
                boolean quadHit = bboxHit && (n < 0 || rectsHitQuads(seeRects, n));
                seeFlush = quadHit;
                if (debugLog) {
                    if (n == 0) seeSkipRect5s++;
                    else if (!bboxHit) seeSkipBbox5s++;
                    else if (!quadHit) seeSkipQuad5s++;
                }
            }
            if (debugLog && seeThrough) {
                seeLists5s++;
                if ((seeKind & TreeRenderer.SEE_STENCIL) != 0) seeStencil5s++;
                if ((seeKind & TreeRenderer.SEE_TRANSPARENT) != 0) seeTransp5s++;
                if ((seeKind & TreeRenderer.SEE_FADE) != 0) seeFade5s++;
                if ((seeKind & TreeRenderer.SEE_CUTAWAY) != 0) seeCut5s++;
            }
            if (!enabled || !mergeOk || !TreeRenderer.active()) {
                // Vanilla draws the lists; only the grass order matters.
                if (seeFlush && !pendingQuads.isEmpty()) {
                    if (debugLog) flushTree5s++;
                    flushPending();
                }
                return false;
            }
            int frame = IsoCamera.frameState.frameCount;
            if (pendingTrees != null && pendingTreeFrame != frame) {
                // A pass that ended without its OnExit left this behind; the
                // list was never queued, so vanilla's release is ours to call.
                trace("stale tree list dropped");
                pendingTrees.postRender();
                pendingTrees = null;
            }
            if (seeFlush) {
                flushPendingTrees(FLUSH_TREES_SEE);
                if (!pendingQuads.isEmpty()) {
                    if (debugLog) flushTree5s++;
                    flushPending();
                }
            }
            ArrayList<?> src = TreeRenderer.trees(list);
            if (src.isEmpty()) return false;
            if (pendingTrees == null) {
                pendingTrees = list;
                pendingTreeFrame = frame;
                treeMinX = Float.MAX_VALUE;
                treeMinY = Float.MAX_VALUE;
                treeMaxX = -Float.MAX_VALUE;
                treeMaxY = -Float.MAX_VALUE;
                extendTreeBounds(src, 0);
                if (debugLog) held5s++;
                return true;
            }
            ArrayList<?> dst = TreeRenderer.trees(pendingTrees);
            int at = dst.size();
            @SuppressWarnings("unchecked")
            ArrayList<Object> dstObj = (ArrayList<Object>) dst;
            // addAll copies the source to an array first.
            for (int i = 0; i < src.size(); ++i) {
                dstObj.add(src.get(i));
            }
            extendTreeBounds(dst, at);
            src.clear();
            recycleList(list);
            if (debugLog) merged5s++;
            return true;
        } catch (Throwable t) {
            mergeOk = false;
            trace("tree list merge disabled: " + t, t);
            flushPendingTrees(FLUSH_TREES_OBJ);
            flushPending();
            return false;
        }
    }

    // Screen boxes of the held trees (x1 y1 x2 y2), computed once per tree:
    // the gate below runs per vanilla draw.
    private static float[] treeBoxes = new float[4 * 256];
    private static int treeBoxCount;

    private static void extendTreeBounds(ArrayList<?> trees, int from) throws Throwable {
        int n = trees.size();
        if (treeBoxes.length < n * 4) {
            int cap = treeBoxes.length;
            while (cap < n * 4) cap *= 2;
            float[] grown = new float[cap];
            System.arraycopy(treeBoxes, 0, grown, 0, treeBoxCount * 4);
            treeBoxes = grown;
        }
        float[] boxes = treeBoxes;
        for (int i = from; i < n; ++i) {
            TreeRenderer.treeBox(trees.get(i), treeBox);
            int k = i * 4;
            boxes[k] = treeBox[0];
            boxes[k + 1] = treeBox[1];
            boxes[k + 2] = treeBox[2];
            boxes[k + 3] = treeBox[3];
            if (treeBox[0] < treeMinX) treeMinX = treeBox[0];
            if (treeBox[2] > treeMaxX) treeMaxX = treeBox[2];
            if (treeBox[1] < treeMinY) treeMinY = treeBox[1];
            if (treeBox[3] > treeMaxY) treeMaxY = treeBox[3];
        }
        treeBoxCount = n;
    }

    private static final float[] treeBox = new float[4];

    private static boolean rectHitsTreeUnion(float[] r) {
        return r[0] < treeMaxX && r[2] > treeMinX && r[1] < treeMaxY && r[3] > treeMinY;
    }

    private static boolean rectHitsTrees(float[] r) {
        float[] boxes = treeBoxes;
        for (int i = 0, k = 0; i < treeBoxCount; ++i, k += 4) {
            if (boxes[k] < r[2] && boxes[k + 2] > r[0] && boxes[k + 1] < r[3] && boxes[k + 3] > r[1]) {
                return true;
            }
        }
        return false;
    }

    // Back to FBORenderTrees.s_pool (package-private); a list never queued is
    // otherwise garbage, not a double release.
    private static void recycleList(FBORenderTrees list) {
        if (!treePoolOk) return;
        try {
            Field f = treePoolField;
            if (f == null) {
                f = Accessor.findField(FBORenderTrees.class, "s_pool");
                if (f == null) throw new NoSuchFieldException("s_pool");
                f.setAccessible(true);
                treePoolField = f;
            }
            if (!TreeRenderer.trees(list).isEmpty()) return;
            @SuppressWarnings("unchecked")
            ObjectPool<FBORenderTrees> pool = (ObjectPool<FBORenderTrees>) f.get(null);
            pool.release(list);
        } catch (Throwable t) {
            treePoolOk = false;
            trace("tree list recycling disabled: " + t);
        }
    }

    private static void flushPendingTrees(int cause) {
        FBORenderTrees list = pendingTrees;
        if (list == null) return;
        pendingTrees = null;
        treeFlushing = true;
        try {
            if (debugLog) {
                int n = 0;
                try {
                    n = TreeRenderer.trees(list).size();
                } catch (Throwable ignored) {
                }
                mergedTrees5s += n;
                if (n > mergedMax5s) mergedMax5s = n;
                if (cause == FLUSH_TREES_OBJ) treeFlushObj5s++;
                else if (cause == FLUSH_TREES_SEE) treeFlushSee5s++;
                else treeFlushPass5s++;
            }
            SpriteRenderer.instance.drawGeneric(list);
        } finally {
            treeFlushing = false;
        }
    }

    // Screen-right is world (+1, -1): a blade bent that way crosses the W edge
    // of column x+1 or the N edge of row y; screen-left mirrors it. Bits 2
    // (left) / 4 (right) mark a fence or wall on one of those edges.
    // nav[] is geometric adjacency (doGridNav), unlike n/s/e/w, which the
    // path finder nulls at blocked edges.
    private static float barrierCode(IsoGridSquare sq) {
        IsoGridSquare s = sq.getAdjacentSquare(IsoDirections.S);
        IsoGridSquare sw = sq.getAdjacentSquare(IsoDirections.SW);
        IsoGridSquare e = sq.getAdjacentSquare(IsoDirections.E);
        IsoGridSquare ne = sq.getAdjacentSquare(IsoDirections.NE);
        float code = 0.0f;
        if (westEdge(sq) || westEdge(s) || northEdge(s) || northEdge(sw)) {
            code += 2.0f;
        }
        if (westEdge(e) || westEdge(ne) || northEdge(sq) || northEdge(e)) {
            code += 4.0f;
        }
        return code;
    }

    private static boolean westEdge(IsoGridSquare s) {
        return s != null && (s.has(IsoFlagType.cutW) || s.has(IsoFlagType.collideW));
    }

    private static boolean northEdge(IsoGridSquare s) {
        return s != null && (s.has(IsoFlagType.cutN) || s.has(IsoFlagType.collideN));
    }

    // skipOn advice (Patch_FBORenderCell): true = engine skips the
    // object's own draw. renderTranslucent computed targetAlpha before us.
    public static boolean tryCaptureGrass(IsoObject object) {
        boolean captured = captureGrassInner(object);
        if (!captured) {
            onVanillaTranslucentDraw(object, false);
        }
        return captured;
    }

    private static boolean captureGrassInner(IsoObject object) {
        if (!enabled) return false;
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return false;
            if (!Core.getInstance().getOptionDoWindSpriteEffects()) return false;
            if (!WindSwayGrassDrawer.ready()) return false;
            // setupTileDepth's special-object list (chunk depth or own
            // shader). Everything else on this path is a tile-depth quad;
            // capturing non-wind objects too keeps a field one pipeline
            // instead of a flush storm.
            if (object instanceof IsoTree || object instanceof IsoGameCharacter
                    || object instanceof IsoFire || object instanceof IsoFireplace
                    || object instanceof IsoWorldInventoryObject
                    || object instanceof IsoZombieGiblets
                    || object instanceof IsoMolotovCocktail
                    || object instanceof IsoCarBatteryCharger
                    || object instanceof IsoBarbecue
                    || object instanceof IsoTrap) {
                return false;
            }
            IsoSprite sprite = object.getSprite();
            if (sprite == null) return false;
            if (!rendersViaIsoObject(object.getClass())) return reject("ownRender", sprite);
            IsoGridSquare square = object.getSquare();
            if (square == null) return reject("noSquare", sprite);
            // IsoObject.render draws nothing for these; capture as nothing
            // but keep the targetAlpha handoff.
            if (!object.getDoRender() || object.isSpriteInvisible()) {
                int pi = IsoCamera.frameState.playerIndex;
                object.setTargetAlpha(pi, object.getRenderInfo(pi).targetAlpha);
                return true;
            }
            // Rendered as 3D models in vanilla, not sprites.
            if (object.getSpriteModel() != null) return reject("model", sprite);
            // Highlight blending (hover/selection) stays vanilla.
            if (FBORenderObjectHighlight.getInstance().shouldRenderObjectHighlight(object)) {
                return reject("highlight", sprite);
            }
            // Windows and wall overlays get directional wall depth
            // (setupWallDepth), not replicated.
            if (sprite.getProperties().has(IsoFlagType.windowN)
                    || sprite.getProperties().has(IsoFlagType.windowW)
                    || sprite.getProperties().has(IsoFlagType.WallOverlay)) {
                return reject("wallDepth", sprite);
            }
            // Animated attachments draw via a separate engine call after
            // renderMinusFloor that our skip does not cover; capturing the
            // body would flush it behind its own attachments.
            if (object.hasAnimatedAttachments()) return reject("animAttach", sprite);
            IsoSpriteInstance inst = sprite.def;
            if (inst == null) return reject("noDef", sprite);
            Texture tex = sprite.getTextureForCurrentFrame(object.getDir(), object);
            if (tex == null || tex.getTextureId() == null) return reject("noTex", sprite);
            Texture mainDepthTex = selectDepthTexture(sprite, object);
            if (mainDepthTex == null || mainDepthTex.getTextureId() == null) return reject("noDepthTex", sprite);

            // performRenderFrame's tileScale fixups, local copy (vanilla
            // applies them by mutating the shared def in the skipped draw).
            float scaleX = inst.scaleX;
            float scaleY = inst.scaleY;
            int wOrig = tex.getWidthOrig();
            int hOrig = tex.getHeightOrig();
            if (Core.tileScale == 2 && wOrig == 64 && hOrig == 128) {
                scaleX = 2.0f;
                scaleY = 2.0f;
            }
            if (Core.tileScale == 2 && scaleX == 2.0f && scaleY == 2.0f && wOrig == 128 && hOrig == 256) {
                scaleX = 1.0f;
                scaleY = 1.0f;
            }
            if (scaleX <= 0.0f || scaleY <= 0.0f) return reject("badScale", sprite);

            int playerIndex = IsoCamera.frameState.playerIndex;
            PlayerCamera camera = IsoCamera.cameras[playerIndex];

            // Vanilla draws with pre-step alpha and steps afterwards; step
            // only on paths that return true, on fallback vanilla steps
            // itself.
            float target = object.getRenderInfo(playerIndex).targetAlpha;
            object.setTargetAlpha(playerIndex, target);
            float alpha = object.getAlpha(playerIndex);
            if (alpha <= 0.01f) {
                if (debugLog) diagAlphaSkips++;
                stepAlphaLikeVanilla(object, square, playerIndex, target);
                return true;
            }

            // All-or-nothing: any undrawable part sends the whole object
            // back to vanilla, half objects read as holes.
            ArrayList<IsoSpriteInstance> attachments = object.getAttachedAnimSprite();
            int attachedCount = attachments != null ? attachments.size() : 0;

            // Vanilla reuses obj.sx from the main draw for attachments;
            // their instance offsets never enter the anchor.
            float offsetXParam = object.offsetX;
            float offsetYParam = object.offsetY + object.getRenderYOffset() * (float) Core.tileScale;
            float baseSx = IsoUtils.XToScreen(square.x + inst.offX, square.y + inst.offY, square.z + inst.offZ, 0);
            float baseSy = IsoUtils.YToScreen(square.x + inst.offX, square.y + inst.offY, square.z + inst.offZ, 0);
            baseSx -= offsetXParam;
            baseSy -= offsetYParam;
            baseSx += -IsoCamera.frameState.offX;
            baseSy += -IsoCamera.frameState.offY;
            // Picker anchor: vanilla sx - offX, no jiggly term.
            float pickerX = baseSx;
            float pickerY = baseSy;
            float zoom = IsoCamera.frameState.zoom;
            baseSx += camera.fixJigglyModelsX * zoom;
            baseSy += camera.fixJigglyModelsY * zoom;

            // startTileDepthShader, translucent branch: near = SE corner
            // one level up.
            float jx = square.x + camera.fixJigglyModelsSquareX;
            float jy = square.y + camera.fixJigglyModelsSquareY;
            int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
            int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
            float zFar = IsoDepthHelper.getSquareDepthData(camX, camY, jx, jy, square.z).depthStart;
            float zNear = IsoDepthHelper.getSquareDepthData(camX, camY, jx + 1.0f, jy + 1.0f, square.z + 1.0f).depthStart;
            float yOff = object.getRenderYOffset();
            if (yOff != 0.0f) {
                float dz = yOff / 96.0f * 0.0028867084f;
                zFar -= dz;
                zNear -= dz;
            }

            // Raw square light feeds overlay and attachments; only the
            // main sprite gets customColor and forceAmbient. The upper
            // part of a multi-level object in a collapsed building is lit
            // from the square below (renderMinusFloor_NotDoorOrWall);
            // only the overlaySpriteColor path reads the object's own
            // square (renderOverlaySprites).
            ColorInfo ownLi = square.getLightInfo(playerIndex);
            ColorInfo li = ownLi;
            if (FBORenderCutaways.getInstance().isForceRenderSquare(playerIndex, square)) {
                IsoGridSquare below = square.getCell().getGridSquare(square.x, square.y, square.z - 1);
                if (below != null) li = below.getLightInfo(playerIndex);
            }
            float liR = 1.0f;
            float liG = 1.0f;
            float liB = 1.0f;
            float liA = 1.0f;
            if (li != null) {
                liR = li.r;
                liG = li.g;
                liB = li.b;
                liA = li.a;
            }
            float ownR = ownLi != null ? ownLi.r : 1.0f;
            float ownG = ownLi != null ? ownLi.g : 1.0f;
            float ownB = ownLi != null ? ownLi.b : 1.0f;
            // Blacked-out buildings and configRoomFade rooms: vanilla
            // scales rgb by 1 - fadeRatio (prepareToRender), forceAmbient
            // overrides it, overlays without overlaySpriteColor get it
            // twice (renderAttachedAndOverlaySpritesInternal, then
            // renderOverlaySprites again).
            float fade = 1.0f;
            if (FBORenderCell.instance.isBlackedOutBuildingSquare(square)) {
                fade = 1.0f - FBORenderCell.instance.getBlackedOutRoomFadeRatio(square);
            }
            float lr = liR * fade;
            float lg = liG * fade;
            float lb = liB * fade;
            ColorInfo custom = object.getCustomColor();
            if (custom != null) {
                lr *= custom.r;
                lg *= custom.g;
                lb *= custom.b;
            }
            if (sprite.forceAmbient) {
                float ambient = RenderSettings.getInstance().getAmbientForPlayer(playerIndex);
                if (object.isUseSnowSprite()) {
                    ambient = PZMath.clamp(ambient * 1.2f, 0.0f, 1.0f);
                }
                lr = ambient * object.tintr;
                lg = ambient * object.tintg;
                lb = ambient * object.tintb;
            }

            // Corner fractions get copied in buildPart: the shared pools
            // mutate on the game thread while the render thread draws.
            // Trample beats wind, as in performRenderFrame. Wind flora without a
            // trample bends in the shader: pool corners dropped, sway parameters on
            // the vertices.
            ObjectRenderEffects ore = object.getObjectRenderEffectsToApply();
            float windS = 0.0f;
            float windSeed = 0.0f;
            float windFrac = 0.0f;
            float windPeriod = 0.0f;
            if (sprite.moveWithWind && ore == object.getWindRenderEffects() && !rigidFlora(sprite)) {
                ore = null;
                windS = (float) ((square.x - square.y) / SQRT2);
                windSeed = TreeSway.hash(square.x * 7919 + square.y * 104729 + square.z * 31 + sprite.tileSheetIndex, 9);
                if (sprite.isBush) {
                    windFrac = (float) TreeSway.bushAmpMax;
                    windPeriod = (float) TreeSway.bushPeriod;
                } else {
                    double stiff = sprite.windType == 2 ? TreeSway.plantStiff2
                            : (sprite.windType == 3 ? TreeSway.plantStiff3 : 1.0);
                    windFrac = (float) (TreeSway.plantAmpMax * stiff);
                    windPeriod = (float) (TreeSway.plantPeriod / (0.7 + 0.3 * stiff));
                }
            }

            ArrayList<WindSwayGrassDrawer.GrassQuad> parts = partsScratch;
            parts.clear();
            parts.add(buildPart(tex, mainDepthTex, sprite, baseSx, baseSy,
                    scaleX, scaleY, scaleX, scaleY, inst.flip,
                    zNear, zFar, ore, lr, lg, lb, alpha));

            // renderOverlaySprites: after main, before attachments; own
            // color, copyTargetAlpha multiplies the object alpha.
            IsoSprite overlay = object.getOverlaySprite();
            if (overlay != null) {
                IsoSpriteInstance odef = overlay.def;
                if (odef == null) return reject("overlayPart", overlay);
                Texture otex = overlay.getTextureForCurrentFrame(object.getDir(), object);
                if (otex == null || otex.getTextureId() == null) return reject("overlayPart", overlay);
                Texture odepth = selectDepthTexture(overlay, object);
                if (odepth == null || odepth.getTextureId() == null) return reject("overlayPart", overlay);
                float ocr = liR * fade * fade;
                float ocg = liG * fade * fade;
                float ocb = liB * fade * fade;
                float oFactor = liA;
                ColorInfo osc = object.getOverlaySpriteColor();
                if (osc != null) {
                    ocr = osc.r * ownR * fade;
                    ocg = osc.g * ownG * fade;
                    ocb = osc.b * ownB * fade;
                    oFactor = osc.a;
                }
                float oAlpha = alpha;
                if (odef.copyTargetAlpha && oFactor != 1.0f) {
                    oAlpha = alpha * oFactor;
                }
                float oScaleX = odef.scaleX;
                float oScaleY = odef.scaleY;
                int oWOrig = otex.getWidthOrig();
                int oHOrig = otex.getHeightOrig();
                if (Core.tileScale == 2 && oWOrig == 64 && oHOrig == 128) {
                    oScaleX = 2.0f;
                    oScaleY = 2.0f;
                }
                if (Core.tileScale == 2 && oScaleX == 2.0f && oScaleY == 2.0f && oWOrig == 128 && oHOrig == 256) {
                    oScaleX = 1.0f;
                    oScaleY = 1.0f;
                }
                if (oScaleX > 0.0f && oScaleY > 0.0f && oAlpha > 0.001f) {
                    parts.add(buildPart(otex, odepth, overlay, baseSx, baseSy,
                            oScaleX, oScaleY, oScaleX, oScaleY, odef.flip,
                            zNear, zFar, ore, ocr, ocg, ocb, oAlpha));
                }
            }

            for (int i = 0; i < attachedCount; ++i) {
                IsoSpriteInstance s = attachments.get(i);
                IsoSprite spr = s != null ? s.parentSprite : null;
                if (spr == null) return reject("attachPart", sprite);
                int frame = 0;
                if (spr.hasAnimation()) {
                    int frameCount = spr.getFrameCount();
                    if (s.frame >= (float) frameCount) {
                        frame = frameCount - 1;
                    } else if (s.frame > 0.0f) {
                        frame = (int) s.frame;
                    }
                }
                Texture tex2 = spr.getTextureForFrame(frame, object.getDir(), object.isUseSnowSprite());
                if (tex2 == null || tex2.getTextureId() == null) return reject("attachPart", spr);
                Texture depthTex2 = selectDepthTexture(spr, object);
                if (depthTex2 == null || depthTex2.getTextureId() == null) return reject("attachPart", spr);
                float a2 = s.alpha;
                if (s.multiplyObjectAlpha) {
                    a2 *= alpha;
                }
                if (a2 <= 0.001f) continue;
                float sX2 = s.scaleX;
                float sY2 = s.scaleY;
                int wOrig2 = tex2.getWidthOrig();
                int hOrig2 = tex2.getHeightOrig();
                if (Core.tileScale == 2 && wOrig2 == 64 && hOrig2 == 128) {
                    sX2 = 2.0f;
                    sY2 = 2.0f;
                }
                if (Core.tileScale == 2 && sX2 == 2.0f && sY2 == 2.0f && wOrig2 == 128 && hOrig2 == 256) {
                    sX2 = 1.0f;
                    sY2 = 1.0f;
                }
                if (sX2 <= 0.0f || sY2 <= 0.0f) continue;
                // TileDepthModifier gets def scale for attachments, the
                // quad itself uses the instance scale (vanilla asymmetry).
                float uvSX = spr.def != null ? spr.def.scaleX : 1.0f;
                float uvSY = spr.def != null ? spr.def.scaleY : 1.0f;
                parts.add(buildPart(tex2, depthTex2, spr, baseSx, baseSy,
                        sX2, sY2, uvSX, uvSY, s.flip,
                        zNear, zFar, ore,
                        liR * fade * s.tintr, liG * fade * s.tintg, liB * fade * s.tintb, a2));
            }

            // Every part bends in the main part's frame, so a flower child stays on
            // its stalk.
            if (windFrac > 0.0f) {
                WindSwayGrassDrawer.GrassQuad main = parts.get(0);
                float frameTop = main.oy;
                float frameBottom = main.oy + main.h;
                float frameLeft = main.ox;
                float barrier = barrierCode(square);
                // Lean with the wind, swing past upright only by the upwind cap
                // (screen-right is downwind for dir > 0).
                boolean right = TreeSway.dir >= 0.0;
                for (int i = 0; i < parts.size(); ++i) {
                    WindSwayGrassDrawer.GrassQuad q = parts.get(i);
                    q.windS = windS;
                    q.windSeed = windSeed;
                    q.windPeriod = windPeriod;
                    q.windAmp = q.w * windFrac;
                    float down = plantPadOn ? TreeSway.plantReach(q.windAmp, true) : 0.0f;
                    float up = plantPadOn ? TreeSway.plantReach(q.windAmp, false) : 0.0f;
                    q.padL = right ? up : down;
                    q.padR = right ? down : up;
                    q.barrier = barrier;
                    float tU = (q.u1 - q.u0) / q.w;
                    float tV = (q.v1 - q.v0) / q.h;
                    q.frameTop = q.v0 + (frameTop - q.oy) * tV;
                    q.frameBottom = q.v0 + (frameBottom - q.oy) * tV;
                    q.frameLeft = q.u0 + (frameLeft - q.ox) * tU;
                }
            }
            // Canary: if the pass advice never drains us (weave failure),
            // nothing captured ever gets drawn.
            if (pendingQuads.size() > 100000) {
                pendingQuads.clear();
                pendBoundsValid = false;
                WindSwayGrassDrawer.fail("pending batch overflowed, pass advice not running?");
                return false;
            }
            for (int i = 0; i < parts.size(); ++i) {
                WindSwayGrassDrawer.GrassQuad q = parts.get(i);
                pendingQuads.add(q);
                extendPendingBounds(q);
            }
            parts.clear();
            // Object-picker click boxes; normally refilled by the draw
            // we skip.
            if (!WeatherFxMask.isRenderingMask()
                    && !FBORenderObjectHighlight.getInstance().isRendering()
                    && !FBORenderObjectOutline.getInstance().isRendering()) {
                ObjectRenderInfo ri = object.getRenderInfo(playerIndex);
                ri.renderX = pickerX;
                ri.renderY = pickerY;
                ri.renderWidth = wOrig * scaleX;
                ri.renderHeight = hOrig * scaleY;
                ri.renderScaleX = scaleX;
                ri.renderScaleY = scaleY;
                ri.renderAlpha = alpha;
            }
            // Vanilla advances attachment anims inside the skipped draw.
            for (int i = 0; i < attachedCount; ++i) {
                IsoSpriteInstance s = attachments.get(i);
                if (s != null) {
                    s.update();
                }
            }
            stepAlphaLikeVanilla(object, square, playerIndex, target);
            if (!firstCaptureLogged) {
                firstCaptureLogged = true;
                trace("first grass object captured: " + (sprite.name != null ? sprite.name : "?"));
            }
            return true;
        } catch (Throwable t) {
            if (!captureFailedLogged) {
                captureFailedLogged = true;
                trace("grass capture failed, falling back to vanilla draw", t);
            }
            return false;
        }
    }

    private static final double SQRT2 = Math.sqrt(2.0);
    // Game thread; the parts of one object between build and enqueue.
    private static final ArrayList<WindSwayGrassDrawer.GrassQuad> partsScratch = new ArrayList<>(8);

    // fencing_burnt_01 trunks carry MoveWithWind without a tree flag: not
    // grass.
    private static boolean rigidFlora(IsoSprite sprite) {
        String name = sprite.name;
        return name != null && name.startsWith("fencing_burnt");
    }

    // Own render overrides draw something other than the tile sprite
    // (IsoMannequin: 3D model, IsoBarricade: swapped light and alpha);
    // only IsoObject.render and its thin super.render wrappers are the
    // path replicated here.
    private static final HashMap<Class<?>, Boolean> renderViaIsoObject = new HashMap<>();

    private static boolean rendersViaIsoObject(Class<?> cls) {
        Boolean known = renderViaIsoObject.get(cls);
        if (known != null) return known;
        boolean result;
        try {
            Class<?> decl = cls.getMethod("render", float.class, float.class, float.class,
                    ColorInfo.class, boolean.class, boolean.class, Shader.class).getDeclaringClass();
            result = decl == IsoObject.class || decl == IsoThumpable.class || decl == IsoCurtain.class;
        } catch (Throwable t) {
            result = false;
        }
        renderViaIsoObject.put(cls, result);
        return result;
    }

    // setupTileDepth's selection chain, reduced to the branches grass
    // and its attachments can hit.
    private static Texture selectDepthTexture(IsoSprite spr, IsoObject object) {
        TileDepthTexture authored = spr.depthTexture;
        if (authored != null && !authored.isEmpty()) {
            return authored.getTexture();
        }
        if (spr.getProperties().has(IsoFlagType.solidfloor)
                || spr.getProperties().has(IsoFlagType.FloorOverlay)
                || spr.renderLayer == 1) {
            return TileDepthMapManager.instance.getTextureForPreset(TileDepthMapManager.TileDepthPreset.Floor);
        }
        IsoSprite main = object.getSprite();
        if (main != null && main != spr) {
            boolean useParent = (spr.depthFlags & 1) != 0
                    || spr.getProperties().has(IsoFlagType.WallOverlay)
                    && (spr.getProperties().has(IsoFlagType.attachedN) || spr.getProperties().has(IsoFlagType.attachedW));
            if (useParent && main.depthTexture != null && !main.depthTexture.isEmpty()) {
                return main.depthTexture.getTexture();
            }
        }
        TileDepthTexture def = TileDepthTextureManager.getInstance().getDefaultDepthTexture();
        if (def != null && !def.isEmpty()) {
            return def.getTexture();
        }
        return null;
    }

    // prepareToRenderSprite + performRenderFrame + Texture.render(ORE),
    // reduced to the static screen-space case shared by the main sprite
    // and its attachments.
    private static WindSwayGrassDrawer.GrassQuad buildPart(
            Texture tex, Texture depthTex, IsoSprite spr,
            float baseSx, float baseSy, float scaleX, float scaleY,
            float uvScaleX, float uvScaleY, boolean flip,
            float zNear, float zFar, ObjectRenderEffects ore,
            float r, float g, float b, float a) {
        float sx = baseSx + spr.soffX;
        float sy = baseSy + spr.soffY;
        float width = tex.getWidth();
        float height = tex.getHeight();
        if (scaleX != 1.0f) {
            sx += tex.getOffsetX() * (scaleX - 1.0f);
            width *= scaleX;
        }
        if (scaleY != 1.0f) {
            sy += tex.getOffsetY() * (scaleY - 1.0f);
            height *= scaleY;
        }

        WindSwayGrassDrawer.GrassQuad q = new WindSwayGrassDrawer.GrassQuad();
        q.tex = tex;
        q.depthTex = depthTex;
        q.ox = sx + tex.getOffsetX();
        q.oy = sy + tex.getOffsetY();
        q.w = width;
        q.h = height;

        q.u0 = tex.getXStart();
        q.u1 = tex.getXEnd();
        q.v0 = tex.getYStart();
        q.v1 = tex.getYEnd();
        if (flip) {
            float t = q.u0;
            q.u0 = q.u1;
            q.u1 = t;
        }

        // TileDepthModifier.accept: sprite rect ∩ depth-map rect in
        // tile pixel space, mapped into the depth page's UVs.
        float ix0 = PZMath.max(spr.soffX + tex.getOffsetX() * uvScaleX, depthTex.getOffsetX());
        float ix1 = PZMath.min(spr.soffX + (tex.getOffsetX() + (float) tex.getWidth()) * uvScaleX,
                depthTex.getOffsetX() + (float) depthTex.getWidth());
        float iy0 = PZMath.max(spr.soffY + tex.getOffsetY() * uvScaleY, depthTex.getOffsetY());
        float iy1 = PZMath.min(spr.soffY + (tex.getOffsetY() + (float) tex.getHeight()) * uvScaleY,
                depthTex.getOffsetY() + (float) depthTex.getHeight());
        q.du0 = depthTex.getXStart() + (ix0 - depthTex.getOffsetX()) / depthTex.getWidthHW();
        q.du1 = depthTex.getXStart() + (ix1 - depthTex.getOffsetX()) / depthTex.getWidthHW();
        q.dv0 = depthTex.getYStart() + (iy0 - depthTex.getOffsetY()) / depthTex.getHeightHW();
        q.dv1 = depthTex.getYStart() + (iy1 - depthTex.getOffsetY()) / depthTex.getHeightHW();

        q.zNear = zNear;
        q.zFar = zFar;

        q.r = r;
        q.g = g;
        q.b = b;
        if (debugTint) {
            q.r = 1.0f;
            q.g = 0.25f;
            q.b = 0.25f;
        }
        q.a = a;

        if (ore != null) {
            q.ox1 = (float) ore.x1;
            q.oy1 = (float) ore.y1;
            q.ox2 = (float) ore.x2;
            q.oy2 = (float) ore.y2;
            q.ox3 = (float) ore.x3;
            q.oy3 = (float) ore.y3;
            q.ox4 = (float) ore.x4;
            q.oy4 = (float) ore.y4;
        }
        return q;
    }

    private static int alphaStepFrame = -1;
    private static float alphaStep;

    // IsoObject.updateAlpha replica. The in/out asymmetry matters: a
    // symmetric step snaps obscure fades around the player instead of
    // melting them.
    private static void stepAlphaLikeVanilla(IsoObject object, IsoGridSquare square, int playerIndex, float target) {
        if (object.alphaForced) return;
        if (object.neverDoneAlpha) {
            object.setAlpha(0.0f);
            object.neverDoneAlpha = false;
        }
        float mul = 0.25f;
        if (square.getRoom() != null) {
            mul *= 2.0f;
        }
        int fc = IsoCamera.frameState.frameCount;
        if (fc != alphaStepFrame) {
            alphaStepFrame = fc;
            alphaStep = 0.28f * GameTime.getInstance().getMultiplier();
        }
        float step = alphaStep;
        float alpha = object.getAlpha(playerIndex);
        if (alpha < target) {
            alpha = Math.min(target, alpha + step * mul);
        } else if (alpha > target) {
            alpha = Math.max(target, alpha - step / 14.0f);
        }
        object.setAlpha(playerIndex, alpha);
    }

    public static void onTranslucentPassDone(int playerIndex, int z) {
        try {
            if (debugLog && !pendingQuads.isEmpty()) {
                flushPass5s++;
            }
            flushPendingTrees(FLUSH_TREES_PASS);
            flushPending();
            WindSwayGrassDrawer.onPassDone();
            IsoWorld world = IsoWorld.instance;
            if (world != lastWorld) {
                lastWorld = world;
                rearm();
                warmUp();
            }
            if (!enabled) return;

            if (debugLog) {
                int fc = IsoCamera.frameState.frameCount;
                if (fc != lastFrameCount) {
                    lastFrameCount = fc;
                    frames5s++;
                }
            }
            long now = System.currentTimeMillis();
            if (debugLog && now - lastWindLog > 5000L) {
                lastWindLog = now;
                trace(String.format("plantWind=%.3f raw=%.3f treeW=%.3f dir=%.2f poolX=%.3f rustleG=%.2f | flushes=%d quads=%d maxBatch=%d | alphaskip=%d",
                        ClimateManager.getWindTickFinal(), TreeSway.raw, TreeSway.w, TreeSway.dir, TreeSway.lastX, lastRustleGain, flushCount5s, flushQuads5s, maxBatch5s, diagAlphaSkips));
                trace(String.format("flush causes: door=%d obj=%d tree=%d passEnd=%d | gateSkips=%d",
                        flushDoor5s, flushObj5s, flushTree5s, flushPass5s, gateSkip5s));
                trace(String.format("trees: lists=%d trees=%d draws=%d binds=%d maxList=%d",
                        TreeRenderer.diagRenders, TreeRenderer.diagTrees, TreeRenderer.diagDraws, TreeRenderer.diagBinds,
                        TreeRenderer.diagMaxTrees));
                TreeRenderer.diagBinds = 0;
                trace(String.format("tree merge: held=%d merged=%d | flush obj=%d see=%d passEnd=%d | gateSkips=%d | trees/flush=%.1f max=%d | see lists: stencil=%d transp=%d fade=%d cut=%d",
                        held5s, merged5s, treeFlushObj5s, treeFlushSee5s, treeFlushPass5s, treeGateSkip5s,
                        mergedTrees5s / (double) Math.max(1, treeFlushObj5s + treeFlushSee5s + treeFlushPass5s), mergedMax5s,
                        seeStencil5s, seeTransp5s, seeFade5s, seeCut5s));
                trace(String.format("see skip: noRect=%d noBbox=%d noQuad=%d of %d lists",
                        seeSkipRect5s, seeSkipBbox5s, seeSkipQuad5s, seeLists5s));
                seeLists5s = 0;
                seeSkipRect5s = 0;
                seeSkipBbox5s = 0;
                seeSkipQuad5s = 0;
                seeStencil5s = 0;
                seeTransp5s = 0;
                seeFade5s = 0;
                seeCut5s = 0;
                held5s = 0;
                merged5s = 0;
                treeFlushObj5s = 0;
                treeFlushSee5s = 0;
                treeFlushPass5s = 0;
                treeGateSkip5s = 0;
                mergedTrees5s = 0;
                mergedMax5s = 0;
                int frames = Math.max(1, frames5s);
                trace(String.format("gpu: trees %s | grass %s | frames=%d",
                        TreeRenderer.gpuTimer.report(frames), WindSwayGrassDrawer.gpuTimer.report(frames), frames5s));
                long grassNs = WindSwayGrassDrawer.cpuNs.getAndSet(0L);
                long grassFillNs = WindSwayGrassDrawer.cpuFillNs.getAndSet(0L);
                long grassRuns = WindSwayGrassDrawer.diagRuns.getAndSet(0L);
                long grassBinds = WindSwayGrassDrawer.diagBinds.getAndSet(0L);
                trace(String.format("cpu (render thread): tree build %.3f draw %.3f ms/frame | grass batch %.3f ms/frame (fill %.3f, gl %.3f, runs/batch %.1f, binds/batch %.1f) | depth atlas cells %d/%d copies %d evictions %d",
                        TreeRenderer.cpuBuildNs.getAndSet(0L) / 1.0e6 / frames,
                        TreeRenderer.cpuDrawNs.getAndSet(0L) / 1.0e6 / frames,
                        grassNs / 1.0e6 / frames,
                        grassFillNs / 1.0e6 / frames,
                        (grassNs - grassFillNs) / 1.0e6 / frames,
                        grassRuns / (double) Math.max(1, flushCount5s),
                        grassBinds / (double) Math.max(1, flushCount5s),
                        DepthAtlas.diagCells, DepthAtlas.diagCapacity, DepthAtlas.diagCopies, DepthAtlas.diagEvictions));
                DepthAtlas.diagCopies = 0;
                DepthAtlas.diagEvictions = 0;
                double perBatch = 1.0e3 / Math.max(1, flushCount5s);
                trace(String.format("grass gl per batch (us): timer %.2f state %.2f upload %.2f attrib %.2f prog %.2f draw %.2f end %.2f",
                        WindSwayGrassDrawer.cpuTimerNs.getAndSet(0L) * perBatch / 1.0e6,
                        WindSwayGrassDrawer.cpuStateNs.getAndSet(0L) * perBatch / 1.0e6,
                        WindSwayGrassDrawer.cpuUploadNs.getAndSet(0L) * perBatch / 1.0e6,
                        WindSwayGrassDrawer.cpuAttribNs.getAndSet(0L) * perBatch / 1.0e6,
                        WindSwayGrassDrawer.cpuProgNs.getAndSet(0L) * perBatch / 1.0e6,
                        WindSwayGrassDrawer.cpuDrawNs.getAndSet(0L) * perBatch / 1.0e6,
                        WindSwayGrassDrawer.cpuEndNs.getAndSet(0L) * perBatch / 1.0e6));
                frames5s = 0;
                TreeRenderer.diagRenders = 0;
                TreeRenderer.diagTrees = 0;
                TreeRenderer.diagDraws = 0;
                TreeRenderer.diagMaxTrees = 0;
                flushDoor5s = 0;
                flushObj5s = 0;
                flushTree5s = 0;
                flushPass5s = 0;
                gateSkip5s = 0;
                while (flushSeenPrinted < flushSeen.size()) {
                    trace("flush trigger: " + flushSeen.get(flushSeenPrinted));
                    flushSeenPrinted++;
                }
                flushCount5s = 0;
                flushQuads5s = 0;
                maxBatch5s = 0;
                diagAlphaSkips = 0;
                if (!rejectCounts.isEmpty()) {
                    StringBuilder sb = new StringBuilder("rejects:");
                    for (Map.Entry<String, Integer> e : rejectCounts.entrySet()) {
                        sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
                    }
                    trace(sb.toString());
                    rejectCounts.clear();
                }
                while (rejectSeenPrinted < rejectSeen.size()) {
                    trace("reject sprite: " + rejectSeen.get(rejectSeenPrinted));
                    rejectSeenPrinted++;
                }
            }

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
            return GL11.glGetError();
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
