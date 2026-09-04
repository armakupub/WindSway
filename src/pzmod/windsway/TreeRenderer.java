package pzmod.windsway;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;

import zombie.core.Core;
import zombie.core.SpriteRenderer;
import zombie.core.math.PZMath;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IShaderProgramListener;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.sprite.SpriteRenderState;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureID;
import zombie.iso.IsoDepthHelper;
import zombie.iso.PlayerCamera;
import zombie.iso.fboRenderChunk.FBORenderChunk;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;
import zombie.iso.IsoCamera;
import zombie.iso.IsoUtils;
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
    public static volatile double bendPowStorm = 0.2;
    public static volatile double crownKnee = 0.3;
    // Broadleaf bow above the trunk (profileRaw); the lean is the crown-centre
    // displacement.
    public static volatile double crownTail = 0.2;
    // Bent branches shorten (crownShorten * dx^2 / W) and the crown tilts
    // (crownTilt * dx / W).
    public static volatile double crownShorten = 0.5;
    public static volatile double crownTilt = 0.3;
    // Share of the lean carried by the whole trunk pivoting at the foot
    // (straight line from the ground, broadleaf only), linear in w between
    // calm and storm; the lowest trunkFoot of the height stays planted.
    public static volatile double trunkLeanCalm = 0.15;
    public static volatile double trunkLeanStorm = 0.5;
    public static volatile double trunkFoot = 0.04;
    public static volatile double heightPow = 0.75;
    public static volatile double leafAmp = 0.6;
    public static volatile double leafAmpStorm = 1.5;
    public static volatile double leafHz = 1.35;
    public static volatile double leafHzStorm = 4.0;
    public static volatile double leafCell = 6.0;
    public static volatile double leafRefH = 600.0;
    public static volatile double leafSizePow = 0.6;
    public static volatile double leafGustBase = 0.35;
    public static volatile double leafRateSpread = 0.15;
    // w >= 1 / leafWindFull: no breathing down; w >= 1 / leafWindDens: every
    // cell on.
    public static volatile double leafWindFull = 1.0;
    public static volatile double leafWindDens = 1.0;
    // Leaf mask: a cell flutters fully or not at all (a fraction of a pixel
    // everywhere reads as heat haze).
    public static volatile double leafMaskStrength = 1.0;
    public static volatile double leafMaskCell = 96.0;
    public static volatile double leafMaskFloor = 0.4;
    // Leaf cell = blade length (TreeSpecies, leafCell px for unknown sprites)
    // * (branch cell / lobeRefCell)^leafCellExp, at least leafCellMin px.
    public static volatile double leafCellExp = 0.2;
    public static volatile double leafCellMin = 4.0;
    // Fine-painted foliage (painted leaf below clusterPaintLo px) moves as
    // twig-end clusters of clusterPx instead of single dabs, leaf shapes
    // above clusterPaintHi as themselves; amplitude and rate follow
    // cell / leaf cell; the cluster offset snaps to whole texels on fine paint.
    public static volatile double clusterPx = 14.0;
    public static volatile double clusterPaintLo = 3.0;
    public static volatile double clusterPaintHi = 6.0;
    public static final double[] paintFamily = {0.5, 0.6, 0.9, 1.0};
    public static volatile double clusterAmpPow = 0.0;
    public static volatile double clusterRatePow = 0.35;
    public static volatile double clusterSnap = 0.0;
    // Leaf flicker: brightness on the flicker cell (r50 * flickCellK, at
    // least flickCellMin px), lit-cell density at calm / w 1 / full local
    // gust with its ramp on g, share outside the mask patches.
    public static volatile double flickAmp = 0.04;
    public static volatile double flickAmpStorm = 0.1;
    public static volatile double flickRate = 2.0;
    public static volatile double flickDuty = 0.55;
    public static volatile double flickDensCalm = 0.05;
    public static volatile double flickDensStorm = 0.9;
    public static volatile double flickDensGust = 0.4;
    public static volatile double flickWindOnset = 0.1;
    public static volatile double flickGustOnset = 0.2;
    public static volatile double flickGustFull = 0.7;
    public static volatile double flickOutside = 0.2;
    public static volatile double flickCellMin = 2.0;
    public static volatile double flickCellK = 1.0;
    // 64 px per world unit, a unit is about a metre.
    static final double PX_PER_CM = 0.64;
    public static volatile double leafGustDens = 0.6;
    public static volatile double leafShade = 0.03;
    public static volatile double leafShadeRate = 0.25;
    // Lobes: gated branchOnset..branchFull, driven by the change energy over
    // lobeRate plus branchFrac of the lean.
    public static volatile double branchFloor = 0.35;
    public static volatile double branchStorm = 0.6;
    public static volatile double branchFrac = 0.04;
    public static volatile double branchMax = 1.0;
    public static volatile double branchOnset = 0.12;
    public static volatile double branchFull = 0.45;
    public static volatile double branchGustBase = 0.1;
    public static volatile double lobeRate = 0.35;
    public static volatile double lobeWind = 1.0;
    public static volatile double branchCellFrac = 0.22;
    public static volatile double branchCellMin = 32.0;
    public static volatile double branchYFrac = 0.25;
    public static volatile double lobeRefCell = 32.0;
    public static volatile double lobeRateExp = 0.35;
    public static volatile double lobeFreqSpread = 0.4;
    // Second lobe octave = the twigs: lattice branchCell / twigScale, rate
    // x twigRate (y field x twigRateY on top), amplitude share twigAmp of
    // the lobe amplitude.
    public static volatile double twigScale = 2.1;
    public static volatile double twigRate = 1.7;
    public static volatile double twigAmp = 0.5;
    public static volatile double twigRateY = 1.118;
    // Jumbo boost from mid wind on, per-tree amplitude spread.
    public static volatile double giantBoost = 0.5;
    public static volatile double giantOnset = 0.0;
    public static volatile double giantFull = 0.3;
    public static volatile double giantFade = 1.0;
    public static volatile double treeJitter = 0.15;
    // Evergreens: stiff needles, own lean factor, bend start no lower than
    // coniferStart of the content height (their measured crown line sits
    // near the ground), own exponent up to the tip.
    public static volatile double coniferLeafAmp = 0.35;
    public static volatile double coniferLeafHz = 0.7;
    // The rate factor times the per-tree spread must stay below 2: the
    // attribute packs it with g.
    public static volatile double coniferLeafAmpStorm = 0.65;
    public static volatile double coniferLeafHzStorm = 0.9;
    public static volatile double coniferLobeAmp = 1.0;
    public static volatile double coniferLean = 1.6;
    public static volatile double coniferStart = 0.2;
    public static volatile double coniferBendPow = 1.5;
    // Evergreen lobes are branch tiers: mostly vertical, a tier moves as one,
    // in over lobeRamp above the bend start; the vertical share loses
    // lobeYStorm of itself toward w 1 (a steady bob at storm reads as jelly).
    public static volatile double coniferLobeX = 0.5;
    public static volatile double coniferLobeY = 0.8;
    public static volatile double coniferTierAspect = 3.0;
    public static volatile double coniferLobeRamp = 0.15;
    public static volatile double coniferLobeYStorm = 0.7;
    // Pixel floor for regular evergreens (width-scaled tiers sat below a
    // pixel), reduced below lobeMinRefH.
    public static volatile double coniferLobeMinPx = 1.2;
    public static volatile double coniferLobeMinRefH = 220.0;
    // Tiers pivot at the trunk: tierTrunk there, 1 at the half width.
    public static volatile double coniferTierTrunk = 0.15;
    public static volatile double coniferTierPow = 1.0;
    // Bare broadleaf crowns (no foliage overlay): rod to the tip, lean and
    // lobes as factors of the leafy tree's, finer lobe cell, no leaves.
    public static volatile double bareLean = 0.6;
    public static volatile double bareBendPow = 2.0;
    public static volatile double bareLobe = 0.5;
    public static volatile double bareCell = 0.6;
    // Lobe share kept by the bare wood part under a foliage overlay.
    public static volatile double woodLobe = 0.25;
    // Leaf-material mask on flutter and flicker (LeafMaskAtlas): 1 = painted
    // wood in the crown band holds still, 0 = off.
    public static volatile double woodMask = 1.0;
    // Diagnosis view, 0 = off; 7 = depth-punch view (every drawn fragment
    // adds 20% red, a quad culled behind an apron shows as a bake hole).
    public static volatile double debugMode = 0.0;
    // The bare factors blend in with (1 - foliage); a sparse autumn or
    // spring overlay sits between the leafy and the bare tree. Bare crowns
    // ring shorter and longer (leaves carry half the damping).
    public static volatile double barePeriod = 0.72;
    public static volatile double bareRing = 1.3;
    // Blossom overlays (redbud, dogwood, hawthorn spring) are not leaves.
    public static volatile double bloomLeaf = 0.3;
    // Scattered spring/autumn foliage reads as still at the summer
    // amplitude; the leaf channels ramp up as the overlay density falls
    // from sparseDense to sparseFull, wood and lean stay untouched.
    public static volatile double sparseLeafAmp = 1.6;
    public static volatile double sparseLeafRate = 1.15;
    public static volatile double sparseFlick = 1.3;
    public static volatile double sparseDense = 1.0;
    public static volatile double sparseFull = 0.5;
    // Diagnostic layer switches.
    public static volatile boolean mainOn = true;
    public static volatile boolean branchOn = true;
    public static volatile boolean leafOn = true;
    // Shader stage switches (uQual): off = not computed, unlike the layer
    // switches above.
    public static volatile boolean qualLobes = true;
    public static volatile boolean qualOctave2 = true;
    public static volatile boolean qualLeaves = true;
    public static volatile boolean qualMask = true;

    static void setQuality(boolean lobes, boolean octave2, boolean leaves, boolean mask, boolean shade) {
        qualLobes = lobes;
        qualOctave2 = octave2;
        qualLeaves = leaves;
        qualMask = mask;
        qualShade = shade;
    }
    public static volatile boolean qualShade = true;
    // Floor strip depth offset, linear from the split row to the frame
    // bottom. Vanilla subtracts a constant 0.0015 from the whole strip:
    // at the SE corner the tree base sits one square-centre step
    // (0.00144) behind its own floor and needs that much; at the split
    // row it is already 0.00048 ahead of the floor, and the full step
    // there hides grass on the front-side squares along a straight line.
    public static volatile double floorHackTop = 0.0006;
    public static volatile double floorHackBottom = 0.0015;

    // 5s log counters, written on the render thread; the log reads them
    // from the game thread as they come.
    static int diagRenders;
    static int diagTrees;
    static int diagDraws;
    static int diagMaxTrees;
    static final int[] diagClass = new int[TreeClass.COUNT];
    static final int[] diagSeason = new int[TreeProfile.SEASON_NONE + 1];

    static String diagClassLine() {
        StringBuilder sb = new StringBuilder("classes:");
        for (int c = 0; c < TreeClass.COUNT; ++c) {
            sb.append(' ').append(TreeClass.name(c)).append('=').append(diagClass[c]);
            diagClass[c] = 0;
        }
        sb.append(" | seasons bare/snow/spring/summer/late/autumn/none:");
        for (int s = 0; s <= TreeProfile.SEASON_NONE; ++s) {
            sb.append(s == 0 ? " " : "/").append(diagSeason[s]);
            diagSeason[s] = 0;
        }
        return sb.toString();
    }
    static final GpuTimer gpuTimer = new GpuTimer();
    static final AtomicLong cpuBuildNs = new AtomicLong();
    static final AtomicLong cpuDrawNs = new AtomicLong();
    // Quad area of the current list in sprite px (= offscreen px).
    private static double listArea;
    // Screen px under which a field is dropped (0 = never).
    static volatile double lodMinPx = 0.0;
    private static boolean glInfoLogged;

    private static volatile boolean ok = true;
    private static Shader shader;
    private static ShaderProgram program;
    private static int programId;
    private static final WindSwayMod.GlProbe glProbe = new WindSwayMod.GlProbe();
    private static volatile boolean rearmRequested;
    private static volatile boolean clearRequested;
    static String glInfo = "GL: ?";
    // Render thread, cumulative for the status line.
    static int totalLists;
    static int totalPageMiss;

    static String statusLine() {
        return "ok=" + ok + " warp=" + warp + " program=" + programId + " lists=" + totalLists
                + " pageMiss=" + totalPageMiss + " leafMask=" + LeafMaskAtlas.active();
    }

    // A recompile keeps the Shader object and may reuse the GL name: the
    // locations and sampler units are looked up again either way.
    private static final IShaderProgramListener ON_COMPILE = p -> programId = 0;

    // Game thread. After a latch: init again, once per new world or on
    // request from the console. The shader is the render thread's, it
    // drops it at its next list.
    static void rearm() {
        if (ok) return;
        rearmRequested = true;
        ok = true;
        WindSwayMod.trace("tree renderer re-armed, trying again");
    }

    // Game thread, per world: the texture-keyed caches are the render
    // thread's.
    static void requestClear() {
        clearRequested = true;
    }

    // True while trees are drawn here; addTree then strips the shared pool
    // sway from the ORE and leaves only per-object effects (axe shudder).
    static boolean active() {
        return WindSwayMod.enabled && warp && ok;
    }

    // Constant handles: C2 folds invokeExact on a static final into the
    // field read; a plain static goes through the LambdaForm invoker.
    // Tree is package-private, so its getters take Object; the list holder
    // is typed, invokeExact needs the receiver type to match the call site.
    private static final class H {
        static final MethodHandle trees;
        static final MethodHandle playerX;
        static final MethodHandle playerY;
        static final MethodHandle playerZ;
        static final MethodHandle texture;
        static final MethodHandle texture2;
        static final MethodHandle x;
        static final MethodHandle y;
        static final MethodHandle z;
        static final MethodHandle r;
        static final MethodHandle g;
        static final MethodHandle b;
        static final MethodHandle a;
        static final MethodHandle ore;
        static final MethodHandle oreX1;
        static final MethodHandle oreX2;
        static final MethodHandle useStencil;
        static final MethodHandle fadeAlpha;
        static final MethodHandle transparent;
        static final MethodHandle cutawayAlpha;
        static final MethodHandle depthOffset;

        static {
            try {
                MethodHandles.Lookup lookup = MethodHandles.lookup();
                Class<?> treeClass = Class.forName("zombie.iso.fboRenderChunk.FBORenderTrees$Tree", true,
                        FBORenderTrees.class.getClassLoader());
                trees = getter(lookup, FBORenderTrees.class, "trees", ArrayList.class, FBORenderTrees.class);
                playerX = getter(lookup, FBORenderTrees.class, "playerX", float.class, FBORenderTrees.class);
                playerY = getter(lookup, FBORenderTrees.class, "playerY", float.class, FBORenderTrees.class);
                playerZ = getter(lookup, FBORenderTrees.class, "playerZ", float.class, FBORenderTrees.class);
                texture = getter(lookup, treeClass, "texture", Texture.class);
                texture2 = getter(lookup, treeClass, "texture2", Texture.class);
                x = getter(lookup, treeClass, "x", float.class);
                y = getter(lookup, treeClass, "y", float.class);
                z = getter(lookup, treeClass, "z", float.class);
                r = getter(lookup, treeClass, "r", float.class);
                g = getter(lookup, treeClass, "g", float.class);
                b = getter(lookup, treeClass, "b", float.class);
                a = getter(lookup, treeClass, "a", float.class);
                ore = getter(lookup, treeClass, "objectRenderEffects", boolean.class);
                oreX1 = getter(lookup, treeClass, "oreX1", float.class);
                oreX2 = getter(lookup, treeClass, "oreX2", float.class);
                useStencil = getter(lookup, treeClass, "useStencil", boolean.class);
                fadeAlpha = getter(lookup, treeClass, "fadeAlpha", float.class);
                transparent = getter(lookup, treeClass, "transparent", boolean.class);
                cutawayAlpha = getter(lookup, treeClass, "cutawayAlpha", float.class);
                depthOffset = getter(lookup, treeClass, "depthOffset", int.class);
            } catch (Exception e) {
                throw new ExceptionInInitializerError(e);
            }
        }
    }

    private static int uParams = -1;
    private static int uMode = -1;
    private static int uStepSize = -1;
    private static int uOutlineColor = -1;
    private static int uMask = -1;
    private static int uLobe = -1;
    private static int uTwig = -1;
    private static int uCrown = -1;
    private static int uTrunk = -1;
    private static int uFlick = -1;
    private static int uFlick2 = -1;
    private static int uPage = -1;
    private static int uLeaf = -1;
    private static int uQual = -1;
    private static int uQual2 = -1;
    private static int uWood = -1;
    private static int uDebug = -1;

    // Uniform values of the current draw; re-sent only on change (nothing
    // else writes this program, so values survive across draws). init()
    // NaN-fills uLast: a fresh program gets everything. 4-float slots;
    // uStepSize and uPage use 2 of theirs.
    private static final float[] uNow = new float[68];
    private static final float[] uLast = new float[68];

    private static void sendU4(int loc, int at) {
        if (loc < 0) return;
        float[] n = uNow;
        float[] l = uLast;
        if (n[at] == l[at] && n[at + 1] == l[at + 1] && n[at + 2] == l[at + 2] && n[at + 3] == l[at + 3]) {
            return;
        }
        GL20.glUniform4f(loc, n[at], n[at + 1], n[at + 2], n[at + 3]);
        l[at] = n[at];
        l[at + 1] = n[at + 1];
        l[at + 2] = n[at + 2];
        l[at + 3] = n[at + 3];
    }

    private static void sendU2(int loc, int at) {
        if (loc < 0) return;
        float[] n = uNow;
        float[] l = uLast;
        if (n[at] == l[at] && n[at + 1] == l[at + 1]) return;
        GL20.glUniform2f(loc, n[at], n[at + 1]);
        l[at] = n[at];
        l[at + 1] = n[at + 1];
    }

    // Attribute layout, 10 x vec4 (location == slot):
    // 0 world position xyz + depth, 1 colour, 2 uv + height fraction +
    // branch cell px, 3 field pixel coords + packed clock cycles + branch
    // clock fraction, 4 atlas rect, 5 bend (lean u, bend start, exponent,
    // branch amp u), 6 leaf (amp u, amp v, band top v, band bottom v),
    // 7 fade alpha, bend end, branch amp v, leaf clock fraction,
    // 8 packed cells (leaf + 64 * flicker + 4096 * cluster, half px), snap,
    // local wind, flicker amplitude, 9 leaf-mask cell (u0, v0, u1, v1).
    private static final int NUM_ATTRIBS = 10;
    private static final int STRIDE = NUM_ATTRIBS * 16;
    // Grows when one list outsizes it: a throw here would park the
    // renderer on the vanilla path for the rest of the session.
    private static int streamCapacity = 8 * 1024 * 1024;
    private static int streamVbo;
    private static int streamOffset;
    private static ByteBuffer stage = BufferUtils.createByteBuffer(512 * 1024);
    private static FloatBuffer stageF = stage.asFloatBuffer();
    private static final int FLOATS = STRIDE / 4;
    private static final float[] quad = new float[4 * FLOATS];

    // The engine never binds a VAO, so ours holds the ten pointers into
    // the stream VBO (the id survives orphaning) and the default object
    // keeps VBORenderer's resting state untouched. Without support:
    // pointers per draw, resting state restored after.
    static volatile boolean useVao = true;
    private static int vao;
    private static int vaoSupport = -1;

    private static boolean vaoOk() {
        if (vaoSupport < 0) {
            try {
                GLCapabilities caps = Display.capabilities;
                vaoSupport = caps.OpenGL30 || caps.GL_ARB_vertex_array_object ? 1 : 0;
            } catch (Throwable t) {
                vaoSupport = 0;
            }
            if (vaoSupport == 0) WindSwayMod.trace("tree renderer: no vertex array objects, pointers per draw");
        }
        return vaoSupport == 1;
    }

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
        float pageW;
        float pageH;
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

    // Broadleaf bow before normalisation, mirrors windsway_tree.frag.
    private static double profileRaw(double x, double hc, double knee, double tail) {
        if (x <= hc) return 0.0;
        double sigma = knee - hc;
        if (x <= knee) {
            double u = (x - hc) / sigma;
            double u2 = u * u;
            return sigma * (u2 * u - 0.5 * u2 * u2);
        }
        double l = 1.0 - knee;
        double y = x - knee;
        return 0.5 * sigma + y + tail * y * y / (2.0 * l);
    }

    // Top over crown-centre displacement, for the pad.
    private static double profileTop(double hc, double knee, double tail, double trunkShare) {
        double centre = knee + 0.5 * (1.0 - knee);
        double dc = profileRaw(centre, hc, knee, tail);
        double bow = dc > 0.0 ? profileRaw(1.0, hc, knee, tail) / dc : 1.0;
        return bow + (1.0 / centre - bow) * trunkShare;
    }

    private static double trunkLeanNow(double n) {
        return trunkLeanCalm + (trunkLeanStorm - trunkLeanCalm) * n;
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> cls, String name, Class<?> as) throws Exception {
        return getter(lookup, cls, name, as, Object.class);
    }

    private static MethodHandle getter(MethodHandles.Lookup lookup, Class<?> cls, String name, Class<?> as, Class<?> receiver) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return lookup.unreflectGetter(f).asType(MethodType.methodType(as, receiver));
    }

    // Reflection only, any thread; the GL part of init() stays on the
    // render thread. A failed holder init resurfaces as NoClassDefFoundError
    // on every later touch, which the callers' latches catch.
    static void initHandles() {
        MethodHandle touch = H.trees;
    }

    // Render thread, queued at world start: shader compile, handle and
    // profile-table loads land in the loading screen instead of the first
    // tree list's frame.
    static void warm() {
        if (!ok || shader != null) return;
        try {
            init();
            TreeProfile.warm();
        } catch (Throwable t) {
            fail(t);
        }
    }

    static final class WarmDrawer extends TextureDraw.GenericDrawer {
        @Override
        public void render() {
            warm();
        }
    }

    static ArrayList<?> trees(FBORenderTrees self) throws Throwable {
        return (ArrayList<?>) H.trees.invokeExact(self);
    }

    static float treeX(Object tree) throws Throwable {
        return (float) H.x.invokeExact(tree);
    }

    static float treeY(Object tree) throws Throwable {
        return (float) H.y.invokeExact(tree);
    }

    static float treeZ(Object tree) throws Throwable {
        return (float) H.z.invokeExact(tree);
    }

    static final int SEE_STENCIL = 1;
    static final int SEE_TRANSPARENT = 2;
    static final int SEE_FADE = 4;
    static final int SEE_CUTAWAY = 8;

    // Game thread, before a tree list is queued: bit set of the see-through
    // causes present in the list. Grass queued after such a tree fails the
    // depth test under its whole silhouette, while vanilla's paint order
    // lets grass behind it show through.
    static int seeThroughKind(FBORenderTrees self) throws Throwable {
        if (WindSwayMod.videoMode) return 0;
        ArrayList<?> trees = (ArrayList<?>) H.trees.invokeExact(self);
        int kind = 0;
        for (int i = 0; i < trees.size(); ++i) {
            Object tree = trees.get(i);
            boolean transparent = (boolean) H.transparent.invokeExact(tree);
            if (transparent) {
                kind |= SEE_TRANSPARENT;
            } else if ((boolean) H.useStencil.invokeExact(tree)) {
                kind |= SEE_STENCIL;
            } else if ((float) H.fadeAlpha.invokeExact(tree) < 1.0f) {
                kind |= SEE_FADE;
            } else if ((float) H.cutawayAlpha.invokeExact(tree) < 1.0f) {
                kind |= SEE_CUTAWAY;
            }
        }
        return kind;
    }

    // Box slack in offscreen px: anchor vs sprite centre, sway pad, texture
    // offsets.
    private static final float BOX_SLACK_X = 256.0f;
    private static final float BOX_SLACK_Y = 192.0f;

    // Game thread. Screen rects (x1, y1, x2, y2, offscreen px) where the list
    // draws see-through: a stencil tree's box clipped to the hole rects, a
    // transparent or fading tree's whole box. -1 when out is full.
    private static final float[] box = new float[4];

    // Game thread. Conservative screen box of a tree, offscreen px.
    static void treeBox(Object tree, float[] out) throws Throwable {
        Texture texture = (Texture) H.texture.invokeExact(tree);
        Texture texture2 = (Texture) H.texture2.invokeExact(tree);
        float w = 0.0f;
        float h = 0.0f;
        if (texture != null) {
            w = Math.max(w, texture.getWidthOrig());
            h = Math.max(h, texture.getHeightOrig());
        }
        if (texture2 != null) {
            w = Math.max(w, texture2.getWidthOrig());
            h = Math.max(h, texture2.getHeightOrig());
        }
        float tx = (float) H.x.invokeExact(tree);
        float ty = (float) H.y.invokeExact(tree);
        float tz = (float) H.z.invokeExact(tree);
        float ax = IsoUtils.XToScreen(tx, ty, tz, 0) - IsoCamera.frameState.offX;
        float ay = IsoUtils.YToScreen(tx, ty, tz, 0) - IsoCamera.frameState.offY;
        out[0] = ax - 0.5f * w - BOX_SLACK_X;
        out[1] = ay - h - BOX_SLACK_Y;
        out[2] = ax + 0.5f * w + BOX_SLACK_X;
        out[3] = ay + BOX_SLACK_Y;
    }

    static int seeThroughRects(FBORenderTrees self, float[] hole, int holeN, float[] out) throws Throwable {
        ArrayList<?> trees = (ArrayList<?>) H.trees.invokeExact(self);
        int n = 0;
        for (int i = 0; i < trees.size(); ++i) {
            Object tree = trees.get(i);
            boolean transparent = (boolean) H.transparent.invokeExact(tree);
            boolean stencil = (boolean) H.useStencil.invokeExact(tree);
            boolean fading = (float) H.fadeAlpha.invokeExact(tree) < 1.0f
                    || (float) H.cutawayAlpha.invokeExact(tree) < 1.0f;
            if (!transparent && !stencil && !fading) continue;
            treeBox(tree, box);
            float bx1 = box[0];
            float by1 = box[1];
            float bx2 = box[2];
            float by2 = box[3];
            if (transparent || !stencil) {
                if (n * 4 + 3 >= out.length) return -1;
                out[n * 4] = bx1;
                out[n * 4 + 1] = by1;
                out[n * 4 + 2] = bx2;
                out[n * 4 + 3] = by2;
                ++n;
                continue;
            }
            for (int k = 0; k < holeN; ++k) {
                float x1 = Math.max(bx1, hole[k * 4]);
                float y1 = Math.max(by1, hole[k * 4 + 1]);
                float x2 = Math.min(bx2, hole[k * 4 + 2]);
                float y2 = Math.min(by2, hole[k * 4 + 3]);
                if (x1 >= x2 || y1 >= y2) continue;
                if (n * 4 + 3 >= out.length) return -1;
                out[n * 4] = x1;
                out[n * 4 + 1] = y1;
                out[n * 4 + 2] = x2;
                out[n * 4 + 3] = y2;
                ++n;
            }
        }
        return n;
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
        programId = program.getShaderID();
        program.addCompileListener(ON_COMPILE);
        lookupUniforms();
        LeafMaskAtlas.ensure();
        if (!glInfoLogged) {
            glInfoLogged = true;
            glInfo = "GL: " + GL11.glGetString(GL11.GL_RENDERER)
                    + ", " + GL11.glGetString(GL11.GL_VERSION)
                    + ", max varying floats " + GL11.glGetInteger(GL20.GL_MAX_VARYING_FLOATS)
                    + ", max vertex attribs " + GL11.glGetInteger(GL20.GL_MAX_VERTEX_ATTRIBS)
                    + ", max texture units " + GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS)
                    + ", timer queries " + (GpuTimer.supported() ? "yes" : "no");
            WindSwayMod.trace(glInfo);
        }
    }

    // Also after a recompile: the locations move and the sampler units are
    // dropped.
    private static void lookupUniforms() {
        ShaderProgram.Uniform u;
        u = program.getUniform("uParams", 35666);
        uParams = u == null ? -1 : u.loc;
        u = program.getUniform("uMode", 35666);
        uMode = u == null ? -1 : u.loc;
        u = program.getUniform("stepSize", 35664);
        uStepSize = u == null ? -1 : u.loc;
        u = program.getUniform("outlineColor", 35666);
        uOutlineColor = u == null ? -1 : u.loc;
        u = program.getUniform("uMask", 35666);
        uMask = u == null ? -1 : u.loc;
        u = program.getUniform("uLobe", 35666);
        uLobe = u == null ? -1 : u.loc;
        u = program.getUniform("uTwig", 35666);
        uTwig = u == null ? -1 : u.loc;
        u = program.getUniform("uCrown", 35666);
        uCrown = u == null ? -1 : u.loc;
        u = program.getUniform("uTrunk", 35666);
        uTrunk = u == null ? -1 : u.loc;
        u = program.getUniform("uFlick", 35666);
        uFlick = u == null ? -1 : u.loc;
        u = program.getUniform("uFlick2", 35666);
        uFlick2 = u == null ? -1 : u.loc;
        u = program.getUniform("uPage", 35664);
        uPage = u == null ? -1 : u.loc;
        u = program.getUniform("uLeaf", 35666);
        uLeaf = u == null ? -1 : u.loc;
        u = program.getUniform("uQual", 35666);
        uQual = u == null ? -1 : u.loc;
        u = program.getUniform("uQual2", 35666);
        uQual2 = u == null ? -1 : u.loc;
        u = program.getUniform("uWood", 35666);
        uWood = u == null ? -1 : u.loc;
        u = program.getUniform("uDebug", 35666);
        uDebug = u == null ? -1 : u.loc;
        Arrays.fill(uLast, Float.NaN);
        shader.Start();
        program.setSamplerUnit("DIFFUSE", 0);
        program.setSamplerUnit("MASK", 1);
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

    // Any thread; a sibling latch takes the batch renderer down with it.
    static void disable(String why) {
        if (ok) fail(why);
    }

    // Render thread, in place of FBORenderTrees.render. Returns true when
    // the list was drawn (or is empty) and vanilla must skip.
    public static boolean render(FBORenderTrees self) {
        if (!WindSwayMod.enabled || !warp || !ok) return false;
        try {
            if (FBORenderChunkManager.instance.renderThreadCurrent != null) return false;
        } catch (Throwable t) {
            fail(t);
            return false;
        }
        if (clearRequested) {
            clearRequested = false;
            TreeProfile.clearCache();
            LeafMaskAtlas.clearCache();
        }
        if (rearmRequested) {
            rearmRequested = false;
            shader = null;
            program = null;
            glProbe.reset();
        }
        ArrayList<?> trees;
        int vertCount;
        boolean diag = WindSwayMod.debugLog;
        long t0 = diag ? System.nanoTime() : 0L;
        try {
            if (shader == null) init();
            trees = (ArrayList<?>) H.trees.invokeExact(self);
            if (trees.isEmpty()) {
                // Vanilla's render() restores the tracked state even for an
                // empty list; the corpse atlas job leaves raw depth state
                // behind that only this heals.
                GLStateRenderThread.restore();
                return true;
            }
            vertCount = build(self, trees);
        } catch (Throwable t) {
            fail(t);
            return false;
        }
        long t1 = diag ? System.nanoTime() : 0L;
        try {
            draw(vertCount, diag);
        } catch (Throwable t) {
            fail(t);
        }
        if (diag) {
            long t2 = System.nanoTime();
            cpuBuildNs.addAndGet(t1 - t0);
            cpuDrawNs.addAndGet(t2 - t1);
        }
        return true;
    }

    private static Run newRun(TextureID tex, int first, int group, float pageW, float pageH) {
        Run r = runPool.isEmpty() ? new Run() : runPool.remove(runPool.size() - 1);
        r.tex = tex;
        r.first = first;
        r.count = 0;
        r.group = group;
        r.pageW = pageW;
        r.pageH = pageH;
        runs.add(r);
        return r;
    }

    // Java-only phase: camera, per-tree sway, quads into the stage buffer.
    private static int build(FBORenderTrees self, ArrayList<?> trees) throws Throwable {
        SpriteRenderState renderState = SpriteRenderer.instance.getRenderingState();
        PlayerCamera camera = renderState.playerCamera[renderState.playerIndex];
        float playerX = (float) H.playerX.invokeExact(self);
        float playerY = (float) H.playerY.invokeExact(self);
        float playerZ = (float) H.playerZ.invokeExact(self);
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
        TreeSway.prepareList();
        double n = TreeSway.listWind();
        double st = TreeSway.storm * n * n;
        double giantRamp = smooth(giantOnset, giantFull, n) * (1.0 - giantFade * smooth(giantFull, 1.0, n));
        double trunkShare = trunkLeanNow(n);
        double leafGate = smooth(0.0, 0.04, n);
        double branchGate = smooth(branchOnset, branchFull, n);
        double coniferYStorm = smooth(branchFull, 1.0, n);
        double leafBase = leafGustBase + (1.0 - leafGustBase) * Math.min(1.0, leafWindFull * n);

        runs.clear();
        int maxBytes = trees.size() * 3 * 4 * STRIDE;
        if (stage.capacity() < maxBytes) {
            int cap = stage.capacity();
            while (cap < maxBytes) cap *= 2;
            stage = BufferUtils.createByteBuffer(cap);
            stageF = stage.asFloatBuffer();
        }
        stageF.clear();
        int vertCount = 0;
        Run cur = null;
        double area = 0.0;

        for (int i = 0; i < trees.size(); ++i) {
            Object tree = trees.get(i);
            Texture texture = (Texture) H.texture.invokeExact(tree);
            Texture texture2 = (Texture) H.texture2.invokeExact(tree);
            if (texture == null && texture2 == null) continue;
            float tx = (float) H.x.invokeExact(tree);
            float ty = (float) H.y.invokeExact(tree);
            float tz = (float) H.z.invokeExact(tree);
            float r = (float) H.r.invokeExact(tree);
            float g = (float) H.g.invokeExact(tree);
            float b = (float) H.b.invokeExact(tree);
            float a = (float) H.a.invokeExact(tree);
            boolean useStencil = (boolean) H.useStencil.invokeExact(tree);
            boolean transparent = (boolean) H.transparent.invokeExact(tree);
            float fadeAlpha = (float) H.fadeAlpha.invokeExact(tree);
            float cutawayAlpha = (float) H.cutawayAlpha.invokeExact(tree);
            int depthOffset = (int) H.depthOffset.invokeExact(tree);
            float lean = 0.0f;
            if ((boolean) H.ore.invokeExact(tree)) {
                lean = 0.5f * ((float) H.oreX1.invokeExact(tree) + (float) H.oreX2.invokeExact(tree));
            }
            boolean video = WindSwayMod.videoMode;
            int group = video ? GROUP_OPAQUE
                    : useStencil ? (transparent ? GROUP_STENCIL_T : GROUP_STENCIL)
                    : transparent ? GROUP_TRANSPARENT : GROUP_OPAQUE;
            if (transparent && !video) {
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
            // A broadleaf base without its overlay is the winter tree (unknown
            // sprites stay leafy).
            boolean bare = !refProfile.conifer && !refProfile.rigid && !refProfile.leafy && texture2 == null;
            // The shader bends as a rod where the exponent is set; 0 = broadleaf bow.
            float powTree = 0.0f;
            if (refProfile.conifer) {
                hc = Math.max(hc, (float) (coniferStart * (1.0 - trunkStorm * st)));
                powTree = (float) (coniferBendPow - bendPowStorm * st);
            } else if (bare) {
                powTree = (float) (bareBendPow - bendPowStorm * st);
            }
            if (hKnee <= hc + 0.02f) hKnee = hc + 0.02f;
            float topFactor = refProfile.conifer || bare ? 1.0f : (float) profileTop(hc, hKnee, crownTail, trunkShare);
            Texture refTex = texture != null ? texture : texture2;
            int cls = refProfile.treeClass;
            // Foliage share from the overlay's sheet index (leaf fall, sparse
            // spring); bare crowns and rigid pieces carry none, evergreens all.
            double foliage = bare || refProfile.rigid ? 0.0
                    : (p2 != null ? p2.density : (refProfile.leafy ? 1.0 : 0.0));
            if (refProfile.conifer) foliage = 1.0;
            diagClass[cls]++;
            int seasonNow = p2 != null ? p2.season : (bare ? TreeProfile.SEASON_BARE : refProfile.season);
            if (refProfile.conifer && seasonNow == TreeProfile.SEASON_BARE) seasonNow = TreeProfile.SEASON_SUMMER;
            diagSeason[seasonNow]++;
            float hUnits = contentH / refTex.getHeightOrig() * unitsYOf(refTex.getWidthOrig());
            double periodFactor = Math.pow(hUnits / TreeSway.periodRefH, TreeClass.periodExp[cls]) * TreeClass.period[cls]
                    * (barePeriod + (1.0 - barePeriod) * foliage);
            double ringFactor = TreeClass.ring[cls] * (bareRing + (1.0 - bareRing) * foliage);
            double sway = TreeSway.lean(tx, ty, periodFactor, ringFactor, hs);
            double gLocal = TreeSway.localWind;
            double lobeEnergy = lobeRate > 0.0
                    ? 1.0 - Math.exp(-(TreeSway.localEnergyRaw / (lobeRate * lobeRate) + lobeWind * n * gLocal))
                    : 0.0;
            if (!mainOn) sway = 0.0;
            if (refProfile.conifer) sway *= coniferLean;
            else sway *= bareLean + (1.0 - bareLean) * foliage;
            sway *= TreeClass.lean[cls];
            double lobeXShare = refProfile.conifer ? coniferLobeX : 1.0;
            double lobeYShare = (refProfile.conifer
                    ? coniferLobeY * (1.0 - coniferLobeYStorm * coniferYStorm) : branchYFrac) * TreeClass.lobeY[cls];

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
                int unitsY = unitsYOf(wOrig);
                int unitsX = unitsY - 2;
                float sizeF = wOrig / 128.0f;
                float giant = (float) (1.0 + giantBoost * (sizeF - 1.0f) / 6.0f * giantRamp);
                // Main sway per tree; the ORE only carries per-object effects
                // (axe shudder), already jumbo-scaled. Both are ORE units: 128
                // texels on a regular sprite, times the jumbo width factor.
                float leanPx = (float) (sway * sizeF + lean) * 128.0f
                        * (float) Math.pow(contentH / hOrig, heightPow) * giant * jitter;
                float leafPx = profile.leafy ? (float) ((leafAmp + (leafAmpStorm - leafAmp) * n) * leafGate
                        * (leafBase + (1.0 - leafBase) * gLocal)
                        * Math.min(1.0, Math.pow(contentH / leafRefH, leafSizePow))) : 0.0f;
                float branchPx = (float) Math.min(branchMax * sizeF,
                        (branchFloor + (branchStorm - branchFloor) * n) * sizeF * branchGate
                        * (branchGustBase + (1.0 - branchGustBase) * lobeEnergy)
                        + branchFrac * Math.abs(leanPx));
                float leafRate = (float) (1.0 - leafRateSpread + 2.0 * leafRateSpread * h5);
                if (refProfile.conifer) {
                    leafPx *= (float) (coniferLeafAmp + (coniferLeafAmpStorm - coniferLeafAmp) * n);
                    branchPx *= (float) coniferLobeAmp;
                    float tierMin = (float) (coniferLobeMinPx * Math.min(1.0, contentH / coniferLobeMinRefH)
                            * branchGate * (branchGustBase + (1.0 - branchGustBase) * lobeEnergy));
                    if (branchPx < tierMin) branchPx = tierMin;
                }
                int sp = refProfile.species;
                float sparse = part == 1 && (profile.season == TreeProfile.SEASON_SPRING
                        || profile.season == TreeProfile.SEASON_AUTUMN)
                        ? 1.0f - (float) smooth(sparseFull, sparseDense, profile.density) : 0.0f;
                leafPx *= (float) (TreeClass.leafAmp[cls] * TreeSpecies.leafAmp(sp));
                leafRate *= (float) (TreeClass.leafRate[cls] * TreeSpecies.leafRate(sp));
                if (sparse > 0.0f) {
                    leafPx *= 1.0f + sparse * (float) (sparseLeafAmp - 1.0);
                    leafRate *= 1.0f + sparse * (float) (sparseLeafRate - 1.0);
                }
                if (leafRate > 1.95f) leafRate = 1.95f;
                // Rates are multiples of 1/64 and the clocks travel as (integer
                // cycles mod 64, fraction): exact phase in the shader, seamless
                // at the 65536 wrap (65536 / 64 cycles = a multiple of 64).
                leafRate = Math.round(leafRate * 64.0f) / 64.0f;
                double leafClockTree = (refProfile.conifer ? TreeSway.coniferLeafClock : TreeSway.leafClock) * leafRate;
                double leafCycles = Math.floor(leafClockTree);
                float qLeaf = (float) (leafClockTree - leafCycles);
                int nmLeaf = (int) (leafCycles % 64.0);
                if (part == 1 && profile.bloom) leafPx *= (float) bloomLeaf;
                float branchCell = (float) Math.max(branchCellMin, branchCellFrac * w);
                // Leaf cell from the blade length (64 px per world unit), grown
                // with the sprite like the branch cell; packed in half pixels
                // above rate (0.5..1.5) + 2 * floor(g * 63).
                double cellGrow = Math.pow(branchCell / lobeRefCell, leafCellExp);
                double cellPx = TreeSpecies.leafCm(sp) * PX_PER_CM * cellGrow;
                if (cellPx < leafCellMin) cellPx = leafCellMin;
                if (cellPx > 31.0) cellPx = 31.0;
                // Cluster cell over the painted leaf size: dabs move as twig
                // ends, leaf shapes as themselves; evergreens keep the needle
                // cell. Flicker only on leaves.
                float snap = 0.0f;
                double clusterCell = cellPx;
                double paint = refProfile.conifer ? 0.0 : TreeSpecies.paintPx(sp) * paintFamily[profile.family];
                if (paint > 0.0) {
                    double t = smooth(clusterPaintLo, clusterPaintHi, paint);
                    snap = (float) (clusterSnap * (1.0 - t));
                    clusterCell = Math.max(cellPx, clusterPx * cellGrow * (1.0 - t));
                }
                if (clusterAmpPow != 0.0) leafPx *= (float) Math.pow(clusterCell / cellPx, clusterAmpPow);
                float flickPx = 0.0f;
                float flickCell = 0.0f;
                if (profile.leafy && !refProfile.conifer) {
                    flickPx = (float) ((flickAmp + (flickAmpStorm - flickAmp) * n) * leafGate * TreeSpecies.flick(sp));
                    if (part == 1 && profile.bloom) flickPx *= (float) bloomLeaf;
                    if (sparse > 0.0f) flickPx *= 1.0f + sparse * (float) (sparseFlick - 1.0);
                    flickCell = (float) Math.max(flickCellMin, flickCellK * profile.r50);
                }
                branchPx *= (float) (TreeClass.lobe[cls] * TreeSpecies.lobe(sp) * (bareLobe + (1.0 - bareLobe) * foliage));
                // The patch lattice belongs to the foliage: the bare wood under
                // an overlay keeps a residue, lean and bend stay full.
                if (part == 0 && texture2 != null && !refProfile.conifer) branchPx *= (float) woodLobe;
                branchCell *= (float) (TreeClass.lobeCell[cls] * TreeSpecies.lobeCell(sp) * (bareCell + (1.0 - bareCell) * foliage));
                double lobeRateTree = Math.round(Math.pow(branchCell / lobeRefCell, -lobeRateExp) * 64.0) / 64.0;
                double branchClockTree = TreeSway.branchClock * lobeRateTree;
                double branchCycles = Math.floor(branchClockTree);
                float qBranch = (float) (branchClockTree - branchCycles);
                float packedNm = nmLeaf + 64.0f * (int) (branchCycles % 64.0);
                float packedCells = (float) (Math.min(63.0, Math.floor(cellPx * 2.0))
                        + 64.0 * Math.min(63.0, Math.floor(flickCell * 2.0))
                        + 4096.0 * Math.min(63.0, Math.floor(clusterCell * 2.0)));
                if (!branchOn) branchPx = 0.0f;
                if (!leafOn) {
                    leafPx = 0.0f;
                    flickPx = 0.0f;
                }
                if (profile.rigid) {
                    leanPx = 0.0f;
                    leafPx = 0.0f;
                    flickPx = 0.0f;
                    branchPx = 0.0f;
                }
                // Fields under lodMinPx screen px are dropped (sprite px / zoom).
                if (lodMinPx > 0.0) {
                    float lodPx = (float) (lodMinPx * camera.zoom);
                    if (branchPx * Math.max(lobeXShare, lobeYShare) < lodPx) branchPx = 0.0f;
                    if (leafPx < lodPx) leafPx = 0.0f;
                }
                // The lean widens one side only; lobes and leaves reach both ways.
                float reach = leafPx + (float) (branchPx * lobeXShare) + 2.0f;
                float leanTop = leanPx * topFactor;
                float padL = reach + Math.max(0.0f, -leanTop);
                float padR = reach + Math.max(0.0f, leanTop);
                // Vertical offsets and the tilt can lift the crown past the quad top.
                float padY = (float) (0.3 * leafPx + lobeYShare * branchPx
                        + 0.5 * crownTilt * Math.abs(leanPx) * topFactor) + 1.0f;

                float xStart = tex.getXStart();
                float xEnd = tex.getXEnd();
                float yStartTex = tex.getYStart();
                float yEndTex = tex.getYEnd();
                float texelU = (xEnd - xStart) / w;
                float texelV = (yEndTex - yStartTex) / h;
                float dx = unitsX / SQRT2 / 2.0f;
                float xL = (offX - padL) / wOrig * unitsX / SQRT2 - dx;
                float xR = (offX + w + padR) / wOrig * unitsX / SQRT2 - dx;
                float uL = xStart - padL * texelU;
                float uR = xEnd + padR * texelU;
                float pageW = tex.getWidthHW();
                float pageH = tex.getHeightHW();
                float wu0 = -1.0f;
                float wv0 = 0.0f;
                float wu1 = 0.0f;
                float wv1 = 0.0f;
                float[] wRect = LeafMaskAtlas.rectFor(tex);
                if (wRect != null) {
                    wu0 = wRect[0];
                    wv0 = wRect[1];
                    wu1 = wRect[2];
                    wv1 = wRect[3];
                }
                // Leaf band top..trunk stub; no leaves = empty band, the fragment skips
                // flutter, mask and shade.
                float vLeafTop = 2.0f;
                float vLeafBottom = -1.0f;
                if (profile.leafy) {
                    vLeafTop = yStartTex + (topRow - offY) * texelV;
                    vLeafBottom = yStartTex + (profile.stubTopRow - offY) * texelV;
                }
                float leanU = leanPx * texelU;
                float branchU = (float) (branchPx * lobeXShare) * texelU;
                float branchV = (float) (branchPx * lobeYShare) * texelV;
                float leafU = leafPx * texelU;
                float leafV = 0.3f * leafPx * texelV;
                // Lattice origin on the sprite, not the part: base and
                // overlay are trimmed differently and must share the phase.
                float pixL = offX - padL + seedX;
                float pixR = offX + w + padR + seedX;

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
                        cur = newRun(texId, vertCount, group, pageW, pageH);
                    }
                    float[] o = quad;
                    putVertex(o, 0, xL, yT, wX, wY, wZ, depthT, r, g, b, a, uL, vQuad0, hTop, branchCell,
                            pixL, pixT, packedNm, qBranch, xStart, xEnd, yStartTex, yEndTex,
                            leanU, hc, powTree, branchU, leafU, leafV, vLeafTop, vLeafBottom, fadeAlpha, hKnee, branchV, qLeaf,
                            packedCells, snap, (float) gLocal, flickPx, wu0, wv0, wu1, wv1);
                    System.arraycopy(o, 0, o, FLOATS, FLOATS);
                    System.arraycopy(o, 0, o, 2 * FLOATS, FLOATS);
                    System.arraycopy(o, 0, o, 3 * FLOATS, FLOATS);
                    putCorner(o, FLOATS, xR, yT, wX, wY, wZ, depthT, uR, vQuad0, hTop, pixR, pixT);
                    putCorner(o, 2 * FLOATS, xR, yB, wX, wY, wZ, depthB, uR, vSeg1, hBottom, pixR, pixB);
                    putCorner(o, 3 * FLOATS, xL, yB, wX, wY, wZ, depthB, uL, vSeg1, hBottom, pixL, pixB);
                    stageF.put(o);
                    cur.count += 4;
                    vertCount += 4;
                    area += (w + padL + padR) * (bottom - rowT);
                }
            }
        }
        listArea = area;
        diagRenders++;
        diagTrees += trees.size();
        if (trees.size() > diagMaxTrees) diagMaxTrees = trees.size();
        return vertCount;
    }

    // Billboard height in world units per family (FBORenderTrees).
    private static int unitsYOf(int wOrig) {
        if (wOrig == FBORenderChunk.JUMBO_L_WIDTH) return 8;
        if (wOrig == FBORenderChunk.JUMBO_XL_WIDTH) return 12;
        if (wOrig == FBORenderChunk.JUMBO_XXL_WIDTH) return 16;
        return 4;
    }

    private static void putVertex(float[] o, int i, float lx, float ly, float wX, float wY, float wZ, float depth,
                                  float r, float g, float b, float a, float u, float v, float hf, float cell,
                                  float px, float py, float packedNm, float qBranch,
                                  float rx, float ry, float rz, float rw,
                                  float leanU, float hc, float pow, float branchU,
                                  float leafU, float leafV, float bandTop, float bandBottom, float fade,
                                  float knee, float branchV, float qLeaf,
                                  float packedCells, float snap, float localWind, float flickPx,
                                  float mu0, float mv0, float mu1, float mv1) {
        float y = ly * ISO_Y;
        o[i] = b00 * lx + b10 * y + b30 + wX;
        o[i + 1] = b01 * lx + b11 * y + b31 + wY;
        o[i + 2] = b02 * lx + b12 * y + b32 + wZ;
        o[i + 3] = depth;
        o[i + 4] = r;
        o[i + 5] = g;
        o[i + 6] = b;
        o[i + 7] = a;
        o[i + 8] = u;
        o[i + 9] = v;
        o[i + 10] = hf;
        o[i + 11] = cell;
        o[i + 12] = px;
        o[i + 13] = py;
        o[i + 14] = packedNm;
        o[i + 15] = qBranch;
        o[i + 16] = rx;
        o[i + 17] = ry;
        o[i + 18] = rz;
        o[i + 19] = rw;
        o[i + 20] = leanU;
        o[i + 21] = hc;
        o[i + 22] = pow;
        o[i + 23] = branchU;
        o[i + 24] = leafU;
        o[i + 25] = leafV;
        o[i + 26] = bandTop;
        o[i + 27] = bandBottom;
        o[i + 28] = fade;
        o[i + 29] = knee;
        o[i + 30] = branchV;
        o[i + 31] = qLeaf;
        o[i + 32] = packedCells;
        o[i + 33] = snap;
        o[i + 34] = localWind;
        o[i + 35] = flickPx;
        o[i + 36] = mu0;
        o[i + 37] = mv0;
        o[i + 38] = mu1;
        o[i + 39] = mv1;
    }

    // The other three corners are a copy of vertex 0 with position, depth,
    // uv, height fraction and field coords overwritten.
    private static void putCorner(float[] o, int i, float lx, float ly, float wX, float wY, float wZ,
                                  float depth, float u, float v, float hf, float px, float py) {
        float y = ly * ISO_Y;
        o[i] = b00 * lx + b10 * y + b30 + wX;
        o[i + 1] = b01 * lx + b11 * y + b31 + wY;
        o[i + 2] = b02 * lx + b12 * y + b32 + wZ;
        o[i + 3] = depth;
        o[i + 8] = u;
        o[i + 9] = v;
        o[i + 10] = hf;
        o[i + 12] = px;
        o[i + 13] = py;
    }

    // GL phase. Mirrors renderTree's raw state calls; the tracked state is
    // restored at the end exactly like vanilla's render().
    private static void draw(int vertCount, boolean diag) {
        if (vertCount == 0) {
            releaseRuns();
            return;
        }
        int bytes = vertCount * STRIDE;
        stage.position(0);
        stage.limit(bytes);

        boolean withVao = useVao && vaoOk();
        boolean timed = false;
        boolean pushed = false;
        GlTrace.begin("tree list");
        try {
            Matrix4f p = Core.getInstance().projectionMatrixStack.alloc();
            p.set(proj);
            Matrix4f m = Core.getInstance().modelViewMatrixStack.alloc();
            m.set(mvCommon);
            Core.getInstance().projectionMatrixStack.push(p);
            Core.getInstance().modelViewMatrixStack.push(m);
            pushed = true;
            // The one-shot calibration only with the log on: its timer
            // queries poll the driver until the window closes.
            double calArea = diag ? TreeDetail.timingArea(gpuTimer, listArea) : 0.0;
            timed = (diag || calArea > 0.0) && gpuTimer.begin(calArea);
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
            if (withVao) {
                if (vao == 0) {
                    // ARRAY_BUFFER is still the stream VBO from the upload.
                    vao = GL30.glGenVertexArrays();
                    GL30.glBindVertexArray(vao);
                    for (int i = 0; i < NUM_ATTRIBS; ++i) {
                        GL20.glVertexAttribPointer(i, 4, GL11.GL_FLOAT, false, STRIDE, (long) i * 16L);
                        GL20.glEnableVertexAttribArray(i);
                    }
                } else {
                    GL30.glBindVertexArray(vao);
                }
            } else {
                for (int i = 0; i < NUM_ATTRIBS; ++i) {
                    GL20.glVertexAttribPointer(i, 4, GL11.GL_FLOAT, false, STRIDE, (long) i * 16L);
                    GL20.glEnableVertexAttribArray(i);
                }
            }

            int pid = program.getShaderID();
            if (pid == 0) {
                fail("tree shader program gone");
                return;
            }
            if (pid != programId) {
                programId = pid;
                lookupUniforms();
            }
            shader.Start();
            VertexBufferObject.setModelViewProjection(shader);
            float[] u = uNow;
            u[0] = (float) TreeSway.branchClock;
            u[1] = (float) TreeSway.leafClock;
            u[2] = (float) leafCell;
            u[3] = (float) sharp;
            sendU4(uParams, 0);
            u[4] = STEP_SIZE;
            u[5] = STEP_SIZE;
            sendU2(uStepSize, 4);
            u[8] = 0.1f;
            u[9] = 0.1f;
            u[10] = 0.1f;
            u[11] = 0.8f;
            sendU4(uOutlineColor, 8);
            double wNow = Math.min(1.0, leafWindDens * TreeSway.listWind());
            u[12] = (float) TreeSway.maskClock;
            u[13] = (float) leafMaskStrength;
            u[14] = (float) (1.0 / Math.max(leafMaskCell, 1.0));
            u[15] = (float) (leafMaskFloor + (1.0 - leafMaskFloor) * wNow);
            sendU4(uMask, 12);
            u[16] = (float) lobeRefCell;
            u[17] = (float) lobeRateExp;
            u[18] = (float) lobeFreqSpread;
            u[19] = (float) (1.0 / Math.max(coniferTierAspect, 1.0));
            sendU4(uLobe, 16);
            u[20] = (float) twigScale;
            u[21] = (float) twigRate;
            u[22] = (float) twigAmp;
            u[23] = (float) twigRateY;
            sendU4(uTwig, 20);
            u[24] = (float) crownTail;
            u[25] = (float) crownShorten;
            u[26] = (float) crownTilt;
            u[27] = (float) coniferLobeRamp;
            sendU4(uCrown, 24);
            u[28] = (float) trunkLeanNow(TreeSway.listWind());
            u[29] = (float) trunkFoot;
            u[30] = 0.0f;
            u[31] = 0.0f;
            sendU4(uTrunk, 28);
            double densLow = flickDensCalm + (flickDensStorm - flickDensCalm) * smooth(flickWindOnset, 1.0, TreeSway.listWind());
            u[32] = (float) flickRate;
            u[33] = (float) densLow;
            u[34] = (float) Math.max(densLow, flickDensGust);
            u[35] = (float) flickDuty;
            sendU4(uFlick, 32);
            u[36] = (float) flickGustOnset;
            u[37] = (float) flickGustFull;
            u[38] = (float) flickOutside;
            u[39] = (float) clusterRatePow;
            sendU4(uFlick2, 36);
            u[40] = (float) leafShade;
            u[41] = (float) leafCellExp;
            u[42] = (float) leafGustDens;
            u[43] = (float) leafShadeRate;
            sendU4(uLeaf, 40);
            u[44] = qualLobes ? 1.0f : 0.0f;
            u[45] = qualOctave2 ? 1.0f : 0.0f;
            u[46] = qualLeaves ? 1.0f : 0.0f;
            u[47] = qualMask ? 1.0f : 0.0f;
            sendU4(uQual, 44);
            u[48] = qualShade ? 1.0f : 0.0f;
            u[49] = 0.0f;
            u[50] = 0.0f;
            u[51] = 0.0f;
            sendU4(uQual2, 48);
            boolean maskOn = woodMask > 0.0 && LeafMaskAtlas.active();
            u[52] = LeafMaskAtlas.scaleU;
            u[53] = LeafMaskAtlas.scaleV;
            u[54] = maskOn ? (float) woodMask : 0.0f;
            u[55] = 0.0f;
            sendU4(uWood, 52);
            if (maskOn) {
                // The bind is not cached: the engine rebinds unit 1 between
                // draws.
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, LeafMaskAtlas.textureId);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            }
            u[56] = (float) debugMode;
            u[57] = 0.0f;
            u[58] = 0.0f;
            u[59] = 0.0f;
            sendU4(uDebug, 56);
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
            if (!withVao) {
                // VBORenderer's resting state: attributes 0-4 enabled, the
                // rest off; the VAO path never touches the default object.
                for (int k = 0; k < NUM_ATTRIBS; ++k) {
                    if (k < 5) {
                        GL20.glEnableVertexAttribArray(k);
                    } else {
                        GL20.glDisableVertexAttribArray(k);
                    }
                }
            }
            if (timed) {
                gpuTimer.end();
                timed = false;
                TreeDetail.update(gpuTimer);
            }
            if (glCheck) {
                int err = glProbe.end();
                if (err != GL11.GL_NO_ERROR) {
                    fail("GL error 0x" + Integer.toHexString(err) + " after the list draw");
                }
            }
        } finally {
            if (timed) gpuTimer.end();
            // In the failure path too: a VAO left bound would swallow the
            // engine's attrib pointers for the rest of the session.
            if (withVao) {
                try {
                    GL30.glBindVertexArray(0);
                } catch (Throwable ignored) {
                }
            }
            if (pushed) {
                Core.getInstance().modelViewMatrixStack.pop();
                Core.getInstance().projectionMatrixStack.pop();
            }
            releaseRuns();
            try {
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
            } catch (Throwable ignored) {
            }
            GLStateRenderThread.restore();
            // The flags are not consumed between two generic drawers; without
            // the cache invalidation the shadow drawer samples our last page.
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            ++totalLists;
            GlTrace.end("tree list");
        }
    }

    private static void setMode(float outline, float capAlpha) {
        uNow[60] = outline;
        uNow[61] = capAlpha;
        uNow[62] = (float) coniferTierTrunk;
        uNow[63] = (float) coniferTierPow;
        sendU4(uMode, 60);
    }

    private static TextureID drawRuns(int from, int to, int baseVert, TextureID bound) {
        for (int i = from; i < to; ++i) {
            Run run = runs.get(i);
            if (run.tex != bound) {
                // A page the engine has not uploaded yet would draw as the
                // error texture (one frame of checkerboard on every tree of
                // that page): skip its runs this frame instead.
                if (!bindNearest(run.tex)) {
                    bound = null;
                    diagPageMiss++;
                    ++totalPageMiss;
                    if (pageMissLogged < 40) {
                        ++pageMissLogged;
                        String path = run.tex.getPath() != null ? String.valueOf(run.tex.getPath().getPath()) : "?";
                        WindSwayMod.trace("tree page bound before upload, runs skipped this frame: " + path
                                + " frame=" + IsoCamera.frameState.frameCount + " wind=" + String.format("%.2f", TreeSway.listWind()));
                    }
                    continue;
                }
                diagBinds++;
                bound = run.tex;
                uNow[64] = 1.0f / run.pageW;
                uNow[65] = 1.0f / run.pageH;
                sendU2(uPage, 64);
            }
            GL11.glDrawArrays(GL11.GL_QUADS, baseVert + run.first, run.count);
            diagDraws++;
        }
        return bound;
    }

    static int diagBinds;
    static int diagPageMiss;
    static int pageMissLogged;

    // Raw bind, NEAREST once per GL texture: TextureID.bind() rewrites four
    // sampler parameters on a page the previous draw still reads, which
    // serialises the draws. The bind cache is invalidated after the pass.
    // False when the page has no data yet (nothing bound). The filter is set
    // on every bind: an engine bind of a shared page re-applies its own
    // flags (trilinear), and past one level of minification the -1 bias no
    // longer pins the base level, so a cached NEAREST is not a NEAREST.
    static boolean bindNearest(TextureID tex) {
        int id = tex.getID();
        if (id == -1) {
            tex.bind();
            id = tex.getID();
            if (id == -1) return false;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        nearestAlways();
        return true;
    }

    // NEAREST on the texture bound to the active unit, every time: the
    // engine's TextureID.assignFilteringFlags re-applies the page's own
    // filter on each of its binds, NEAREST inside a chunk bake and trilinear
    // outside, so the state a page arrives in flips with the frame.
    static void nearestAlways() {
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
    }

    // GL id of the page, created through the engine's bind() (lands on the
    // active unit). A page without data binds the engine's error texture
    // and keeps id -1.
    static int ensureId(TextureID tex) {
        int id = tex.getID();
        if (id == -1) {
            tex.bind();
            id = tex.getID();
            if (id == -1) id = Texture.getErrorTexture().getID();
        }
        return id;
    }

    private static void releaseRuns() {
        for (int i = 0; i < runs.size(); ++i) {
            runPool.add(runs.get(i));
        }
        runs.clear();
    }
}
