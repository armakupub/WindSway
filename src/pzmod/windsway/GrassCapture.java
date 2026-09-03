package pzmod.windsway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Consumer;

import zombie.GameTime;
import zombie.core.Color;
import zombie.core.Core;
import zombie.core.math.PZMath;
import zombie.core.opengl.RenderSettings;
import zombie.core.opengl.Shader;
import zombie.core.properties.RoofProperties;
import zombie.core.textures.ColorInfo;
import zombie.core.textures.Texture;
import zombie.characters.IsoGameCharacter;
import zombie.characters.IsoPlayer;
import zombie.iso.IsoCamera;
import zombie.iso.IsoDepthHelper;
import zombie.iso.IsoDirections;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.IsoWorld;
import zombie.iso.PlayerCamera;
import zombie.iso.SpriteDetails.IsoFlagType;
import zombie.iso.SpriteDetails.IsoObjectType;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderCutaways;
import zombie.iso.fboRenderChunk.FBORenderObjectHighlight;
import zombie.iso.fboRenderChunk.FBORenderObjectOutline;
import zombie.iso.fboRenderChunk.FBORenderSnow;
import zombie.iso.fboRenderChunk.ObjectRenderInfo;
import zombie.iso.objects.IsoBarbecue;
import zombie.iso.objects.IsoCarBatteryCharger;
import zombie.iso.objects.IsoCurtain;
import zombie.iso.objects.IsoDoor;
import zombie.iso.objects.IsoFire;
import zombie.iso.objects.IsoFireplace;
import zombie.iso.objects.IsoMolotovCocktail;
import zombie.iso.objects.IsoThumpable;
import zombie.iso.objects.IsoTrap;
import zombie.iso.objects.IsoTree;
import zombie.iso.objects.IsoWindow;
import zombie.iso.objects.IsoWindowFrame;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.objects.IsoZombieGiblets;
import zombie.iso.objects.ObjectRenderEffects;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.iso.weather.fx.WeatherFxMask;
import zombie.tileDepth.TileDepthMapManager;
import zombie.tileDepth.TileDepthTexture;
import zombie.tileDepth.TileDepthTextureManager;

// Game-thread capture of vanilla's per-object translucent draws into
// GrassQuads: reject gates hand anything the batch cannot replicate back
// to vanilla, wind flora gets its sway parameters per part.
public final class GrassCapture {

    private GrassCapture() {
    }

