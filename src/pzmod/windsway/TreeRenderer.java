package pzmod.windsway;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.iso.IsoDepthHelper;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.fboRenderChunk.FBORenderTrees;

// Replaces FBORenderTrees.render for on-screen tree lists: vanilla draws
// every tree on its own (matrix, state calls, VBORenderer runs and a
// glBufferData flush per tree); here the whole list goes into one
// streaming VBO in list order, per-tree values ride as vertex attributes
// and a draw call happens only where the texture page or the vanilla
// state class (opaque / transparent / stencil) changes. The quads stay
// undeformed, widened by the sway reach; windsway_tree.frag bends the
// crown and flutters the leaves as sample offsets.
public final class TreeRenderer {

    private static final float SQRT2 = (float) Math.sqrt(2.0);
    private static final float STEP_SIZE = 9.765625E-4f;
    private static final float TREE_ANGLE = 2.3561945f;
    private static final float ISO_Y = 0.8164967f;

    public static volatile boolean warp = true;
    // Bilinear blend sharpening, 1 = plain bilinear.
    public static volatile double sharp = 2.5;
    // Crown profile: rigid trunk up to trunkFactor of the crown line, bend
    // exponent, storm shaping (trunk joins in, profile flattens), knee
    // above which a broad crown rides as a block.
    public static volatile double trunkFactor = 0.7;
    public static volatile double trunkStorm = 0.15;
    public static volatile double bendPow = 2.0;
    public static volatile double bendPowStorm = 0.35;
    public static volatile double crownKnee = 0.3;
    public static volatile double heightPow = 0.75;
    // Leaf flutter, calm to storm, scaled by (content height / leafRefH)^pow.
    public static volatile double leafAmp = 0.5;
    public static volatile double leafAmpStorm = 1.5;
    public static volatile double leafHz = 4.5;
    public static volatile double leafHzStorm = 4.0;
    public static volatile double leafCell = 6.0;
    public static volatile double leafRefH = 600.0;
    public static volatile double leafSizePow = 0.6;
    // Crown lobes, calm to storm, plus a share of the lean, capped.
    public static volatile double branchFloor = 0.35;
    public static volatile double branchStorm = 0.6;
    public static volatile double branchFrac = 0.1;
    public static volatile double branchMax = 1.0;
    public static volatile double branchHz = 0.8;
    public static volatile double branchHzStorm = 1.0;
    public static volatile double branchCellFrac = 0.6;
    public static volatile double branchCellMin = 32.0;
    public static volatile double branchYFrac = 0.25;
    // Jumbo boost from mid wind on, per-tree amplitude spread.
    public static volatile double giantBoost = 0.5;
    public static volatile double giantOnset = 0.25;
    public static volatile double giantFull = 0.7;
    public static volatile double treeJitter = 0.15;
    // Evergreens: stiff needles, own lean factor, bend start no lower than
    // coniferStart of the content height (their measured crown line sits
    // near the ground), own exponent up to the tip.
    public static volatile double coniferLeafAmp = 0.35;
    public static volatile double coniferLeafHz = 0.7;
    public static volatile double coniferLobeAmp = 1.0;
    public static volatile double coniferLean = 1.0;
    public static volatile double coniferStart = 0.2;
    public static volatile double coniferBendPow = 1.6;
    // Diagnostic layer switches.
    public static volatile boolean mainOn = true;
    public static volatile boolean branchOn = true;
    public static volatile boolean leafOn = true;
    // Floor strip depth offset, linear from the split row to the frame
    // bottom. Vanilla subtracts a constant 0.0015 from the whole strip:
    // at the SE corner the tree base sits one square-centre step
    // (0.00144) behind its own floor and needs that much; at the split
    // row it is already 0.00048 ahead of the floor, and the full step
    // there hides grass on the front-side squares along a straight line.
    public static volatile double floorHackTop = 0.0006;
    public static volatile double floorHackBottom = 0.0015;

    // 5s log counters, written on the render thread only.
    public static volatile int diagRenders;
    public static volatile int diagTrees;
    public static volatile int diagDraws;
    public static volatile int diagMaxTrees;

    private static volatile boolean ok = true;
    private static Shader shader;
    private static ShaderProgram program;
    private static final WindSwayMod.GlProbe glProbe = new WindSwayMod.GlProbe();

    // Game thread. After a latch: init again, once per new world or on
    // request from the console.
    static void rearm() {
        if (ok) return;
        shader = null;
        program = null;
        glProbe.reset();
        ok = true;
        WindSwayMod.trace("tree renderer re-armed, trying again");
    }

    // True while trees are drawn here; addTree then strips the shared pool
    // sway from the ORE and leaves only per-object effects (axe shudder).
    static boolean active() {
        return WindSwayMod.enabled && warp && ok;
    }

