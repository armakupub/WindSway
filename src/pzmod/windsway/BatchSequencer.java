package pzmod.windsway;

import java.lang.reflect.Field;
import java.util.ArrayList;

import me.zed_0xff.zombie_buddy.Accessor;

import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.textures.Texture;
import zombie.iso.IsoCamera;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderCell;
import zombie.iso.fboRenderChunk.FBORenderTrees;
import zombie.iso.objects.IsoTree;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.sprite.IsoSpriteInstance;
import zombie.popman.ObjectPool;

// Game-thread ordering of the captured batch against vanilla's paint
// order: quads and held tree lists form one arrival sequence, flushed
// before any vanilla translucent draw that can overlap them and drained
// interleaved (Q0 T0 Q1 T1 ...), so nothing is painted early.
public final class BatchSequencer {

    private BatchSequencer() {
    }

    // Scratch for GrassQuad.bounds; game thread only.
    private static final float[] qBounds = new float[4];

    // Game-thread only. Drained mid-pass (onVanillaTranslucentDraw) and
    // at pass end (onTranslucentPassDone).
    private static ArrayList<WindSwayGrassDrawer.GrassQuad> pendingQuads = new ArrayList<>();

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

    private static void extendPendingBounds(WindSwayGrassDrawer.GrassQuad q) {
        float[] b = qBounds;
        q.bounds(b);
        if (!pendBoundsValid) {
            pendBoundsValid = true;
            pendMinX = b[0];
            pendMaxX = b[2];
            pendMinY = b[1];
            pendMaxY = b[3];
        } else {
            pendMinX = Math.min(pendMinX, b[0]);
            pendMaxX = Math.max(pendMaxX, b[2]);
            pendMinY = Math.min(pendMinY, b[1]);
            pendMaxY = Math.max(pendMaxY, b[3]);
        }
    }

