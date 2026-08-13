package pzmod.windsway;

import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Exposer;

import zombie.GameTime;
import zombie.IndieGL;
import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderSettings;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.characters.IsoGameCharacter;
import zombie.iso.PlayerCamera;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderObjectHighlight;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoCarBatteryCharger;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireplace;
import zombie.iso.objects.IsoMolotovCocktail;
import zombie.iso.objects.IsoTrap;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.IsoZombieGiblets;
import zombie.iso.objects.ObjectRenderEffects;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.weather.ClimateManager;
import zombie.tileDepth.TileDepthMapManager;
import zombie.tileDepth.TileDepthTexture;
import zombie.tileDepth.TileDepthTextureManager;

// Registered as the Kahlua global "WindSwayMod" (simple class name).
@Exposer.LuaClass
public class WindSwayMod {
    public static final WindSwayMod instance = new WindSwayMod();

    public static volatile boolean enabled = true;

    public static void setEnabled(boolean v) {
        enabled = v;
    }

    // Remap base for getWindTickFinal (Patch_ClimateManager): vanilla
    // wind sits near zero for hours on calm days, stilling all plant sway.
    public static volatile double windFloor = 0.1;

    public static void setWindFloor(double v) {
        windFloor = v;
    }

    // Separate remap base for tree pools (Patch_ObjectRenderEffects) so
    // crown and ground sway stay tunable apart.
    public static volatile double treeWindFloor = 0.1;

    public static void setTreeWindFloor(double v) {
        treeWindFloor = v;
    }

    private static boolean treeSplitOk = true;
    private static java.lang.reflect.Field isTreeField;
    private static java.lang.reflect.Field windTypeField;
    private static java.lang.reflect.Field rawWindTickField;
    public static volatile double lastTreeWind = -1.0;