    private static MethodHandle mhTrees;
    private static MethodHandle mhPlayerX;
    private static MethodHandle mhPlayerY;
    private static MethodHandle mhPlayerZ;
    private static MethodHandle mhTexture;
    private static MethodHandle mhTexture2;
    private static MethodHandle mhX;
    private static MethodHandle mhY;
    private static MethodHandle mhZ;
    private static MethodHandle mhR;
    private static MethodHandle mhG;
    private static MethodHandle mhB;
    private static MethodHandle mhA;
    private static MethodHandle mhOre;
    private static MethodHandle mhOreX1;
    private static MethodHandle mhOreX2;
    private static MethodHandle mhUseStencil;
    private static MethodHandle mhFadeAlpha;
    private static MethodHandle mhTransparent;
    private static MethodHandle mhCutawayAlpha;
    private static MethodHandle mhDepthOffset;

    private static int uParams = -1;
    private static int uMode = -1;
    private static int uStepSize = -1;
    private static int uOutlineColor = -1;

    // Attribute layout, 8 x vec4 (location == slot):
    // 0 world position xyz + depth, 1 colour, 2 uv + height fraction +
    // branch cell px, 3 field pixel coords + uv per texel, 4 atlas rect,
    // 5 bend (lean u, bend start, exponent, branch amp u), 6 leaf (amp u,
    // amp v, band top v, band bottom v), 7 fade alpha, bend end, branch amp v.
    private static final int NUM_ATTRIBS = 8;
    private static final int STRIDE = NUM_ATTRIBS * 16;
    // Grows when one list outsizes it: a throw here would park the
    // renderer on the vanilla path for the rest of the session.
    private static int streamCapacity = 8 * 1024 * 1024;
    private static int streamVbo;
    private static int streamOffset;
    private static ByteBuffer stage = BufferUtils.createByteBuffer(512 * 1024);

    private static final int GROUP_OPAQUE = 0;
    private static final int GROUP_TRANSPARENT = 1;
    private static final int GROUP_STENCIL = 2;
    // Stencil tree that is also on the transparent path: vanilla's third
    // pass falls back to the plain shader instead of the outline.
    private static final int GROUP_STENCIL_T = 3;

    private static final class Run {
        TextureID tex;
        int first;
        int count;
        int group;
    }

    private static final ArrayList<Run> runs = new ArrayList<>(64);
    private static final ArrayList<Run> runPool = new ArrayList<>(64);

    // Billboard local -> world without the per-tree translation:
    // scale(-1,1,1) * rotateY(angle + pi) * translate(0, -0.72, 0), the
    // tail of vanilla's pushModelViewMatrix. Column-major coefficients.
    private static float b00, b01, b02, b10, b11, b12, b30, b31, b32;
    private static final Matrix4f mvCommon = new Matrix4f();
    private static final Matrix4f proj = new Matrix4f();
    private static final Matrix4f mvp = new Matrix4f();
    private static final Vector3f scratch = new Vector3f();

    private TreeRenderer() {
    }

    private static float floorHack(float rowsBelowSplit, float stripRows) {
        float t = stripRows > 0.0f ? Math.min(1.0f, Math.max(0.0f, rowsBelowSplit / stripRows)) : 1.0f;
        return (float) (floorHackTop + (floorHackBottom - floorHackTop) * t);
    }

    private static double smooth(double e0, double e1, double x) {
        double t = (x - e0) / (e1 - e0);
        if (t < 0.0) t = 0.0; else if (t > 1.0) t = 1.0;
        return t * t * (3.0 - 2.0 * t);
    }