    static volatile boolean flushPrecise = true;

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
        float[] b = qBounds;
        for (int k = 0; k < pendingQuads.size(); ++k) {
            pendingQuads.get(k).bounds(b);
            if (ux0 >= b[2] || ux1 <= b[0] || uy0 >= b[3] || uy1 <= b[1]) continue;
            for (int i = 0; i < n; ++i) {
                if (r[i * 4] < b[2] && r[i * 4 + 2] > b[0] && r[i * 4 + 1] < b[3] && r[i * 4 + 3] > b[1]) {
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
        grass.setFrameCamera(camOffJX, camOffJY, camJigSqX, camJigSqY,
                camCentreX, camCentreY, camK1, passWorld);
        pendingQuads = new ArrayList<>();
        pendBoundsValid = false;
        SpriteRenderer.instance.drawGeneric(grass);
        if (WindSwayMod.debugLog) {
            DebugStats.flushCount5s++;
            DebugStats.flushQuads5s += n;
            DebugStats.grassSubDraws5s++;
            if (n > DebugStats.maxBatch5s) {
                DebugStats.maxBatch5s = n;
            }
        }
    }

    // True when a contrast quad captured since the tail segment began
    // overlaps one of the incoming list's trees on screen: the quad sits
    // between those trees and this list in paint order, and merged past it
    // the list's apron would depth-punch it. Plain grass quads are ignored:
    // their punch holes land on the ground bake, green on green; over
    // ground snow the bake is white, so those quads carry the flag too.
    // Splitting on all grass would kill the batching (fired on 92% of the
    // lists in a grassy forest). Order is the only clean fix here: a
    // depth-write alpha floor on the apron shimmered with the sway.
    private static boolean wallSinceSegHit(ArrayList<?> trees) throws Throwable {
        if (!wallSinceValid) return false;
        int mark = segMark[segCount - 1];
        int nq = pendingQuads.size();
        float[] b = qBounds;
        for (int t = 0; t < trees.size(); ++t) {
            TreeRenderer.treeBox(trees.get(t), treeBox);
            float bx0 = treeBox[0];
            float by0 = treeBox[1];
            float bx1 = treeBox[2];
            float by1 = treeBox[3];
            if (bx0 >= wallSinceMaxX || bx1 <= wallSinceMinX
                    || by0 >= wallSinceMaxY || by1 <= wallSinceMinY) {
                continue;
            }
            for (int k = mark; k < nq; ++k) {
                WindSwayGrassDrawer.GrassQuad q = pendingQuads.get(k);
                if (!q.wall) continue;
                q.bounds(b);
                if (bx0 < b[2] && bx1 > b[0] && by0 < b[3] && by1 > b[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void extendWallSince(WindSwayGrassDrawer.GrassQuad q) {
        float[] b = qBounds;
        q.bounds(b);
        if (!wallSinceValid) {
            wallSinceValid = true;
            wallSinceMinX = b[0];
            wallSinceMaxX = b[2];
            wallSinceMinY = b[1];
            wallSinceMaxY = b[3];
        } else {
            wallSinceMinX = Math.min(wallSinceMinX, b[0]);
            wallSinceMaxX = Math.max(wallSinceMaxX, b[2]);
            wallSinceMinY = Math.min(wallSinceMinY, b[1]);
            wallSinceMaxY = Math.max(wallSinceMaxY, b[3]);
        }
    }

    // Draws the arrival sequence: quads before segment 0, segment 0, quads
    // between segments 0 and 1, ... The grass batch is built and uploaded
    // once (the queued ranges share it); every tree segment stays its own
    // list draw. With keepTail the quads captured after the last segment
    // began stay pending: nothing held constrains them.
    private static void drainSequence(int cause, boolean keepTail) {
        long tD = WindSwayMod.debugLog ? System.nanoTime() : 0L;
        try {
            drainSequenceInner(cause, keepTail);
        } finally {
            if (tD != 0L) DebugStats.gtDrainNs += System.nanoTime() - tD;
        }
    }

    private static void drainSequenceInner(int cause, boolean keepTail) {
        if (segCount == 0) {
            if (!keepTail) flushPending();
            return;
        }
        treeFlushing = true;
        try {
            int n = segCount;
            int size = pendingQuads.size();
            int drawn = keepTail ? Math.min(segMark[n - 1], size) : size;
            ArrayList<WindSwayGrassDrawer.GrassQuad> head;
            if (drawn == size) {
                head = pendingQuads;
                pendingQuads = new ArrayList<>();
                pendBoundsValid = false;
            } else {
                head = new ArrayList<>(pendingQuads.subList(0, drawn));
                pendingQuads.subList(0, drawn).clear();
                pendBoundsValid = false;
                for (int k = 0; k < pendingQuads.size(); ++k) {
                    extendPendingBounds(pendingQuads.get(k));
                }
            }
            int[] cuts = null;
            WindSwayGrassDrawer master = null;
            if (!head.isEmpty()) {
                cuts = new int[n + 2];
                for (int i = 0; i < n; ++i) {
                    cuts[i + 1] = Math.min(segMark[i], drawn);
                }
                cuts[n + 1] = drawn;
                int live = 0;
                for (int i = 0; i <= n; ++i) {
                    if (cuts[i + 1] > cuts[i]) ++live;
                }
                master = new WindSwayGrassDrawer();
                master.setSegmented(head, cuts, live);
                master.setFrameCamera(camOffJX, camOffJY, camJigSqX, camJigSqY,
                        camCentreX, camCentreY, camK1, passWorld);
                if (WindSwayMod.debugLog) {
                    DebugStats.flushCount5s++;
                    DebugStats.flushQuads5s += head.size();
                    DebugStats.grassSubDraws5s += live;
                    if (head.size() > DebugStats.maxBatch5s) {
                        DebugStats.maxBatch5s = head.size();
                    }
                }
            }
            if (WindSwayMod.debugLog) {
                if (cause == FLUSH_TREES_OBJ) DebugStats.treeFlushObj5s++;
                else if (cause == FLUSH_TREES_SEE) DebugStats.treeFlushSee5s++;
                else DebugStats.treeFlushPass5s++;
            }
            for (int i = 0; i <= n; ++i) {
                if (master != null && cuts[i + 1] > cuts[i]) {
                    SpriteRenderer.instance.drawGeneric(master.segment(i));
                }
                if (i < n) {
                    FBORenderTrees seg = segList[i];
                    segList[i] = null;
                    if (WindSwayMod.debugLog) {
                        int tn = 0;
                        try {
                            tn = TreeRenderer.trees(seg).size();
                        } catch (Throwable ignored) {
                        }
                        DebugStats.mergedTrees5s += tn;
                        if (tn > DebugStats.mergedMax5s) DebugStats.mergedMax5s = tn;
                        DebugStats.treeSegDraws5s++;
                    }
                    SpriteRenderer.instance.drawGeneric(seg);
                }
            }
        } finally {
            releaseSegments();
            treeFlushing = false;
        }
    }

    // Flush before a no-depth-write vanilla translucent that can touch the
    // pending batches: the sequence drains in arrival order. Trees and
    // character models write depth and need none.
    public static void onVanillaTranslucentDraw(IsoObject object, boolean doorOrWall) {
        try {
            if (pendingQuads.isEmpty() && segCount == 0) return;
            if (!FBORenderCell.instance.renderTranslucentOnly) return;
            if (object instanceof IsoTree) return;
            IsoGridSquare square = object != null ? object.getSquare() : null;
            if (square == null) {
                DebugStats.noteFlushTrigger(object, doorOrWall);
                drainSequence(FLUSH_TREES_OBJ, false);
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
            if (segCount > 0) {
                boolean hit = rectHitsTreeUnion(objRect) && (!precise || rectHitsTrees(objRect));
                if (hit) {
                    drainSequence(FLUSH_TREES_OBJ, true);
                } else if (WindSwayMod.debugLog) {
                    DebugStats.treeGateSkip5s++;
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
                if (WindSwayMod.debugLog) DebugStats.gateSkip5s++;
                return;
            }
            DebugStats.noteFlushTrigger(object, doorOrWall);
            drainSequence(FLUSH_TREES_OBJ, false);
        } catch (Throwable t) {
            // Ordering beats batching: if the bounds test dies, draw what
            // we have.
            drainSequence(FLUSH_TREES_OBJ, false);
        }
    }

    // Tree list merge. Vanilla cuts FBORenderTrees.current at every non-tree
    // translucent in paint order (hundreds of one-tree lists per frame in a
    // forest). Lists are held (drawGeneric skipped) and merged into the tail
    // of a segment sequence; quads and segments keep their arrival order and
    // the drain queues them interleaved (Q0 T0 Q1 T1 ... Qn), so the paint
    // order is vanilla's without drawing anything early. Valid because
    // everything between two opaque lists depth-tests against the trees and
    // writes no depth. A see-through tree breaks that for the grass captured
    // before it: such a list drains the sequence first and opens a fresh
    // one. Tree objects come from one ownerless pool.
    private static FBORenderTrees[] segList = new FBORenderTrees[16];
    // pendingQuads.size() when the segment was opened: quads below the mark
    // paint before the segment's trees, quads at or above it after.
    private static int[] segMark = new int[16];
    private static int segCount;
    private static int pendingTreeFrame = -1;
    private static boolean treeFlushing;
    // Union of the wall quads captured since the tail segment began; false =
    // none, the merge check short-circuits.
    private static boolean wallSinceValid;
    private static float wallSinceMinX;
    private static float wallSinceMinY;
    private static float wallSinceMaxX;
    private static float wallSinceMaxY;
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
    private static final float[] holeRects = new float[8];
    private static final float[] seeRects = new float[64 * 4];

    // skipOn advice on SpriteRenderer.drawGeneric: true = the list is held.
    public static boolean onTreeListDraw(Object drawer) {
        if (!(drawer instanceof FBORenderTrees)) return false;
        if (treeFlushing) return false;
        long t0 = WindSwayMod.debugLog ? System.nanoTime() : 0L;
        try {
            return onTreeListDrawInner(drawer);
        } finally {
            if (t0 != 0L) {
                DebugStats.gtTreeNs += System.nanoTime() - t0;
                DebugStats.gtTreeN++;
            }
        }
    }

    private static boolean onTreeListDrawInner(Object drawer) {
        try {
            if (!FBORenderCell.instance.renderTranslucentOnly) return false;
            if (pendingQuads.isEmpty() && segCount == 0
                    && (!WindSwayMod.enabled || !mergeOk || !TreeRenderer.active())) {
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
                if (WindSwayMod.debugLog) {
                    if (n == 0) DebugStats.seeSkipRect5s++;
                    else if (!bboxHit) DebugStats.seeSkipBbox5s++;
                    else if (!quadHit) DebugStats.seeSkipQuad5s++;
                }
            }
            if (WindSwayMod.debugLog && seeThrough) {
                DebugStats.seeLists5s++;
                if ((seeKind & TreeRenderer.SEE_STENCIL) != 0) DebugStats.seeStencil5s++;
                if ((seeKind & TreeRenderer.SEE_TRANSPARENT) != 0) DebugStats.seeTransp5s++;
                if ((seeKind & TreeRenderer.SEE_FADE) != 0) DebugStats.seeFade5s++;
                if ((seeKind & TreeRenderer.SEE_CUTAWAY) != 0) DebugStats.seeCut5s++;
            }
            if (!WindSwayMod.enabled || !mergeOk || !TreeRenderer.active()) {
                // Vanilla draws the lists; drain anything still held so the
                // order stays vanilla's.
                if (segCount > 0) {
                    drainSequence(FLUSH_TREES_OBJ, false);
                } else if (seeFlush && !pendingQuads.isEmpty()) {
                    if (WindSwayMod.debugLog) DebugStats.flushTree5s++;
                    flushPending();
                }
                return false;
            }
            int frame = IsoCamera.frameState.frameCount;
            if (segCount > 0 && pendingTreeFrame != frame) {
                dropStaleSegments();
            }
            if (seeFlush) {
                if (WindSwayMod.debugLog && !pendingQuads.isEmpty()) DebugStats.flushTree5s++;
                drainSequence(FLUSH_TREES_SEE, false);
            }
            ArrayList<?> src = TreeRenderer.trees(list);
            if (src.isEmpty()) return false;
            // A conflicting wall quad splits the sequence: this list opens a
            // new segment behind the quads instead of merging past them.
            // Nothing is drawn; the drain keeps the arrival order.
            if (segCount > 0 && wallSinceSegHit(src)) {
                if (WindSwayMod.debugLog) DebugStats.treeSplit5s++;
                openSegment(list, src, frame);
                return true;
            }
            if (segCount == 0) {
                openSegment(list, src, frame);
                return true;
            }
            ArrayList<?> dst = TreeRenderer.trees(segList[segCount - 1]);
            int at = dst.size();
            @SuppressWarnings("unchecked")
            ArrayList<Object> dstObj = (ArrayList<Object>) dst;
            // addAll copies the source to an array first.
            for (int i = 0; i < src.size(); ++i) {
                dstObj.add(src.get(i));
            }
            appendTreeBoxes(dst, at);
            src.clear();
            recycleList(list);
            if (WindSwayMod.debugLog) DebugStats.merged5s++;
            return true;
        } catch (Throwable t) {
            mergeOk = false;
            WindSwayMod.trace("tree list merge disabled: " + t, t);
            drainSequence(FLUSH_TREES_OBJ, false);
            return false;
        }
    }

    // Screen boxes of the held trees across every segment (x1 y1 x2 y2),
    // computed once per tree: the gate below runs per vanilla draw.
    private static float[] treeBoxes = new float[4 * 256];
    private static int treeBoxCount;

    private static void appendTreeBoxes(ArrayList<?> trees, int from) throws Throwable {
        int n = treeBoxCount + trees.size() - from;
        if (treeBoxes.length < n * 4) {
            int cap = treeBoxes.length;
            while (cap < n * 4) cap *= 2;
            float[] grown = new float[cap];
            System.arraycopy(treeBoxes, 0, grown, 0, treeBoxCount * 4);
            treeBoxes = grown;
        }
        float[] boxes = treeBoxes;
        for (int i = from; i < trees.size(); ++i) {
            TreeRenderer.treeBox(trees.get(i), treeBox);
            int k = treeBoxCount * 4;
            boxes[k] = treeBox[0];
            boxes[k + 1] = treeBox[1];
            boxes[k + 2] = treeBox[2];
            boxes[k + 3] = treeBox[3];
            if (treeBox[0] < treeMinX) treeMinX = treeBox[0];
            if (treeBox[2] > treeMaxX) treeMaxX = treeBox[2];
            if (treeBox[1] < treeMinY) treeMinY = treeBox[1];
            if (treeBox[3] > treeMaxY) treeMaxY = treeBox[3];
            ++treeBoxCount;
        }
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
            WindSwayMod.trace("tree list recycling disabled: " + t);
        }
    }

    private static void openSegment(FBORenderTrees list, ArrayList<?> src, int frame) throws Throwable {
        if (segCount == 0) {
            treeMinX = Float.MAX_VALUE;
            treeMinY = Float.MAX_VALUE;
            treeMaxX = -Float.MAX_VALUE;
            treeMaxY = -Float.MAX_VALUE;
            treeBoxCount = 0;
        }
        if (segCount == segList.length) {
            FBORenderTrees[] grownList = new FBORenderTrees[segCount * 2];
            System.arraycopy(segList, 0, grownList, 0, segCount);
            segList = grownList;
            int[] grownMark = new int[segCount * 2];
            System.arraycopy(segMark, 0, grownMark, 0, segCount);
            segMark = grownMark;
        }
        segList[segCount] = list;
        segMark[segCount] = pendingQuads.size();
        ++segCount;
        pendingTreeFrame = frame;
        wallSinceValid = false;
        appendTreeBoxes(src, 0);
        if (WindSwayMod.debugLog) DebugStats.held5s++;
    }

    private static void dropStaleSegments() {
        // A pass that ended without its OnExit left these behind.
        WindSwayMod.trace("stale tree lists dropped");
        releaseSegments();
    }

    // Lists still held were never queued (a throw mid-drain leaves the
    // tail behind), so vanilla's release is ours to call.
    private static void releaseSegments() {
        for (int i = 0; i < segCount; ++i) {
            FBORenderTrees seg = segList[i];
            segList[i] = null;
            if (seg == null) continue;
            try {
                seg.postRender();
            } catch (Throwable ignored) {
            }
        }
        segCount = 0;
        treeBoxCount = 0;
        wallSinceValid = false;
    }

    // Camera snapshot per (frame, player), handed to the drawers at drain:
    // the exact floats the capture's anchors used. offJ folds offX and the
    // jiggly pixel term into one value so the shader's single subtract
    // reproduces the capture's rounding.
    private static int camFrame = -1;
    private static int camPlayer = -1;
    private static boolean passWorld;
    static float camOffJX;
    static float camOffJY;
    private static float camJigSqX;
    private static float camJigSqY;
    private static float camCentreX;
    private static float camCentreY;
    static float camK1;

    static boolean latchFrameCamera(PlayerCamera camera, int playerIndex) {
        int fc = IsoCamera.frameState.frameCount;
        if (fc != camFrame || playerIndex != camPlayer) {
            camFrame = fc;
            camPlayer = playerIndex;
            passWorld = WindSwayMod.grassWorldPath;
            float zoom = IsoCamera.frameState.zoom;
            camOffJX = IsoCamera.frameState.offX - camera.fixJigglyModelsX * zoom;
            camOffJY = IsoCamera.frameState.offY - camera.fixJigglyModelsY * zoom;
            camJigSqX = camera.fixJigglyModelsSquareX;
            camJigSqY = camera.fixJigglyModelsSquareY;
            int camX = PZMath.fastfloor(IsoCamera.frameState.camCharacterX);
            int camY = PZMath.fastfloor(IsoCamera.frameState.camCharacterY);
            camCentreX = PZMath.fastfloor(camX / 8.0f);
            camCentreY = PZMath.fastfloor(camY / 8.0f);
            camK1 = 32.0f * Core.tileScale;
        }
        return passWorld;
    }

    // Game thread, from the captures: the quad joins the pending batch.
    static void add(WindSwayGrassDrawer.GrassQuad q) {
        pendingQuads.add(q);
        extendPendingBounds(q);
    }

    // Wall quads also extend the merge barrier of the tail segment.
    static void addWall(WindSwayGrassDrawer.GrassQuad q) {
        pendingQuads.add(q);
        extendPendingBounds(q);
        if (segCount > 0) extendWallSince(q);
    }

    // Canary: if the pass advice never drains us (weave failure), nothing
    // captured ever gets drawn.
    static boolean overflowed() {
        if (pendingQuads.size() <= 100000) return false;
        pendingQuads.clear();
        pendBoundsValid = false;
        WindSwayGrassDrawer.fail("pending batch overflowed, pass advice not running?");
        return true;
    }

    static void passDone() {
        if (WindSwayMod.debugLog && !pendingQuads.isEmpty()) {
            DebugStats.flushPass5s++;
        }
        drainSequence(FLUSH_TREES_PASS, false);
    }

    static void rearm() {
        mergeOk = true;
        treePoolOk = true;
    }
}