    // Runs per pool update (~90x/frame) inside woven advice; must not
    // throw or the render loop dies.
    public static float treePoolWind(Object pool, float wind) {
        if (!enabled || !treeSplitOk) return wind;
        try {
            java.lang.reflect.Field treeF = isTreeField;
            if (treeF == null) {
                treeF = me.zed_0xff.zombie_buddy.Accessor.findField(
                        zombie.iso.objects.ObjectRenderEffects.class, "isTree");
                java.lang.reflect.Field typeF = me.zed_0xff.zombie_buddy.Accessor.findField(
                        zombie.iso.objects.ObjectRenderEffects.class, "windType");
                java.lang.reflect.Field rawF = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ClimateManager.class, "windTickFinal");
                if (treeF == null || typeF == null || rawF == null) {
                    throw new NoSuchFieldException("isTree/windType/windTickFinal");
                }
                treeF.setAccessible(true);
                typeF.setAccessible(true);
                rawF.setAccessible(true);
                rawWindTickField = rawF;
                windTypeField = typeF;
                isTreeField = treeF;
            }
            if (!treeF.getBoolean(pool)) return wind;
            // Same linear squeeze as the plant channel, but from the raw
            // (unpatched) field so the plant remap never stacks on top.
            double floor = treeWindFloor;
            double raw = rawWindTickField.getDouble(null);
            float tree = floor > 0.0 ? (float) (floor + (1.0 - floor) * raw) : (float) raw;
            lastTreeWind = tree;
            // Each pool type gates at its own threshold (0.08/0.15/0.3);
            // pre-distort the input so every type normalizes like type 1:
            // shared onset, unchanged skew ratio at saturation.
            int wt = windTypeField.getInt(pool);
            if (wt == 2 || wt == 3) {
                float n = tree <= 0.08f ? 0.0f : (tree - 0.08f) / 0.92f;
                if (n > 1.0f) n = 1.0f;
                tree = wt == 2 ? 0.15f + n * 0.85f : 0.3f + n * 0.7f;
            }
            return tree;
        } catch (Throwable t) {
            treeSplitOk = false;
            System.out.println("[WindSway] tree wind split disabled, trees follow plant wind: " + t);
            return wind;
        }
    }

    private static boolean rustleOk = true;
    private static java.lang.reflect.Field rrField;
    private static java.lang.reflect.Field rrTypeField;
    private static java.lang.reflect.Field rrTargetField;
    private static java.lang.reflect.Field treePoolsField;
    private static java.lang.reflect.Field dynFxField;
    private static java.lang.reflect.Field oreTypeField;
    private static java.lang.reflect.Field oreParentField;
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
                java.lang.reflect.Field rawF = rawWindTickField;
                if (rawF == null) {
                    rawF = me.zed_0xff.zombie_buddy.Accessor.findField(
                            ClimateManager.class, "windTickFinal");
                    if (rawF == null) throw new NoSuchFieldException("windTickFinal");
                    rawF.setAccessible(true);
                    rawWindTickField = rawF;
                }
                java.lang.reflect.Field rr = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "randomRustle");
                java.lang.reflect.Field rrType = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "randomRustleType");
                java.lang.reflect.Field rrTarget = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "randomRustleTarget");
                java.lang.reflect.Field pools = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "WIND_EFFECTS_TREES");
                java.lang.reflect.Field dyn = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "DYNAMIC_EFFECTS");
                java.lang.reflect.Field type = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "type");
                java.lang.reflect.Field parent = me.zed_0xff.zombie_buddy.Accessor.findField(
                        ObjectRenderEffects.class, "parent");
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
            double raw = rawWindTickField.getDouble(null);
            double tf = treeWindFloor;
            double pf = windFloor;
            double treeCh = tf > 0.0 ? tf + (1.0 - tf) * raw : raw;
            double plantCh = pf > 0.0 ? pf + (1.0 - pf) * raw : raw;
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
                if (oreTypeField.get(e) != zombie.iso.objects.RenderEffectType.Vegetation_Rustle) continue;
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

    // Runs once per visible tree per frame from woven advice; must not
    // throw.
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
                return ore;
            }
            ObjectRenderEffects scratch = treeOreScratch;
            if (scratch == null) {
                scratch = ObjectRenderEffects.alloc();
                treeOreScratch = scratch;
            }
            scratch.x1 = ore.x1 * f;
            scratch.y1 = ore.y1 * f;
            scratch.x2 = ore.x2 * f;
            scratch.y2 = ore.y2 * f;
            scratch.x3 = ore.x3 * f;
            scratch.y3 = ore.y3 * f;
            scratch.x4 = ore.x4 * f;
            scratch.y4 = ore.y4 * f;
            if (!firstTreeScaleLogged) {
                firstTreeScaleLogged = true;
                trace("first jumbo ORE scaled x" + (int) f + " (texW=" + w + ")");
            }
            return scratch;
        } catch (Throwable t) {
            treeOreScaleOk = false;
            trace("tree ORE scaling disabled: " + t);
            return ore;
        }
    }

    // Debug: tint every batch quad red to tell batch output from vanilla
    // draws.
    public static volatile boolean debugTint = false;

    public static void setDebugTint(boolean v) {
        debugTint = v;
    }

    // Debug: 5s counters plus reject/flush-trigger names in the console.
    public static volatile boolean debugLog = false;

    public static void setDebugLog(boolean v) {
        debugLog = v;
    }

    private static volatile boolean enqueueFailedLogged = false;
    private static volatile boolean captureFailedLogged = false;
    private static volatile boolean firstCaptureLogged = false;

    // Game-thread only. Drained mid-pass (onVanillaTranslucentDraw) and
    // at pass end (onTranslucentPassDone).
    private static ArrayList<WindSwayGrassDrawer.GrassQuad> pendingQuads = new ArrayList<>();
    private static long lastWindLog = 0L;
    private static int diagAlphaSkips = 0;

    // Diagnostic: counts and names objects handed back to vanilla.
    private static final java.util.HashMap<String, Integer> rejectCounts = new java.util.HashMap<>();
    private static final java.util.ArrayList<String> rejectSeen = new java.util.ArrayList<>();
    private static final java.util.HashSet<String> rejectSeenSet = new java.util.HashSet<>();
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

    // Batch order = capture order = vanilla paint order. Against no-depth-
    // write translucents (fences, doors, handed-back objects) ordering is
    // kept by flushing before any such draw that can overlap the pending
    // bounds. Depth cannot replace paint order here: neighboring squares'
    // [zNear,zFar] ranges overlap, blade depth interleaves across squares
    // in both directions.
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
    private static int flushPass5s = 0;
    private static int gateSkip5s = 0;
    private static final java.util.ArrayList<String> flushSeen = new java.util.ArrayList<>();
    private static final java.util.HashSet<String> flushSeenSet = new java.util.HashSet<>();
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
        float x0 = q.ox + Math.min(q.ox1, q.ox4) * q.w;
        float x1 = q.ox + q.w + Math.max(q.ox2, q.ox3) * q.w;
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

    // Flush the pending batch before a no-depth-write vanilla translucent
    // that can touch it. Trees and character models write depth and need
    // no flush.
    public static void onVanillaTranslucentDraw(IsoObject object, boolean doorOrWall) {
        try {
            if (pendingQuads.isEmpty()) return;
            if (!FBORenderCell.instance.renderTranslucentOnly) return;
            if (object instanceof IsoTree) return;
            IsoGridSquare square = object != null ? object.getSquare() : null;
            if (square == null) {
                noteFlushTrigger(object, doorOrWall);
                flushPending();
                return;
            }
            float ax = IsoUtils.XToScreen(square.x, square.y, square.z, 0) - IsoCamera.frameState.offX;
            float ay = IsoUtils.YToScreen(square.x, square.y, square.z, 0) - IsoCamera.frameState.offY;
            if (ax + OVERLAP_PAD < pendMinX || ax - OVERLAP_PAD > pendMaxX
                    || ay + OVERLAP_PAD < pendMinY || ay - OVERLAP_PAD > pendMaxY) {
                if (debugLog) gateSkip5s++;
                return;
            }
            noteFlushTrigger(object, doorOrWall);
            flushPending();
        } catch (Throwable t) {
            // Ordering beats batching: if the bounds test dies, draw what
            // we have.
            flushPending();
        }
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
            // setupTileDepth's special-object list: chunk depth or own
            // shader in vanilla. Everything else on this path is a
            // tile-depth quad in vanilla too — capturing non-wind objects
            // as well keeps a field one pipeline instead of a flush storm.
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
            // main sprite gets customColor and forceAmbient.
            float liR = 1.0f;
            float liG = 1.0f;
            float liB = 1.0f;
            float liA = 1.0f;
            ColorInfo li = square.getLightInfo(playerIndex);
            if (li != null) {
                liR = li.r;
                liG = li.g;
                liB = li.b;
                liA = li.a;
            }
            float lr = liR;
            float lg = liG;
            float lb = liB;
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
            // Trample beats wind, as in performRenderFrame.
            ObjectRenderEffects ore = object.getObjectRenderEffectsToApply();

            ArrayList<WindSwayGrassDrawer.GrassQuad> parts = new ArrayList<>(2 + attachedCount);
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
                float ocr = liR;
                float ocg = liG;
                float ocb = liB;
                float oFactor = liA;
                ColorInfo osc = object.getOverlaySpriteColor();
                if (osc != null) {
                    ocr = osc.r * liR;
                    ocg = osc.g * liG;
                    ocb = osc.b * liB;
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
                        liR * s.tintr, liG * s.tintg, liB * s.tintb, a2));
            }

            // Canary: if the pass advice never drains us (weave failure),
            // cap instead of leaking.
            if (pendingQuads.size() > 100000) {
                pendingQuads.clear();
                pendBoundsValid = false;
                if (!captureFailedLogged) {
                    captureFailedLogged = true;
                    trace("pendingQuads overflow — pass advice not running? batch disabled this session");
                }
                return false;
            }
            pendingQuads.addAll(parts);
            for (int i = 0; i < parts.size(); ++i) {
                extendPendingBounds(parts.get(i));
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
                trace("grass capture failed — falling back to vanilla draw", t);
            }
            return false;
        }
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
        float step = 0.28f * GameTime.getInstance().getMultiplier();
        float alpha = object.getAlpha(playerIndex);
        if (alpha < target) {
            alpha = Math.min(target, alpha + step * mul);
        } else if (alpha > target) {
            alpha = Math.max(target, alpha - step / 14.0f);
        }
        object.setAlpha(playerIndex, alpha);
    }

    public static void onTranslucentPassDone(int playerIndex, int z) {
        if (!enabled) return;
        try {
            if (debugLog && !pendingQuads.isEmpty()) {
                flushPass5s++;
            }
            flushPending();

            long now = System.currentTimeMillis();
            if (debugLog && now - lastWindLog > 5000L) {
                lastWindLog = now;
                trace(String.format("windTickFinal=%.3f treeWind=%.3f rustleG=%.2f | flushes=%d quads=%d maxBatch=%d | alphaskip=%d",
                        ClimateManager.getWindTickFinal(), lastTreeWind, lastRustleGain, flushCount5s, flushQuads5s, maxBatch5s, diagAlphaSkips));
                trace(String.format("flush causes: door=%d obj=%d passEnd=%d | gateSkips=%d",
                        flushDoor5s, flushObj5s, flushPass5s, gateSkip5s));
                flushDoor5s = 0;
                flushObj5s = 0;
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
                    for (java.util.Map.Entry<String, Integer> e : rejectCounts.entrySet()) {
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

    public void init() {
        trace("WindSway initialized");
    }

    public static void trace(String msg) {
        System.out.println("[WindSway] " + msg);
    }

    public static void trace(String msg, Throwable t) {
        System.out.println("[WindSway] " + msg);
        t.printStackTrace(System.out);
    }
}