    // Tree is package-private, so its getters take Object; the list holder
    // is typed, invokeExact needs the receiver type to match the call site.
    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> cls, String name, Class<?> as) throws Exception {
        return getter(lookup, cls, name, as, Object.class);
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> cls, String name, Class<?> as, Class<?> receiver) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return lookup.unreflectGetter(f).asType(MethodType.methodType(as, receiver));
    }

    private static volatile boolean handlesReady;

    // Reflection only, any thread; the GL part of init() stays on the
    // render thread.
    private static synchronized void initHandles() throws Exception {
        if (handlesReady) return;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        Class<?> treeClass = Class.forName("zombie.iso.fboRenderChunk.FBORenderTrees$Tree", true,
                FBORenderTrees.class.getClassLoader());
        MethodHandle trees = getter(lookup, FBORenderTrees.class, "trees", ArrayList.class, FBORenderTrees.class);
        mhPlayerX = getter(lookup, FBORenderTrees.class, "playerX", float.class, FBORenderTrees.class);
        mhPlayerY = getter(lookup, FBORenderTrees.class, "playerY", float.class, FBORenderTrees.class);
        mhPlayerZ = getter(lookup, FBORenderTrees.class, "playerZ", float.class, FBORenderTrees.class);
        mhTexture = getter(lookup, treeClass, "texture", Texture.class);
        mhTexture2 = getter(lookup, treeClass, "texture2", Texture.class);
        mhX = getter(lookup, treeClass, "x", float.class);
        mhY = getter(lookup, treeClass, "y", float.class);
        mhZ = getter(lookup, treeClass, "z", float.class);
        mhR = getter(lookup, treeClass, "r", float.class);
        mhG = getter(lookup, treeClass, "g", float.class);
        mhB = getter(lookup, treeClass, "b", float.class);
        mhA = getter(lookup, treeClass, "a", float.class);
        mhOre = getter(lookup, treeClass, "objectRenderEffects", boolean.class);
        mhOreX1 = getter(lookup, treeClass, "oreX1", float.class);
        mhOreX2 = getter(lookup, treeClass, "oreX2", float.class);
        mhUseStencil = getter(lookup, treeClass, "useStencil", boolean.class);
        mhFadeAlpha = getter(lookup, treeClass, "fadeAlpha", float.class);
        mhTransparent = getter(lookup, treeClass, "transparent", boolean.class);
        mhCutawayAlpha = getter(lookup, treeClass, "cutawayAlpha", float.class);
        mhDepthOffset = getter(lookup, treeClass, "depthOffset", int.class);
        mhTrees = trees;
        handlesReady = true;
    }

    // Game thread, before a tree list is queued: true when a tree of the
    // list draws see-through (stencil hole, fade, cutaway). Grass queued
    // after such a tree fails the depth test under its whole silhouette,
    // while vanilla's paint order lets grass behind it show through.
    static boolean hasSeeThrough(FBORenderTrees self) throws Throwable {
        if (!handlesReady) initHandles();
        ArrayList<?> trees = (ArrayList<?>) mhTrees.invokeExact(self);
        for (int i = 0; i < trees.size(); ++i) {
            Object tree = trees.get(i);
            if ((boolean) mhUseStencil.invokeExact(tree)) return true;
            if ((boolean) mhTransparent.invokeExact(tree)) return true;
            if ((float) mhFadeAlpha.invokeExact(tree) < 1.0f) return true;
            if ((float) mhCutawayAlpha.invokeExact(tree) < 1.0f) return true;
        }
        return false;
    }

    private static void init() throws Exception {
        initHandles();

        Matrix4f b = new Matrix4f().scale(-1.0f, 1.0f, 1.0f)
                .rotate(TREE_ANGLE + (float) Math.PI, 0.0f, 1.0f, 0.0f)
                .translate(0.0f, -0.71999997f, 0.0f);
        b00 = b.m00(); b01 = b.m01(); b02 = b.m02();
        b10 = b.m10(); b11 = b.m11(); b12 = b.m12();
        b30 = b.m30(); b31 = b.m31(); b32 = b.m32();

        shader = ShaderManager.instance.getOrCreateShader("windsway_tree", true, false);
        program = shader.getShaderProgram();
        if (!program.isCompiled() && !WindSwayMod.recompileShaderWithLog(program)) {
            throw new IllegalStateException("windsway_tree shader not compiled");
        }
        ShaderProgram.Uniform u;
        u = program.getUniform("uParams", 35666);
        uParams = u == null ? -1 : u.loc;
        u = program.getUniform("uMode", 35664);
        uMode = u == null ? -1 : u.loc;
        u = program.getUniform("stepSize", 35664);
        uStepSize = u == null ? -1 : u.loc;
        u = program.getUniform("outlineColor", 35666);
        uOutlineColor = u == null ? -1 : u.loc;
        shader.Start();
        program.setSamplerUnit("DIFFUSE", 0);
        shader.End();
    }

    private static void fail(Throwable t) {
        ok = false;
        System.out.println("[WindSway] tree renderer disabled, trees follow vanilla: " + t);
        t.printStackTrace(System.out);
    }

    private static void fail(String why) {
        ok = false;
        System.out.println("[WindSway] tree renderer disabled, trees follow vanilla: " + why);
    }

    // Render thread, in place of FBORenderTrees.render. Returns true when
    // the list was drawn (or is empty) and vanilla must skip.
    public static boolean render(FBORenderTrees self) {
        if (!WindSwayMod.enabled || !warp || !ok) return false;
        if (FBORenderChunkManager.instance.renderThreadCurrent != null) return false;
        ArrayList<?> trees;
        int vertCount;
        try {
            if (shader == null) init();
            trees = (ArrayList<?>) mhTrees.invokeExact(self);
            if (trees.isEmpty()) return true;
            vertCount = build(self, trees);
        } catch (Throwable t) {
            fail(t);
            return false;
        }
        try {
            draw(vertCount);
        } catch (Throwable t) {
            fail(t);
        }
        return true;
    }

    private static Run newRun(TextureID tex, int first, int group) {
        Run r = runPool.isEmpty() ? new Run() : runPool.remove(runPool.size() - 1);
        r.tex = tex;
        r.first = first;
        r.count = 0;
        r.group = group;
        runs.add(r);
        return r;
    }

    // Java-only phase: camera, per-tree sway, quads into the stage buffer.
    private static int build(FBORenderTrees self, ArrayList<?> trees) throws Throwable {
        SpriteRenderState renderState = SpriteRenderer.instance.getRenderingState();
        PlayerCamera camera = renderState.playerCamera[renderState.playerIndex];
        float playerX = (float) mhPlayerX.invokeExact(self);
        float playerY = (float) mhPlayerY.invokeExact(self);
        float playerZ = (float) mhPlayerZ.invokeExact(self);
        float rcx = camera.rightClickX;
        float rcy = camera.rightClickY;
        float tox = camera.getTOffX();
        float toy = camera.getTOffY();
        float cx = playerX - camera.XToIso(-tox - rcx, -toy - rcy, 0.0f) + camera.deferedX;
        float cy = playerY - camera.YToIso(-tox - rcx, -toy - rcy, 0.0f) + camera.deferedY;
        int camX = PZMath.fastfloor(playerX);
        int camY = PZMath.fastfloor(playerY);

        float screenWidth = (float) camera.offscreenWidth / 1920.0f;
        float screenHeight = (float) camera.offscreenHeight / 1920.0f;
        proj.setOrtho(-screenWidth / 2.0f, screenWidth / 2.0f, -screenHeight / 2.0f, screenHeight / 2.0f, -10.0f, 10.0f);
        mvCommon.scaling(Core.scale);
        mvCommon.scale((float) Core.tileScale / 2.0f);
        mvCommon.rotate(0.5235988f, 1.0f, 0.0f, 0.0f);
        mvCommon.rotate(TREE_ANGLE, 0.0f, 1.0f, 0.0f);
        mvp.set(proj).mul(mvCommon);

        int floorHeight = 32 * Core.tileScale;
        float dyFloor = (float) floorHeight / 2.0f;
        double n = TreeSway.w;
        double st = TreeSway.storm * n * n;
        float bendPowNow = (float) (bendPow - bendPowStorm * st);
        double giantRamp = smooth(giantOnset, giantFull, n);
        double leafGate = smooth(0.0, 0.04, n);
        double branchGate = smooth(0.02, 0.15, n);

        runs.clear();
        int maxBytes = trees.size() * 3 * 4 * STRIDE;
        if (stage.capacity() < maxBytes) {
            int cap = stage.capacity();
            while (cap < maxBytes) cap *= 2;
            stage = BufferUtils.createByteBuffer(cap);
        }
        stage.clear();
        int vertCount = 0;
        Run cur = null;

        for (int i = 0; i < trees.size(); ++i) {
            Object tree = trees.get(i);
            Texture texture = (Texture) mhTexture.invokeExact(tree);
            Texture texture2 = (Texture) mhTexture2.invokeExact(tree);
            if (texture == null && texture2 == null) continue;
            float tx = (float) mhX.invokeExact(tree);
            float ty = (float) mhY.invokeExact(tree);
            float tz = (float) mhZ.invokeExact(tree);
            float r = (float) mhR.invokeExact(tree);
            float g = (float) mhG.invokeExact(tree);
            float b = (float) mhB.invokeExact(tree);
            float a = (float) mhA.invokeExact(tree);
            boolean useStencil = (boolean) mhUseStencil.invokeExact(tree);
            boolean transparent = (boolean) mhTransparent.invokeExact(tree);
            float fadeAlpha = (float) mhFadeAlpha.invokeExact(tree);
            float cutawayAlpha = (float) mhCutawayAlpha.invokeExact(tree);
            int depthOffset = (int) mhDepthOffset.invokeExact(tree);
            float lean = 0.0f;
            if ((boolean) mhOre.invokeExact(tree)) {
                lean = 0.5f * ((float) mhOreX1.invokeExact(tree) + (float) mhOreX2.invokeExact(tree));
            }
            int group = useStencil ? (transparent ? GROUP_STENCIL_T : GROUP_STENCIL)
                    : transparent ? GROUP_TRANSPARENT : GROUP_OPAQUE;
            if (transparent) {
                a = Math.min(cutawayAlpha, fadeAlpha);
            }

            // Vanilla pushModelViewMatrix: translate(-difX, difZ, -difY)
            // between the camera part and the billboard part.
            float wX = -(tx - cx);
            float wY = (tz - playerZ) * 2.44949f;
            float wZ = -(ty - cy);
            // Depth: vanilla measures the clip z of the billboard origin
            // under the per-tree matrix; the origin sits at B * 0 =
            // (0, -0.72, 0) plus the translation.
            scratch.set(b30 + wX, b31 + wY, b32 + wZ);
            mvp.transformPosition(scratch);
            float depthBase = IsoDepthHelper.getSquareDepthData(camX, camY, tx - 0.5f, ty - 0.5f, tz).depthStart
                    - (scratch.z + 1.0f) / 2.0f + (float) depthOffset * 2.0E-4f;

            // Position hash: same tree, same numbers every frame.
            int hx = Float.floatToIntBits(tx) * 0x27d4eb2d;
            int hy = Float.floatToIntBits(ty) * 0x165667b1;
            int hs = (hx ^ (hy >>> 13)) * 0x9E3779B1;
            float h1 = ((hs >>> 8) & 0xFFFF) / 65535.0f;
            float h2 = ((hs >>> 20) & 0xFFF) / 4095.0f;
            int hs2 = hs * 0x85ebca6b;
            float h3 = ((hs2 >>> 20) & 0xFFF) / 4095.0f;
            float h4 = ((hs2 >>> 8) & 0xFFF) / 4095.0f;
            int hs3 = hs2 * 0xc2b2ae35;
            float h5 = ((hs3 >>> 20) & 0xFFF) / 4095.0f;
            float h6 = ((hs3 >>> 8) & 0xFFF) / 4095.0f;
            float seedX = h1 * 977.0f;
            float seedY = h2 * 613.0f;
            float jitter = (float) (1.0 - treeJitter + 2.0 * treeJitter * h3);

            // Seasonal trees are a bare base sprite plus a foliage overlay
            // (texture2); both take base, crown line and top from the pair
            // so branch tips and leaves bend as one.
            TreeProfile refProfile = TreeProfile.of(texture != null ? texture : texture2);
            TreeProfile p1 = texture != null ? refProfile : null;
            TreeProfile p2 = texture2 != null ? (texture != null ? TreeProfile.of(texture2) : refProfile) : null;
            float baseRow = refProfile.baseRow;
            float topRow = refProfile.topRow;
            if (p2 != null && p2.topRow < topRow) topRow = p2.topRow;
            float contentH = Math.max(1.0f, baseRow - topRow);
            float hCrown = (baseRow - refProfile.crownRow) / contentH;
            float hc = (float) (trunkFactor * (1.0 - trunkStorm * st) * hCrown);
            float hKnee = refProfile.conifer ? 1.0f : (float) Math.min(1.0, hCrown + crownKnee * (1.0 - hCrown));
            float powTree = bendPowNow;
            if (refProfile.conifer) {
                hc = Math.max(hc, (float) (coniferStart * (1.0 - trunkStorm * st)));
                powTree = (float) (coniferBendPow - bendPowStorm * st);
            }
            if (hKnee <= hc + 0.02f) hKnee = hc + 0.02f;
            int wRef = (texture != null ? texture : texture2).getWidthOrig();
            float sizeRef = wRef == FBORenderChunk.JUMBO_L_WIDTH || wRef == FBORenderChunk.JUMBO_XL_WIDTH
                    || wRef == FBORenderChunk.JUMBO_XXL_WIDTH ? wRef / 128.0f : 1.0f;
            double sway = mainOn ? TreeSway.lean(tx, ty, sizeRef, h1, h2, h3, h4, h5, h6) : 0.0;
            if (refProfile.conifer) sway *= coniferLean;

            for (int part = 0; part < 2; ++part) {
                Texture tex = part == 0 ? texture : texture2;
                if (tex == null) continue;
                TextureID texId = tex.getTextureId();
                if (texId == null) continue;
                TreeProfile profile = part == 0 ? p1 : p2;
                int wOrig = tex.getWidthOrig();
                float hOrig = tex.getHeightOrig();
                float offX = tex.getOffsetX();
                float offY = tex.getOffsetY();
                float w = tex.getWidth();
                float h = tex.getHeight();
                int unitsX;
                int unitsY;
                if (wOrig == FBORenderChunk.JUMBO_L_WIDTH) {
                    unitsX = 6;
                    unitsY = 8;
                } else if (wOrig == FBORenderChunk.JUMBO_XL_WIDTH) {
                    unitsX = 10;
                    unitsY = 12;
                } else if (wOrig == FBORenderChunk.JUMBO_XXL_WIDTH) {
                    unitsX = 14;
                    unitsY = 16;
                } else {
                    unitsX = 2;
                    unitsY = 4;
                }
                float sizeF = wOrig / 128.0f;
                float giant = (float) (1.0 + giantBoost * (sizeF - 1.0f) / 6.0f * giantRamp);
                // Main sway per tree; the ORE only carries per-object effects
                // (axe shudder), already jumbo-scaled. Both are ORE units: 128
                // texels on a regular sprite, times the jumbo width factor.
                float leanPx = (float) (sway * sizeF + lean) * 128.0f
                        * (float) Math.pow(contentH / hOrig, heightPow) * giant * jitter;
                float leafPx = profile.leafy ? (float) ((leafAmp + (leafAmpStorm - leafAmp) * n) * leafGate
                        * Math.min(1.0, Math.pow(contentH / leafRefH, leafSizePow))) : 0.0f;
                float branchPx = (float) Math.min(branchMax * sizeF,
                        (branchFloor + (branchStorm - branchFloor) * n) * sizeF * branchGate + branchFrac * Math.abs(leanPx));
                float leafRate = 1.0f;
                if (refProfile.conifer) {
                    leafPx *= (float) coniferLeafAmp;
                    leafRate = (float) coniferLeafHz;
                    branchPx *= (float) coniferLobeAmp;
                }
                float branchCell = (float) Math.max(branchCellMin, branchCellFrac * w);
                if (!branchOn) branchPx = 0.0f;
                if (!leafOn) leafPx = 0.0f;
                if (profile.rigid) {
                    leanPx = 0.0f;
                    leafPx = 0.0f;
                    branchPx = 0.0f;
                }
                float pad = Math.abs(leanPx) + leafPx + branchPx + 2.0f;
                // Vertical offsets can carry the crown top past the quad's
                // top edge; extend the first segment upward by that reach.
                float padY = (float) (0.3 * leafPx + branchYFrac * branchPx) + 1.0f;

                float xStart = tex.getXStart();
                float xEnd = tex.getXEnd();
                float yStartTex = tex.getYStart();
                float yEndTex = tex.getYEnd();
                float texelU = (xEnd - xStart) / w;
                float texelV = (yEndTex - yStartTex) / h;
                float dx = unitsX / SQRT2 / 2.0f;
                float xL = (offX - pad) / wOrig * unitsX / SQRT2 - dx;
                float xR = (offX + w + pad) / wOrig * unitsX / SQRT2 - dx;
                float uL = xStart - pad * texelU;
                float uR = xEnd + pad * texelU;
                // The measured leaf band holds only the dense rows; the sparse
                // crown top and fringe are leaves too, so for leafy sprites the
                // band spans from the shared top down to the trunk stub.
                float leafTopRow = profile.leafy ? topRow : profile.leafTopRow;
                float leafBottomRow = profile.leafy ? profile.stubTopRow : profile.leafBottomRow;
                float vLeafTop = yStartTex + (leafTopRow - offY) * texelV;
                float vLeafBottom = yStartTex + (leafBottomRow - offY) * texelV;
                float leanU = leanPx * texelU;
                float branchU = branchPx * texelU;
                float branchV = (float) (branchPx * branchYFrac) * texelV;
                float leafU = leafPx * texelU;
                float leafV = 0.3f * leafPx * texelV;
                float pixL = -pad + seedX;
                float pixR = w + pad + seedX;

                // Vanilla segments: base sprite in two pieces split above the
                // floor strip (the lower piece sits nearer, floorHackTop to
                // floorHackBottom instead of vanilla's constant 0.0015),
                // foliage overlay whole and 1e-4 in front. The split is depth only;
                // the sample rect stays the whole sprite, a per-segment rect
                // makes the bilinear blend the split row with transparent
                // and vertical offsets discard along it (visible seam).
                int segs = part == 0 ? 2 : 1;
                float y2 = part == 0 ? PZMath.min(hOrig - dyFloor, offY + h) : offY + h;
                for (int s = 0; s < segs; ++s) {
                    float top = s == 0 ? offY : y2;
                    float bottom = s == 0 ? y2 : offY + h;
                    if (bottom <= top) continue;
                    float depthT = depthBase;
                    float depthB = depthBase;
                    if (part == 1) {
                        depthT -= 1.0E-4f;
                        depthB -= 1.0E-4f;
                    }
                    if (top > offY) {
                        depthT -= floorHack(top - y2, dyFloor);
                        depthB -= floorHack(bottom - y2, dyFloor);
                    }
                    float vSeg1 = yStartTex + (bottom - offY) * texelV;
                    float rowT = s == 0 ? top - padY : top;
                    float yT = unitsY - rowT / hOrig * unitsY;
                    float yB = (hOrig - bottom) / hOrig * unitsY;
                    float vQuad0 = yStartTex + (rowT - offY) * texelV;
                    float hTop = (baseRow - rowT) / contentH;
                    float hBottom = (baseRow - bottom) / contentH;
                    float pixT = (rowT - topRow) + seedY;
                    float pixB = (bottom - topRow) + seedY;

                    if (cur == null || cur.tex != texId || cur.group != group) {
                        cur = newRun(texId, vertCount, group);
                    }
                    ByteBuffer o = stage;
                    putVertex(o, xL, yT, wX, wY, wZ, depthT, r, g, b, a, uL, vQuad0, hTop, branchCell,
                            pixL, pixT, texelU, texelV, xStart, xEnd, yStartTex, yEndTex,
                            leanU, hc, powTree, branchU, leafU, leafV, vLeafTop, vLeafBottom, fadeAlpha, hKnee, branchV, leafRate);
                    putVertex(o, xR, yT, wX, wY, wZ, depthT, r, g, b, a, uR, vQuad0, hTop, branchCell,
                            pixR, pixT, texelU, texelV, xStart, xEnd, yStartTex, yEndTex,
                            leanU, hc, powTree, branchU, leafU, leafV, vLeafTop, vLeafBottom, fadeAlpha, hKnee, branchV, leafRate);
                    putVertex(o, xR, yB, wX, wY, wZ, depthB, r, g, b, a, uR, vSeg1, hBottom, branchCell,
                            pixR, pixB, texelU, texelV, xStart, xEnd, yStartTex, yEndTex,
                            leanU, hc, powTree, branchU, leafU, leafV, vLeafTop, vLeafBottom, fadeAlpha, hKnee, branchV, leafRate);
                    putVertex(o, xL, yB, wX, wY, wZ, depthB, r, g, b, a, uL, vSeg1, hBottom, branchCell,
                            pixL, pixB, texelU, texelV, xStart, xEnd, yStartTex, yEndTex,
                            leanU, hc, powTree, branchU, leafU, leafV, vLeafTop, vLeafBottom, fadeAlpha, hKnee, branchV, leafRate);
                    cur.count += 4;
                    vertCount += 4;
                }
            }
        }
        diagRenders++;
        diagTrees += trees.size();
        if (trees.size() > diagMaxTrees) diagMaxTrees = trees.size();
        return vertCount;
    }

    private static void putVertex(ByteBuffer o, float lx, float ly, float wX, float wY, float wZ, float depth,
                                  float r, float g, float b, float a, float u, float v, float hf, float cell,
                                  float px, float py, float texelU, float texelV,
                                  float rx, float ry, float rz, float rw,
                                  float leanU, float hc, float pow, float branchU,
                                  float leafU, float leafV, float bandTop, float bandBottom, float fade,
                                  float knee, float branchV, float leafRate) {
        float y = ly * ISO_Y;
        o.putFloat(b00 * lx + b10 * y + b30 + wX);
        o.putFloat(b01 * lx + b11 * y + b31 + wY);
        o.putFloat(b02 * lx + b12 * y + b32 + wZ);
        o.putFloat(depth);
        o.putFloat(r).putFloat(g).putFloat(b).putFloat(a);
        o.putFloat(u).putFloat(v).putFloat(hf).putFloat(cell);
        o.putFloat(px).putFloat(py).putFloat(texelU).putFloat(texelV);
        o.putFloat(rx).putFloat(ry).putFloat(rz).putFloat(rw);
        o.putFloat(leanU).putFloat(hc).putFloat(pow).putFloat(branchU);
        o.putFloat(leafU).putFloat(leafV).putFloat(bandTop).putFloat(bandBottom);
        o.putFloat(fade).putFloat(knee).putFloat(branchV).putFloat(leafRate);
    }

    // GL phase. Mirrors renderTree's raw state calls; the tracked state is
    // restored at the end exactly like vanilla's render().
    private static void draw(int vertCount) {
        if (vertCount == 0) {
            releaseRuns();
            return;
        }
        stage.flip();
        int bytes = vertCount * STRIDE;

        Matrix4f p = Core.getInstance().projectionMatrixStack.alloc();
        p.set(proj);
        Core.getInstance().projectionMatrixStack.push(p);
        Matrix4f m = Core.getInstance().modelViewMatrixStack.alloc();
        m.set(mvCommon);
        Core.getInstance().modelViewMatrixStack.push(m);
        try {
            boolean glCheck = glProbe.begin();
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glDepthMask(true);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glEnable(GL11.GL_TEXTURE_2D);
            // The shader outputs premultiplied colour, so one blend func
            // serves every group. Vanilla's opaque trees inherit the pass's
            // premultiplied default while its transparent ones switch to
            // straight alpha; a straight-alpha silhouette under the default
            // rims every bilinear edge bright.
            GL11.glBlendFunc(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);

            if (streamVbo == 0) {
                streamVbo = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamVbo);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, streamCapacity, GL15.GL_STREAM_DRAW);
            } else {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamVbo);
            }
            if (bytes > streamCapacity) {
                while (streamCapacity < bytes) streamCapacity *= 2;
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, streamCapacity, GL15.GL_STREAM_DRAW);
                streamOffset = 0;
            } else if (streamOffset + bytes > streamCapacity) {
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, streamCapacity, GL15.GL_STREAM_DRAW);
                streamOffset = 0;
            }
            GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, streamOffset, stage);
            int baseVert = streamOffset / STRIDE;
            streamOffset += bytes;
            for (int i = 0; i < NUM_ATTRIBS; ++i) {
                GL20.glVertexAttribPointer(i, 4, GL11.GL_FLOAT, false, STRIDE, (long) i * 16L);
                GL20.glEnableVertexAttribArray(i);
            }

            shader.Start();
            VertexBufferObject.setModelViewProjection(shader);
            if (uParams >= 0) {
                GL20.glUniform4f(uParams, (float) TreeSway.branchClock, (float) TreeSway.leafClock,
                        (float) leafCell, (float) sharp);
            }
            if (uStepSize >= 0) GL20.glUniform2f(uStepSize, STEP_SIZE, STEP_SIZE);
            if (uOutlineColor >= 0) GL20.glUniform4f(uOutlineColor, 0.1f, 0.1f, 0.1f, 0.8f);
            setMode(0.0f, 0.0f);

            TextureID bound = null;
            boolean stencilOn = false;
            boolean stencilKnown = false;
            int i = 0;
            while (i < runs.size()) {
                int group = runs.get(i).group;
                int end = i;
                while (end < runs.size() && runs.get(end).group == group) ++end;
                if (group == GROUP_STENCIL || group == GROUP_STENCIL_T) {
                    if (!stencilOn) {
                        GL11.glEnable(GL11.GL_STENCIL_TEST);
                        stencilOn = true;
                        stencilKnown = true;
                    }
                    GL11.glStencilFunc(GL11.GL_NOTEQUAL, 128, 128);
                    bound = drawRuns(i, end, baseVert, bound);
                    GL11.glStencilFunc(GL11.GL_EQUAL, 128, 128);
                    setMode(0.0f, 1.0f);
                    bound = drawRuns(i, end, baseVert, bound);
                    setMode(group == GROUP_STENCIL ? 1.0f : 0.0f, 0.0f);
                    bound = drawRuns(i, end, baseVert, bound);
                    setMode(0.0f, 0.0f);
                    GL11.glStencilFunc(GL11.GL_ALWAYS, 255, 255);
                } else {
                    if (stencilOn || !stencilKnown) {
                        GL11.glDisable(GL11.GL_STENCIL_TEST);
                        stencilOn = false;
                        stencilKnown = true;
                    }
                    bound = drawRuns(i, end, baseVert, bound);
                }
                i = end;
            }

            shader.End();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            // VBORenderer's resting state: attributes 0-4 enabled, the rest
            // off; plus its contract with the RingBuffer.
            for (int k = 0; k < NUM_ATTRIBS; ++k) {
                if (k < 5) {
                    GL20.glEnableVertexAttribArray(k);
                } else {
                    GL20.glDisableVertexAttribArray(k);
                }
            }
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            if (glCheck) {
                int err = glProbe.end();
                if (err != GL11.GL_NO_ERROR) {
                    fail("GL error 0x" + Integer.toHexString(err) + " after the list draw");
                }
            }
        } finally {
            Core.getInstance().modelViewMatrixStack.pop();
            Core.getInstance().projectionMatrixStack.pop();
            releaseRuns();
            GLStateRenderThread.restore();
        }
    }

    private static void setMode(float outline, float capAlpha) {
        if (uMode >= 0) GL20.glUniform2f(uMode, outline, capAlpha);
    }

    private static TextureID drawRuns(int from, int to, int baseVert, TextureID bound) {
        for (int i = from; i < to; ++i) {
            Run run = runs.get(i);
            if (run.tex != bound) {
                run.tex.bind();
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                bound = run.tex;
            }
            GL11.glDrawArrays(GL11.GL_QUADS, baseVert + run.first, run.count);
            diagDraws++;
        }
        return bound;
    }

    private static void releaseRuns() {
        for (int i = 0; i < runs.size(); ++i) {
            runPool.add(runs.get(i));
        }
        runs.clear();
    }
}