    private static volatile boolean captureFailedLogged = false;
    private static volatile boolean firstCaptureLogged = false;

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
        long t0 = WindSwayMod.debugLog ? System.nanoTime() : 0L;
        boolean captured = captureGrassInner(object);
        if (!captured) {
            BatchSequencer.onVanillaTranslucentDraw(object, false);
        }
        if (t0 != 0L) {
            DebugStats.gtGrassNs += System.nanoTime() - t0;
            DebugStats.gtGrassN++;
        }
        return captured;
    }

    public static boolean tryCaptureWall(IsoObject object) {
        long t0 = WindSwayMod.debugLog ? System.nanoTime() : 0L;
        boolean captured = captureWallInner(object);
        if (!captured) {
            BatchSequencer.onVanillaTranslucentDraw(object, true);
        }
        if (t0 != 0L) {
            DebugStats.gtWallNs += System.nanoTime() - t0;
            DebugStats.gtWallN++;
        }
        return captured;
    }

    // renderFloor path: same gate as any vanilla translucent, timed on its
    // own account.
    public static void onVanillaFloorDraw(IsoObject object) {
        long t0 = WindSwayMod.debugLog ? System.nanoTime() : 0L;
        BatchSequencer.onVanillaTranslucentDraw(object, false);
        if (t0 != 0L) {
            DebugStats.gtFloorNs += System.nanoTime() - t0;
            DebugStats.gtFloorN++;
        }
    }

    // renderMinusFloor_DoorOrWall, plain case only: one wall direction,
    // no cutaway on the square, no door or window, full alpha. Corners
    // (two halved quads), SE walls, door frames and anything cut away
    // keep the vanilla draw.
    static volatile boolean wallCapture = true;

    private static boolean captureWallInner(IsoObject object) {
        if (!WindSwayMod.enabled || !wallCapture) return false;
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return false;
            // The highlight pass raises the same flag before the z loop.
            if (FBORenderObjectHighlight.getInstance().isRendering()) return false;
            if (!Core.getInstance().getOptionDoWindSpriteEffects()) return false;
            if (!WindSwayGrassDrawer.ready()) return false;
            if (IsoSprite.seamFix2 != null) return false;
            IsoSprite sprite = object.getSprite();
            if (sprite == null) return false;
            if (object instanceof IsoDoor || object instanceof IsoWindow || object instanceof IsoWindowFrame) {
                return DebugStats.reject("wall:door", sprite);
            }
            if (object instanceof IsoThumpable && ((IsoThumpable) object).isDoor()) return DebugStats.reject("wall:door", sprite);
            if (!rendersWallViaIsoObject(object.getClass())) return DebugStats.reject("wall:ownRender", sprite);
            boolean cutN = sprite.cutN;
            boolean cutW = sprite.cutW;
            if (cutN == cutW) return DebugStats.reject("wall:corner", sprite);
            if (object.isWallSE()) return DebugStats.reject("wall:se", sprite);
            IsoObjectType t = sprite.getTileType();
            if (t == IsoObjectType.doorFrN || t == IsoObjectType.doorN
                    || t == IsoObjectType.doorFrW || t == IsoObjectType.doorW) {
                return DebugStats.reject("wall:door", sprite);
            }
            if (sprite.getProperties().has(IsoFlagType.DoorWallN) || sprite.getProperties().has(IsoFlagType.DoorWallW)
                    || sprite.getProperties().has(IsoFlagType.doorN) || sprite.getProperties().has(IsoFlagType.doorW)) {
                return DebugStats.reject("wall:doorWall", sprite);
            }
            if (sprite.getProperties().has(IsoFlagType.NoWallLighting)) return DebugStats.reject("wall:noLight", sprite);
            if ((sprite.depthFlags & 4) != 0) return DebugStats.reject("wall:opaque", sprite);
            IsoGridSquare square = object.getSquare();
            if (square == null) return DebugStats.reject("noSquare", sprite);
            int playerIndex = IsoCamera.frameState.playerIndex;
            if (square.getPlayerCutawayFlag(playerIndex, 0L) != 0) return DebugStats.reject("wall:cutaway", sprite);
            ObjectRenderInfo renderInfo = object.getRenderInfo(playerIndex);
            if (renderInfo.cutawayOutline) return DebugStats.reject("wall:cutaway", sprite);
            if (renderInfo.targetAlpha != 1.0f) return DebugStats.reject("wall:alpha", sprite);
            if (object.getSpriteModel() != null) return DebugStats.reject("model", sprite);
            if (FBORenderObjectHighlight.getInstance().shouldRenderObjectHighlight(object)) {
                return DebugStats.reject("highlight", sprite);
            }
            if (object.getOverlaySprite() != null) return DebugStats.reject("wall:overlay", sprite);
            ArrayList<IsoSpriteInstance> attachments = object.getAttachedAnimSprite();
            if (attachments != null && !attachments.isEmpty()) return DebugStats.reject("wall:attached", sprite);
            if (object.wallBloodSplats != null && !object.wallBloodSplats.isEmpty()) return DebugStats.reject("wall:blood", sprite);
            if (object.hasAnimatedAttachments()) return DebugStats.reject("animAttach", sprite);
            // renderWallTile draws nothing for these; vanilla still ends
            // the object at alpha 1.
            if (object.isSpriteInvisible()) {
                object.setAlphaAndTarget(playerIndex, 1.0f);
                return true;
            }
            IsoSpriteInstance inst = sprite.def;
            if (inst == null) return DebugStats.reject("noDef", sprite);
            Texture tex = sprite.getTextureForCurrentFrame(cutN ? IsoDirections.N : IsoDirections.W, object);
            if (tex == null || tex.getTextureId() == null) return DebugStats.reject("noTex", sprite);
            Texture depthTex = selectWallDepthTexture(sprite, cutN);
            if (depthTex == null || depthTex.getTextureId() == null) return DebugStats.reject("noDepthTex", sprite);

            int wOrig = tex.getWidthOrig();
            int hOrig = tex.getHeightOrig();
            fixupScale(inst.scaleX, inst.scaleY, wOrig, hOrig, scaleScratch);
            float scaleX = scaleScratch[0];
            float scaleY = scaleScratch[1];
            if (scaleX <= 0.0f || scaleY <= 0.0f) return DebugStats.reject("badScale", sprite);

            PlayerCamera camera = IsoCamera.cameras[playerIndex];
            float offsetXParam = object.offsetX;
            float offsetYParam = object.offsetY + object.getRenderYOffset() * (float) Core.tileScale;
            float baseSx = IsoUtils.XToScreen(square.x + inst.offX, square.y + inst.offY, square.z + inst.offZ, 0);
            float baseSy = IsoUtils.YToScreen(square.x + inst.offX, square.y + inst.offY, square.z + inst.offZ, 0);
            baseSx -= offsetXParam;
            baseSy -= offsetYParam;
            baseSx += -IsoCamera.frameState.offX;
            baseSy += -IsoCamera.frameState.offY;
            float pickerX = baseSx;
            float pickerY = baseSy;
            float zoom = IsoCamera.frameState.zoom;
            baseSx += camera.fixJigglyModelsX * zoom;
            baseSy += camera.fixJigglyModelsY * zoom;

            boolean world = BatchSequencer.latchFrameCamera(camera, playerIndex);
            float zFar = 0.0f;
            float zNear = 0.0f;
            float anchorX = 0.0f;
            float anchorY = 0.0f;
            if (world) {
                float xts = square.x * BatchSequencer.camK1 - square.y * BatchSequencer.camK1;
                float k2 = BatchSequencer.camK1 * 0.5f;
                float yts = square.y * k2 + square.x * k2 + (0.0f - square.z) * (BatchSequencer.camK1 * 3.0f);
                anchorX = xts - BatchSequencer.camOffJX;
                anchorY = yts - BatchSequencer.camOffJY;
            } else {
                // setupTileDepthWall passes z2 == z: no renderYOffset ramp.
                float jx = square.x + camera.fixJigglyModelsSquareX;
                float jy = square.y + camera.fixJigglyModelsSquareY;
                int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                zFar = IsoDepthHelper.getSquareDepthData(camX, camY, jx, jy, square.z).depthStart;
                zNear = IsoDepthHelper.getSquareDepthData(camX, camY, jx + 1.0f, jy + 1.0f, square.z + 1.0f).depthStart;
            }

            // DoWallLightingN/W: the flat colour is white and WallShaper
            // replaces rgb per corner, so customColor and forceAmbient
            // never reach the main sprite. Upper corners take the
            // contrast tweak against the lower ones.
            int vertU = square.getVertLight(0, playerIndex);
            int vertU2 = square.getVertLight(4, playerIndex);
            int vertL = square.getVertLight(cutN ? 1 : 3, playerIndex);
            int vertL2 = square.getVertLight(cutN ? 5 : 7, playerIndex);
            float[] c = wallCornerScratch;
            unpackABGR(vertU2, c, 0);
            unpackABGR(vertL, c, 3);
            unpackTweaked(vertU, vertU2, c, 6);
            unpackTweaked(vertL2, vertL, c, 9);
            if (FBORenderCell.instance.isBlackedOutBuildingSquare(square)) {
                float keep = 1.0f - FBORenderCell.instance.getBlackedOutRoomFadeRatio(square);
                for (int i = 0; i < 12; ++i) c[i] *= keep;
            }
            // c: 0 colu2 (raw upper vert 4), 3 coll (raw lower vert 1/3),
            // 6 colu (tweaked vert 0), 9 coll2 (tweaked vert 5/7).
            // N: col0 colu2, col1 coll2, col2 coll, col3 colu.
            // W: col0 coll2, col1 colu2, col2 colu, col3 coll.
            int c0 = cutN ? 0 : 9;
            int c1 = cutN ? 9 : 0;
            int c2 = cutN ? 3 : 6;
            int c3 = cutN ? 6 : 3;

            WindSwayGrassDrawer.GrassQuad q = buildPart(tex, depthTex, sprite, baseSx, baseSy,
                    scaleX, scaleY, 1.0f, 1.0f, inst.flip,
                    zNear, zFar, null, c[c0], c[c0 + 1], c[c0 + 2], 1.0f);
            q.lit = true;
            q.r1 = c[c1];
            q.g1 = c[c1 + 1];
            q.b1 = c[c1 + 2];
            q.r2 = c[c2];
            q.g2 = c[c2 + 1];
            q.b2 = c[c2 + 2];
            q.r3 = c[c3];
            q.g3 = c[c3 + 1];
            q.b3 = c[c3 + 2];
            if (WindSwayMod.debugTint) {
                q.r1 = q.r2 = q.r3 = q.r;
                q.g1 = q.g2 = q.g3 = q.g;
                q.b1 = q.b2 = q.b3 = q.b;
            }
            if (world) {
                q.sqX = square.x;
                q.sqY = square.y;
                q.sqZ = square.z;
                q.anchorX = anchorX;
                q.anchorY = anchorY;
            }
            if (BatchSequencer.overflowed()) return false;
            q.wall = true;
            BatchSequencer.addWall(q);
            if (WindSwayMod.debugLog) DebugStats.wallsCaptured5s++;

            // renderMinusFloor_DoorOrWall: setAlphaAndTarget(target),
            // performDrawWallOnly ends with setAlpha(1); target is 1 here.
            object.setAlphaAndTarget(playerIndex, 1.0f);
            writePicker(object, playerIndex, pickerX, pickerY, wOrig, hOrig, scaleX, scaleY, 1.0f);
            wallNoPicking(object, sprite, square, playerIndex, cutN, cutW);
            return true;
        } catch (Throwable t) {
            if (!wallCaptureFailedLogged) {
                wallCaptureFailedLogged = true;
                WindSwayMod.trace("wall capture failed, falling back to vanilla draw", t);
            }
            return false;
        }
    }

    private static volatile boolean wallCaptureFailedLogged = false;

    // Object-picker click box, normally refilled by the draw we skip;
    // vanilla leaves it alone only at alpha 0.
    private static void writePicker(IsoObject object, int playerIndex, float x, float y,
            float wOrig, float hOrig, float scaleX, float scaleY, float alpha) {
        if (alpha == 0.0f) return;
        if (WeatherFxMask.isRenderingMask()
                || FBORenderObjectHighlight.getInstance().isRendering()
                || FBORenderObjectOutline.getInstance().isRendering()) return;
        ObjectRenderInfo ri = object.getRenderInfo(playerIndex);
        ri.renderX = x;
        ri.renderY = y;
        ri.renderWidth = wOrig * scaleX;
        ri.renderHeight = hOrig * scaleY;
        ri.renderScaleX = scaleX;
        ri.renderScaleY = scaleY;
        ri.renderAlpha = alpha;
    }

    // One-entry memo: captures arrive square by square and the ground-snow
    // lookup walks square flags and the chunk snow grid.
    private static IsoGridSquare snowMemoSquare;
    private static boolean snowMemoSnowy;

    // Per world: the memo pins a square of the old one.
    static void rearm() {
        snowMemoSquare = null;
    }
    private static final float[] wallCornerScratch = new float[12];

    private static void unpackABGR(int abgr, float[] out, int at) {
        out[at] = Color.getRedChannelFromABGR(abgr);
        out[at + 1] = Color.getGreenChannelFromABGR(abgr);
        out[at + 2] = Color.getBlueChannelFromABGR(abgr);
    }

    // DoWallLightingN/W contrast step: each channel of col nudged 4.5 %
    // away from ref.
    private static void unpackTweaked(int col, int ref, float[] out, int at) {
        float r = Color.getRedChannelFromABGR(col);
        float g = Color.getGreenChannelFromABGR(col);
        float b = Color.getBlueChannelFromABGR(col);
        float rr = Color.getRedChannelFromABGR(ref);
        float rg = Color.getGreenChannelFromABGR(ref);
        float rb = Color.getBlueChannelFromABGR(ref);
        out[at] = PZMath.clamp(r * (r >= rr ? 1.045f : 0.955f), 0.0f, 1.0f);
        out[at + 1] = PZMath.clamp(g * (g >= rg ? 1.045f : 0.955f), 0.0f, 1.0f);
        out[at + 2] = PZMath.clamp(b * (b >= rb ? 1.045f : 0.955f), 0.0f, 1.0f);
    }

    // setupWallDepth for the main sprite: authored map, else the preset
    // for the direction (door-frame and SE presets are rejected before).
    private static Texture selectWallDepthTexture(IsoSprite sprite, boolean north) {
        TileDepthTexture authored = sprite.depthTexture;
        if (authored != null && !authored.isEmpty()) {
            return authored.getTexture();
        }
        return TileDepthMapManager.instance.getTextureForPreset(
                north ? TileDepthMapManager.TileDepthPreset.NWall : TileDepthMapManager.TileDepthPreset.WWall);
    }

    // IsoObject.prepareToRender's picker gate for the skipped draw: a wall
    // between the camera character and the viewer is not clickable.
    private static void wallNoPicking(IsoObject object, IsoSprite sprite, IsoGridSquare square,
            int playerIndex, boolean cutN, boolean cutW) {
        if (IsoCamera.getCameraCharacter() == null) return;
        float camCharacterX = IsoCamera.frameState.camCharacterX;
        float camCharacterY = IsoCamera.frameState.camCharacterY;
        float camCharacterZ = IsoCamera.frameState.camCharacterZ;
        if (IsoWorld.instance.currentCell.IsPlayerWindowPeeking(playerIndex)) {
            IsoPlayer player = IsoPlayer.players[playerIndex];
            IsoDirections playerDir = IsoDirections.fromAngle(player.getForwardDirection());
            if (playerDir == IsoDirections.N || playerDir == IsoDirections.NW) camCharacterY -= 1.0f;
            if (playerDir == IsoDirections.W || playerDir == IsoDirections.NW) camCharacterX -= 1.0f;
        }
        boolean noPicking = false;
        if ((square.getX() > camCharacterX || square.getY() > camCharacterY)
                && PZMath.fastfloor(camCharacterZ) <= square.getZ()) {
            boolean cutWest = cutW && square.getX() > camCharacterX;
            boolean cutNorth = cutN && square.getY() > camCharacterY;
            if (cutWest && square.getProperties().has(IsoFlagType.WallSE) && square.getY() <= camCharacterY) {
                cutWest = false;
            }
            boolean cut = cutWest || cutNorth;
            if (sprite.getProperties().has(IsoFlagType.halfheight)) cut = false;
            if (cut) {
                noPicking = object.rerouteMask == null
                        && !(object instanceof IsoThumpable)
                        && !sprite.getProperties().has(IsoFlagType.HoppableN)
                        && !sprite.getProperties().has(IsoFlagType.HoppableW);
            }
        }
        object.noPicking = noPicking;
    }

    private static final HashMap<Class<?>, Boolean> renderWallViaIsoObject = new HashMap<>();

    // IsoThumpable's override only diverts double doors, rejected before.
    private static boolean rendersWallViaIsoObject(Class<?> cls) {
        Boolean known = renderWallViaIsoObject.get(cls);
        if (known != null) return known;
        boolean result;
        try {
            Class<?> decl = cls.getMethod("renderWallTile", IsoDirections.class, float.class, float.class,
                    float.class, ColorInfo.class, boolean.class, boolean.class, Shader.class,
                    Consumer.class).getDeclaringClass();
            result = decl == IsoObject.class || decl == IsoThumpable.class;
        } catch (Throwable t) {
            result = false;
        }
        renderWallViaIsoObject.put(cls, result);
        return result;
    }

    private static boolean captureGrassInner(IsoObject object) {
        if (!WindSwayMod.enabled) return false;
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return false;
            // FBORenderObjectHighlight.render raises the same flag before
            // the z loop and draws the highlighted square's neighbours
            // through renderTranslucent: a capture there would land in the
            // wrong pass and draw twice.
            if (FBORenderObjectHighlight.getInstance().isRendering()) return false;
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
            if (!rendersViaIsoObject(object.getClass())) return DebugStats.reject("ownRender", sprite);
            IsoGridSquare square = object.getSquare();
            if (square == null) return DebugStats.reject("noSquare", sprite);
            // IsoObject.render draws nothing for these; capture as nothing
            // but keep the targetAlpha handoff.
            if (!object.getDoRender() || object.isSpriteInvisible()) {
                int pi = IsoCamera.frameState.playerIndex;
                object.setTargetAlpha(pi, object.getRenderInfo(pi).targetAlpha);
                return true;
            }
            // Rendered as 3D models in vanilla, not sprites.
            if (object.getSpriteModel() != null) return DebugStats.reject("model", sprite);
            // Highlight blending (hover/selection) stays vanilla.
            if (FBORenderObjectHighlight.getInstance().shouldRenderObjectHighlight(object)) {
                return DebugStats.reject("highlight", sprite);
            }
            // Windows and wall overlays get directional wall depth
            // (setupWallDepth), not replicated.
            if (sprite.getProperties().has(IsoFlagType.windowN)
                    || sprite.getProperties().has(IsoFlagType.windowW)
                    || sprite.getProperties().has(IsoFlagType.WallOverlay)) {
                return DebugStats.reject("wallDepth", sprite);
            }
            // renderMinusFloor_NotDoorOrWall draws the joined neighbour of
            // a roof tile before the tile itself (same level at a chunk
            // edge, the level below anywhere); the skip would drop that
            // seam.
            RoofProperties roof = sprite.getRoofProperties();
            if (roof != null && (PZMath.coordmodulo(square.x, 8) == 7 && roof.hasPossibleSeamSameLevel(IsoDirections.E)
                    || PZMath.coordmodulo(square.y, 8) == 7 && roof.hasPossibleSeamSameLevel(IsoDirections.S)
                    || roof.hasPossibleSeamLevelBelow(IsoDirections.E)
                    || roof.hasPossibleSeamLevelBelow(IsoDirections.S))) {
                return DebugStats.reject("roof", sprite);
            }
            // renderAttachedSprites draws blood splats and children after
            // the parts; children is protected, no gate for it.
            if (object.wallBloodSplats != null && !object.wallBloodSplats.isEmpty()) return DebugStats.reject("blood", sprite);
            // OpaquePixelsOnly: vanilla swaps to opaqueDepthShader with an
            // alpha > 0.8 cut the batch shader has not.
            if ((sprite.depthFlags & 4) != 0) return DebugStats.reject("opaque", sprite);
            // Animated attachments draw via a separate engine call after
            // renderMinusFloor that our skip does not cover; capturing the
            // body would flush it behind its own attachments.
            if (object.hasAnimatedAttachments()) return DebugStats.reject("animAttach", sprite);
            IsoSpriteInstance inst = sprite.def;
            if (inst == null) return DebugStats.reject("noDef", sprite);
            Texture tex = sprite.getTextureForCurrentFrame(object.getDir(), object);
            if (tex == null || tex.getTextureId() == null) return DebugStats.reject("noTex", sprite);
            Texture mainDepthTex = selectDepthTexture(sprite, object);
            if (mainDepthTex == null || mainDepthTex.getTextureId() == null) return DebugStats.reject("noDepthTex", sprite);

            int wOrig = tex.getWidthOrig();
            int hOrig = tex.getHeightOrig();
            fixupScale(inst.scaleX, inst.scaleY, wOrig, hOrig, scaleScratch);
            float scaleX = scaleScratch[0];
            float scaleY = scaleScratch[1];
            if (scaleX <= 0.0f || scaleY <= 0.0f) return DebugStats.reject("badScale", sprite);

            int playerIndex = IsoCamera.frameState.playerIndex;
            PlayerCamera camera = IsoCamera.cameras[playerIndex];

            // Vanilla draws with pre-step alpha and steps afterwards; step
            // only on paths that return true, on fallback vanilla steps
            // itself. WestRoofT snaps to its target instead.
            float target = object.getRenderInfo(playerIndex).targetAlpha;
            object.setTargetAlpha(playerIndex, target);
            if (object.getType() == IsoObjectType.WestRoofT) object.setAlphaAndTarget(playerIndex, target);
            float alpha = object.getAlpha(playerIndex);

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
            if (alpha <= 0.01f) {
                if (WindSwayMod.debugLog) DebugStats.diagAlphaSkips++;
                writePicker(object, playerIndex, pickerX, pickerY, wOrig, hOrig, scaleX, scaleY, alpha);
                stepAlphaLikeVanilla(object, square, playerIndex, target);
                return true;
            }

            // All-or-nothing: any undrawable part sends the whole object
            // back to vanilla, half objects read as holes. IsoObject.render
            // leaves out the attachments of a chair someone sits on.
            ArrayList<IsoSpriteInstance> attachments = object.getAttachedAnimSprite();
            int attachedCount = attachments != null && !object.isSatChair() ? attachments.size() : 0;

            float zoom = IsoCamera.frameState.zoom;
            baseSx += camera.fixJigglyModelsX * zoom;
            baseSy += camera.fixJigglyModelsY * zoom;

            boolean world = BatchSequencer.latchFrameCamera(camera, playerIndex);
            float zFar = 0.0f;
            float zNear = 0.0f;
            float anchorX = 0.0f;
            float anchorY = 0.0f;
            float dzShift = 0.0f;
            float yOff = object.getRenderYOffset();
            if (world) {
                float xts = square.x * BatchSequencer.camK1 - square.y * BatchSequencer.camK1;
                float k2 = BatchSequencer.camK1 * 0.5f;
                float yts = square.y * k2 + square.x * k2 + (0.0f - square.z) * (BatchSequencer.camK1 * 3.0f);
                anchorX = xts - BatchSequencer.camOffJX;
                anchorY = yts - BatchSequencer.camOffJY;
                if (yOff != 0.0f) {
                    dzShift = yOff / 96.0f * 0.0028867084f;
                }
            } else {
                // startTileDepthShader, translucent branch: near = SE corner
                // one level up.
                float jx = square.x + camera.fixJigglyModelsSquareX;
                float jy = square.y + camera.fixJigglyModelsSquareY;
                int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
                int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
                zFar = IsoDepthHelper.getSquareDepthData(camX, camY, jx, jy, square.z).depthStart;
                zNear = IsoDepthHelper.getSquareDepthData(camX, camY, jx + 1.0f, jy + 1.0f, square.z + 1.0f).depthStart;
                if (yOff != 0.0f) {
                    float dz = yOff / 96.0f * 0.0028867084f;
                    zFar -= dz;
                    zNear -= dz;
                }
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
            // renderCurrentAnim: an unlit sprite draws white, whatever
            // colour the object arrived with. Per part.
            if (sprite.getProperties().has(IsoFlagType.unlit)) {
                lr = 1.0f;
                lg = 1.0f;
                lb = 1.0f;
            }

            // Corner fractions get copied in buildPart: the shared pools
            // mutate on the game thread while the render thread draws.
            // Trample beats wind, as in performRenderFrame. Wind flora without a
            // trample bends in the shader: pool corners dropped, sway parameters on
            // the vertices.
            ObjectRenderEffects ore = object.getObjectRenderEffectsToApply();
            float windS = 0.0f;
            float windT = 0.0f;
            float windSeed = 0.0f;
            int windClass = -1;
            if (sprite.moveWithWind && ore == object.getWindRenderEffects() && !rigidFlora(sprite)) {
                ore = null;
                windS = (float) ((square.x - square.y) / SQRT2);
                windT = (float) ((square.x + square.y) / SQRT2);
                windSeed = TreeSway.hash(square.x * 7919 + square.y * 104729 + square.z * 31 + sprite.tileSheetIndex, 9);
                windClass = PlantClass.of(sprite);
            }

            ArrayList<WindSwayGrassDrawer.GrassQuad> parts = partsScratch;
            parts.clear();
            parts.add(buildPart(tex, mainDepthTex, sprite, baseSx, baseSy,
                    scaleX, scaleY, scaleX, scaleY, inst.flip,
                    zNear, zFar, ore, lr, lg, lb, alpha));
            parts.get(0).cls = windClass;

            // renderOverlaySprites: after main, before attachments; own
            // color, copyTargetAlpha multiplies the object alpha.
            IsoSprite overlay = object.getOverlaySprite();
            if (overlay != null) {
                IsoSpriteInstance odef = overlay.def;
                if (odef == null) return DebugStats.reject("overlayPart", overlay);
                if ((overlay.depthFlags & 4) != 0) return DebugStats.reject("opaque", overlay);
                Texture otex = overlay.getTextureForCurrentFrame(object.getDir(), object);
                if (otex == null || otex.getTextureId() == null) return DebugStats.reject("overlayPart", overlay);
                Texture odepth = selectDepthTexture(overlay, object);
                if (odepth == null) return DebugStats.reject(wallDepth(overlay) ? "wallDepth" : "overlayPart", overlay);
                if (odepth.getTextureId() == null) return DebugStats.reject("overlayPart", overlay);
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
                if (overlay.getProperties().has(IsoFlagType.unlit)) {
                    ocr = 1.0f;
                    ocg = 1.0f;
                    ocb = 1.0f;
                }
                float oAlpha = alpha;
                if (odef.copyTargetAlpha && oFactor != 1.0f) {
                    oAlpha = alpha * oFactor;
                }
                fixupScale(odef.scaleX, odef.scaleY, otex.getWidthOrig(), otex.getHeightOrig(), scaleScratch);
                float oScaleX = scaleScratch[0];
                float oScaleY = scaleScratch[1];
                if (oScaleX > 0.0f && oScaleY > 0.0f && oAlpha > 0.001f) {
                    parts.add(buildPart(otex, odepth, overlay, baseSx, baseSy,
                            oScaleX, oScaleY, oScaleX, oScaleY, odef.flip,
                            zNear, zFar, ore, ocr, ocg, ocb, oAlpha));
                    if (windClass >= 0) parts.get(parts.size() - 1).cls = PlantClass.of(overlay);
                }
            }

            for (int i = 0; i < attachedCount; ++i) {
                IsoSpriteInstance s = attachments.get(i);
                IsoSprite spr = s != null ? s.parentSprite : null;
                if (spr == null) return DebugStats.reject("attachPart", sprite);
                int frame = 0;
                if (spr.hasAnimation()) {
                    int frameCount = spr.getFrameCount();
                    if (s.frame >= (float) frameCount) {
                        frame = frameCount - 1;
                    } else if (s.frame > 0.0f) {
                        frame = (int) s.frame;
                    }
                }
                if ((spr.depthFlags & 4) != 0) return DebugStats.reject("opaque", spr);
                Texture tex2 = spr.getTextureForFrame(frame, object.getDir(), object.isUseSnowSprite());
                if (tex2 == null || tex2.getTextureId() == null) return DebugStats.reject("attachPart", spr);
                Texture depthTex2 = selectDepthTexture(spr, object);
                if (depthTex2 == null) return DebugStats.reject(wallDepth(spr) ? "wallDepth" : "attachPart", spr);
                if (depthTex2.getTextureId() == null) return DebugStats.reject("attachPart", spr);
                // IsoSpriteInstance.renderprep runs inside the draw we skip:
                // a copyTargetAlpha instance takes the object's alpha every
                // frame, the others step toward their own target. Read raw,
                // s.alpha is whatever vanilla's last draw left (a crown
                // stayed at the obscure fade after a hover drew it).
                if (s.copyTargetAlpha) {
                    s.targetAlpha = target;
                    s.alpha = alpha;
                } else if (!s.multiplyObjectAlpha) {
                    if (s.alpha < s.targetAlpha) {
                        s.alpha = Math.min(s.targetAlpha, s.alpha + IsoSprite.alphaStep);
                    } else if (s.alpha > s.targetAlpha) {
                        s.alpha = Math.max(s.targetAlpha, s.alpha - IsoSprite.alphaStep);
                    }
                    s.alpha = PZMath.clamp(s.alpha, 0.0f, 1.0f);
                }
                float a2 = s.alpha;
                if (s.multiplyObjectAlpha) {
                    a2 *= alpha;
                }
                if (a2 <= 0.001f) continue;
                fixupScale(s.scaleX, s.scaleY, tex2.getWidthOrig(), tex2.getHeightOrig(), scaleScratch);
                float sX2 = scaleScratch[0];
                float sY2 = scaleScratch[1];
                if (sX2 <= 0.0f || sY2 <= 0.0f) continue;
                // TileDepthModifier gets def scale for attachments, the
                // quad itself uses the instance scale (vanilla asymmetry).
                float uvSX = spr.def != null ? spr.def.scaleX : 1.0f;
                float uvSY = spr.def != null ? spr.def.scaleY : 1.0f;
                boolean unlit = spr.getProperties().has(IsoFlagType.unlit);
                float ar = unlit ? 1.0f : liR * fade;
                float ag = unlit ? 1.0f : liG * fade;
                float ab = unlit ? 1.0f : liB * fade;
                parts.add(buildPart(tex2, depthTex2, spr, baseSx, baseSy,
                        sX2, sY2, uvSX, uvSY, s.flip,
                        zNear, zFar, ore,
                        ar * s.tintr, ag * s.tintg, ab * s.tintb, a2));
                if (windClass >= 0) {
                    WindSwayGrassDrawer.GrassQuad aq = parts.get(parts.size() - 1);
                    aq.cls = PlantClass.of(spr);
                    aq.genus = BushGenus.of(spr);
                }
            }

            if (windClass >= 0) {
                assignPlantWind(parts, windClass, sprite, square, object.isUseSnowSprite(),
                        windS, windT, windSeed);
            }
            if (world) {
                for (int i = 0; i < parts.size(); ++i) {
                    WindSwayGrassDrawer.GrassQuad q = parts.get(i);
                    q.sqX = square.x;
                    q.sqY = square.y;
                    q.sqZ = square.z;
                    q.anchorX = anchorX;
                    q.anchorY = anchorY;
                    q.depthShift = dzShift;
                }
            }
            if (BatchSequencer.overflowed()) return false;
            // Ground snow turns the bake white: a tree list merged past
            // these quads depth-punches them into a pale fringe, so they
            // guard the merge barrier like walls.
            boolean snowGround;
            if (square == snowMemoSquare) {
                snowGround = snowMemoSnowy;
            } else {
                snowGround = FBORenderSnow.getInstance()
                        .gridSquareIsSnow(square.x, square.y, square.z);
                snowMemoSquare = square;
                snowMemoSnowy = snowGround;
            }
            for (int i = 0; i < parts.size(); ++i) {
                if (snowGround) {
                    parts.get(i).wall = true;
                    BatchSequencer.addWall(parts.get(i));
                } else {
                    BatchSequencer.add(parts.get(i));
                }
            }
            parts.clear();
            writePicker(object, playerIndex, pickerX, pickerY, wOrig, hOrig, scaleX, scaleY, alpha);
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
                WindSwayMod.trace("first grass object captured: " + (sprite.name != null ? sprite.name : "?"));
            }
            return true;
        } catch (Throwable t) {
            if (!captureFailedLogged) {
                captureFailedLogged = true;
                WindSwayMod.trace("grass capture failed, falling back to vanilla draw", t);
            }
            return false;
        }
    }

    private static final double SQRT2 = Math.sqrt(2.0);
    // Game thread; the parts of one object between build and enqueue.
    private static final ArrayList<WindSwayGrassDrawer.GrassQuad> partsScratch = new ArrayList<>(8);
    private static final float[] scaleScratch = new float[2];

    // performRenderFrame's tileScale fixups on a local copy: the skipped
    // vanilla draw applies them by mutating the shared def. out = scaleX,
    // scaleY.
    private static void fixupScale(float scaleX, float scaleY, int wOrig, int hOrig, float[] out) {
        if (Core.tileScale == 2 && wOrig == 64 && hOrig == 128) {
            scaleX = 2.0f;
            scaleY = 2.0f;
        }
        if (Core.tileScale == 2 && scaleX == 2.0f && scaleY == 2.0f && wOrig == 128 && hOrig == 256) {
            scaleX = 1.0f;
            scaleY = 1.0f;
        }
        out[0] = scaleX;
        out[1] = scaleY;
    }

    // Every part bends in the main part's frame, so a flower child stays on
    // its stalk. The object's lean class is the main part's, except that a
    // bush base with a crown child leans as the crown (a sparse spring or
    // autumn crown as sparse, near bare); the leaf flutter is per part.
    private static void assignPlantWind(ArrayList<WindSwayGrassDrawer.GrassQuad> parts, int windClass,
            IsoSprite sprite, IsoGridSquare square, boolean snowy, float windS, float windT, float windSeed) {
        refreshPlantFrame();
        WindSwayGrassDrawer.GrassQuad main = parts.get(0);
        int cls = windClass;
        int genus = BushGenus.of(sprite);
        for (int i = 1; i < parts.size(); ++i) {
            int pc = parts.get(i).cls;
            if (pc == PlantClass.CROWN) cls = PlantClass.CROWN;
            else if (pc == PlantClass.SPARSE && cls != PlantClass.CROWN) cls = PlantClass.SPARSE;
            if (parts.get(i).genus >= 0) genus = parts.get(i).genus;
        }
        double stiff = 1.0;
        if (cls == PlantClass.BLADES) {
            stiff = sprite.windType == 2 ? TreeSway.plantStiff2
                    : (sprite.windType == 3 ? TreeSway.plantStiff3 : 1.0);
        }
        // A bare base with a crown child is a leafy bush, not dead wood,
        // sparse crowns included. Dry factors belong to the ring model.
        boolean dry = TreeSway.plantModel != 0 && PlantClass.dry(sprite)
                && cls != PlantClass.CROWN && cls != PlantClass.SPARSE;
        // Snow-laden (texture swap on the same object): stiffer, the
        // leaves buried.
        float windFrac = (float) (TreeSway.plantAmpMax * stiff * PlantClass.lean[cls] * (dry ? TreeSway.dryLean : 1.0)
                * (snowy ? TreeSway.plantSnowLean : 1.0));
        // The object's frame spans every part: a crown above the base
        // sprite's top would otherwise sit at h 1 as a whole, and the
        // period would be the base's.
        float frameTop = main.oy;
        for (int i = 1; i < parts.size(); ++i) {
            frameTop = Math.min(frameTop, parts.get(i).oy);
        }
        float frameBottom = main.oy + main.h;
        float frameLeft = main.ox;
        double hUnits = (frameBottom - frameTop) / (32.0 * Core.tileScale);
        double hf = periodHeightOf(hUnits);
        float windPeriod = (float) (TreeSway.plantPeriod * PlantClass.period[cls] * hf * (dry ? TreeSway.dryPeriod : 1.0));
        float dampF = (float) (dry ? TreeSway.dryDamping : 1.0);
        float flutF = (float) (dry ? TreeSway.dryFlutter : 1.0);
        float bendPow = (float) PlantClass.bendPow[cls];
        float bladeVar = (float) PlantClass.bladeVar[cls];
        double snowF = snowy ? TreeSway.plantSnowLeaf : 1.0;
        double leafNow = pfLeafNow * snowF;
        float flickNow = pfFlickNow * (float) snowF;
        float block = (float) PlantClass.block[cls];
        float swingF = (float) PlantClass.swing[cls];
        float inertiaF = (float) PlantClass.inertia[cls];
        float lobeNow = snowy ? pfLobeNow * (float) TreeSway.plantSnowLean : pfLobeNow;
        double gSteady = 1.0;
        double gLeafAmp = 1.0;
        double gCell = 1.0;
        double gRate = 1.0;
        float gFlick = 1.0f;
        float gMask = 1.0f;
        if (genus >= 0) {
            windFrac *= (float) BushGenus.lean[genus];
            windPeriod *= (float) BushGenus.period[genus];
            bendPow *= (float) BushGenus.bendPow[genus];
            block *= (float) BushGenus.block[genus];
            swingF *= (float) BushGenus.swing[genus];
            gSteady = BushGenus.steady[genus];
            gLeafAmp = BushGenus.leafAmp[genus];
            gCell = BushGenus.leafCell[genus];
            gRate = pfGenusCellRate[genus];
            gFlick = (float) BushGenus.flick[genus];
            gMask = (float) BushGenus.mask[genus];
        }
        float barrier = barrierCode(square);
        // Lean with the wind, swing past upright only by the upwind cap
        // (screen-right is downwind for dir > 0).
        boolean right = pfRight;
        for (int i = 0; i < parts.size(); ++i) {
            WindSwayGrassDrawer.GrassQuad q = parts.get(i);
            q.windS = windS;
            q.windT = windT;
            q.windSeed = windSeed;
            q.windPeriod = windPeriod;
            q.windAmp = q.w * windFrac;
            q.bendPow = bendPow;
            q.bladeVar = bladeVar;
            int pc = q.cls < 0 ? cls : q.cls;
            if (WindSwayMod.debugLog) PlantClass.diag[pc]++;
            q.tipF = (float) PlantClass.tip[pc];
            q.sheenF = (float) PlantClass.sheen[pc];
            q.dampF = dampF;
            q.flutF = flutF;
            q.steadyF = (float) (Math.min(1.0, PlantClass.steady[pc] * gSteady)
                    * (snowy ? TreeSway.plantSnowSteady : 1.0));
            q.block = block;
            q.swingF = swingF;
            q.inertiaF = inertiaF;
            q.lobePx = lobeNow * (float) PlantClass.lobe[pc];
            float lift = TreeSway.plantTipLift(q.windAmp) * q.tipF;
            if (lift > 0.0f) q.padT = (float) Math.ceil(lift) + 1.0f;
            double la = PlantClass.leafAmp[pc];
            if (la > 0.0) {
                double amp = leafNow * la * leafSizeOf(q.h) * gLeafAmp;
                q.leafX = (float) (amp * PlantClass.leafX[pc]);
                q.leafY = (float) (amp * PlantClass.leafY[pc]);
                q.leafCell = (float) (pfLeafCell[pc] * gCell);
                q.leafRate = (float) (pfLeafRate[pc] * gRate);
                if (q.leafY > 0.0f) q.padT = Math.max(q.padT, (float) Math.ceil(q.leafY) + 1.0f);
            }
            q.flickPx = flickNow * (float) PlantClass.flick[pc] * gFlick;
            q.maskF = (float) PlantClass.mask[pc] * gMask;
            float down = WindSwayMod.plantPadOn ? TreeSway.plantReach(q.windAmp, true) : 0.0f;
            float up = WindSwayMod.plantPadOn ? TreeSway.plantReach(q.windAmp, false) : 0.0f;
            q.padL = right ? up : down;
            q.padR = right ? down : up;
            if (q.lobePx > 0.0f) {
                float lp = (float) Math.ceil(q.lobePx) + 1.0f;
                q.padL += lp;
                q.padR += lp;
                q.padT = Math.max(q.padT, (float) Math.ceil(q.lobePx * TreeSway.plantLobeY) + 1.0f);
            }
            q.barrier = barrier;
            float tU = (q.u1 - q.u0) / q.w;
            float tV = (q.v1 - q.v0) / q.h;
            q.frameTop = q.v0 + (frameTop - q.oy) * tV;
            q.frameBottom = q.v0 + (frameBottom - q.oy) * tV;
            q.frameLeft = q.u0 + (frameLeft - q.ox) * tU;
        }
    }

    // Frame-constant part of the windClass math, computed once per frame
    // instead of per captured object (the capture ran it ~2500-4750x per
    // frame). Everything here depends only on wPlant and the tuning knobs.
    private static int pfFrame = -1;
    private static double pfLeafNow;
    private static float pfFlickNow;
    private static float pfLobeNow;
    private static double pfLeafRefPx;
    private static boolean pfRight;
    private static final double[] pfLeafCell = new double[PlantClass.COUNT];
    private static final double[] pfLeafRate = new double[PlantClass.COUNT];
    private static final double[] pfGenusCellRate = new double[BushGenus.COUNT];
    // 1-entry pow memos: a field repeats the same few sprite heights, so
    // the memo hits almost every object; a miss just pays the pow it
    // would have paid anyway. Reset per frame with the knobs they bake in.
    private static long pfHfKey = Long.MIN_VALUE;
    private static double pfHf;
    private static int pfSizeKey = Integer.MIN_VALUE;
    private static double pfSizeF;

    private static void refreshPlantFrame() {
        int fc = IsoCamera.frameState.frameCount;
        if (fc == pfFrame) return;
        pfFrame = fc;
        double wp = TreeSway.wPlant;
        pfLeafNow = (TreeSway.plantLeafAmp + (TreeSway.plantLeafAmpStorm - TreeSway.plantLeafAmp) * wp)
                * TreeSway.plantLeafGate(wp);
        pfFlickNow = TreeSway.plantFlickPx(wp);
        pfLobeNow = TreeSway.plantLobePx(wp);
        pfLeafRefPx = TreeSway.plantLeafRefPx * 0.5 * Core.tileScale;
        pfRight = TreeSway.dir >= 0.0;
        for (int c = 0; c < PlantClass.COUNT; ++c) {
            double cellPx = PlantClass.leafCell[c];
            double rate = PlantClass.leafRate[c];
            double cl = PlantClass.cluster[c];
            if (cl > cellPx) {
                // Fine paint moves as twig-end clusters: the offset runs
                // on the larger cell, slower by the cell ratio.
                rate *= Math.pow(cellPx / cl, TreeSway.plantClusterRatePow);
                cellPx = cl;
            }
            pfLeafCell[c] = cellPx;
            pfLeafRate[c] = rate;
        }
        // The genus cell factor follows the same cluster rate rule.
        for (int g = 0; g < BushGenus.COUNT; ++g) {
            pfGenusCellRate[g] = Math.pow(BushGenus.leafCell[g], -TreeSway.plantClusterRatePow);
        }
        pfHfKey = Long.MIN_VALUE;
        pfSizeKey = Integer.MIN_VALUE;
    }

    private static double periodHeightOf(double hUnits) {
        double h = Math.max(hUnits, 0.1);
        long key = Double.doubleToLongBits(h);
        if (key != pfHfKey) {
            pfHfKey = key;
            pfHf = Math.pow(h / TreeSway.plantPeriodRefH, TreeSway.plantPeriodExp);
        }
        return pfHf;
    }

    private static double leafSizeOf(float partH) {
        int key = Float.floatToIntBits(partH);
        if (key != pfSizeKey) {
            pfSizeKey = key;
            pfSizeF = Math.min(1.0, Math.pow(partH / pfLeafRefPx, TreeSway.plantLeafSizePow));
        }
        return pfSizeF;
    }

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

    // setupTileDepth hands windows and wall overlays that found no map
    // above to setupWallDepth: directional wall depth, not replicated.
    private static boolean wallDepth(IsoSprite spr) {
        return spr.getProperties().has(IsoFlagType.windowN) || spr.getProperties().has(IsoFlagType.windowW)
                || spr.getProperties().has(IsoFlagType.WallOverlay)
                && (spr.getProperties().has(IsoFlagType.attachedN) || spr.getProperties().has(IsoFlagType.attachedW));
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
        if (wallDepth(spr)) return null;
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

        WindSwayGrassDrawer.GrassQuad q = WindSwayGrassDrawer.obtainQuad();
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
        // IsoDirectionFrame.doFlip is false only for IsoAnim's multi-texture
        // frames, which belong to moving objects: never captured.
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
        if (WindSwayMod.debugTint) {
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

}
