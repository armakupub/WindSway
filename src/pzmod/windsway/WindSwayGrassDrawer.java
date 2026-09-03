package pzmod.windsway;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;

import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IShaderProgramListener;
import zombie.core.opengl.ShaderProgram;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureID;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;

// Grass batch on vanilla's tileWithDepth contract: screen-space quads,
// authored depth maps, depth TEST only, no alpha discard. Ordering against
// no-depth-write translucents is capture-side (mid-pass flushes). Wind
// flora bends in the shader; the quad is widened by the reach.
public class WindSwayGrassDrawer extends TextureDraw.GenericDrawer {

    private static Shader shader;
    private static volatile boolean samplersSet = false;
    private static volatile boolean firstBatchLogged = false;

    // Capture skips vanilla's draw on the game thread, the batch draws on
    // the render thread; a drawer that cannot draw would leave every
    // captured object invisible but clickable. Capture is gated on this
    // state: UNKNOWN until a probe drawer has compiled the shader, FAILED
    // on any failure until the next world load (everything vanilla, like
    // the tree renderer's latch).
    private static final int UNKNOWN = 0;
    private static final int READY = 1;
    private static final int FAILED = 2;
    private static volatile int state = UNKNOWN;
    private static boolean probeQueued = false;

    // Debug: the next batch throws, exercising the fallback.
    static volatile boolean debugFail = false;

    // Game thread, mid-pass. One probe per pass until the state is known.
    static boolean ready() {
        int s = state;
        if (s == READY) return true;
        if (s == UNKNOWN && !probeQueued) probe();
        return false;
    }

    // Game thread. Also the warm-up's probe, so the first pass of a world
    // does not queue a second one.
    static void probe() {
        probeQueued = true;
        SpriteRenderer.instance.drawGeneric(new WindSwayGrassDrawer());
    }

    static void onPassDone() {
        probeQueued = false;
    }

    // Game thread. After a latch: probe again, once per new world or on
    // request from the console. The shader and its caches are the render
    // thread's, it resets them at its next segment.
    static void rearm() {
        if (state != FAILED) return;
        probeQueued = false;
        rearmRequested = true;
        state = UNKNOWN;
        WindSwayMod.trace("grass batch re-armed, probing again");
    }

    private static volatile boolean rearmRequested;

    private static void applyRearm() {
        if (!rearmRequested) return;
        rearmRequested = false;
        shader = null;
        programId = 0;
        samplersSet = false;
        uniformsLooked = false;
        uLoaded = false;
        glProbe.reset();
    }

    private static int programId;

    private static final WindSwayMod.GlProbe glProbe = new WindSwayMod.GlProbe();
    static final GpuTimer gpuTimer = new GpuTimer();
    static final AtomicLong cpuNs = new AtomicLong();
    static final AtomicLong cpuFillNs = new AtomicLong();
    static final AtomicLong cpuStateNs = new AtomicLong();
    static final AtomicLong cpuUploadNs = new AtomicLong();
    static final AtomicLong cpuAttribNs = new AtomicLong();
    static final AtomicLong cpuProgNs = new AtomicLong();
    static final AtomicLong cpuDrawNs = new AtomicLong();
    static final AtomicLong cpuEndNs = new AtomicLong();
    static final AtomicLong cpuTimerNs = new AtomicLong();

    static void fail(String why) {
        if (state == FAILED) return;
        state = FAILED;
        WindSwayMod.trace("grass batch disabled, plants follow vanilla: " + why);
    }

    private static void fail(Throwable t) {
        if (state == FAILED) return;
        state = FAILED;
        WindSwayMod.trace("grass batch disabled, plants follow vanilla: " + t, t);
    }

    // Streaming VBO, orphaned only on wrap. Not VBORenderer: its flush()
    // re-specs VBO+IBO via glBufferData every call, built for once per
    // pass, fatal at ~140 interleave breaks per frame. Attrib layout
    // follows the VBORenderer convention (location == element index):
    // pos3f, color4f, uv0, uv1, depth1f, wind4f, rect4f, texel4f, frame4f,
    // leaf4f, class4f (bend exponent, blade spread, tip factor, sheen factor),
    // class3 4f (damping factor, flutter factor, cross position, steady share),
    // body4f (stem share, swing, inertia, lobe px), look4f (flicker, mask
    // share): 14 attributes (layout contract with windsway_grass_static.vert).
    private static final int STRIDE = 192;
    private static final int FLOATS = STRIDE / 4;
    private static int streamCapacity = 4 * 1024 * 1024;
    private static int streamVbo = 0;
    private static int streamOffset = 0;
    private static ByteBuffer stage = BufferUtils.createByteBuffer(256 * 1024);
    private static FloatBuffer stageF = stage.asFloatBuffer();
    // One quad array, copied in one block: per-float puts were most of the
    // batch's CPU time.
    private static final float[] quad = new float[4 * FLOATS];

    private static volatile boolean uniformsLooked = false;

    // Uniforms are re-sent only on change: nothing else uses this program.
    static volatile boolean useVao = true;
    private static int vao = 0;
    private static int vaoSupport = -1;
    private static final float[] lastU = new float[TreeSway.PLANT_UNIFORMS];
    private static boolean uLoaded = false;

    private static boolean vaoOk() {
        if (vaoSupport < 0) {
            try {
                GLCapabilities caps = Display.capabilities;
                vaoSupport = caps.OpenGL30 || caps.GL_ARB_vertex_array_object ? 1 : 0;
            } catch (Throwable t) {
                vaoSupport = 0;
            }
            if (vaoSupport == 0) WindSwayMod.trace("grass batch: no vertex array objects, pointers per batch");
        }
        return vaoSupport == 1;
    }

    private static void setAttribPointers() {
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
        GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, STRIDE, 12L);
        GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 28L);
        GL20.glVertexAttribPointer(3, 2, GL11.GL_FLOAT, false, STRIDE, 36L);
        GL20.glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, STRIDE, 44L);
        GL20.glVertexAttribPointer(5, 4, GL11.GL_FLOAT, false, STRIDE, 48L);
        GL20.glVertexAttribPointer(6, 4, GL11.GL_FLOAT, false, STRIDE, 64L);
        GL20.glVertexAttribPointer(7, 4, GL11.GL_FLOAT, false, STRIDE, 80L);
        GL20.glVertexAttribPointer(8, 4, GL11.GL_FLOAT, false, STRIDE, 96L);
        GL20.glVertexAttribPointer(9, 4, GL11.GL_FLOAT, false, STRIDE, 112L);
        GL20.glVertexAttribPointer(10, 4, GL11.GL_FLOAT, false, STRIDE, 128L);
        GL20.glVertexAttribPointer(11, 4, GL11.GL_FLOAT, false, STRIDE, 144L);
        GL20.glVertexAttribPointer(12, 4, GL11.GL_FLOAT, false, STRIDE, 160L);
        GL20.glVertexAttribPointer(13, 4, GL11.GL_FLOAT, false, STRIDE, 176L);
    }

    private static final int NUM_ATTRIBS = 14;
    private static int uWind = -1;
    private static int uClock = -1;
    private static int uTurb = -1;
    private static int uMix = -1;
    private static int uResp = -1;
    private static int uRing = -1;
    private static int uRing2 = -1;
    private static int uPlant = -1;
    private static int uPlant2 = -1;
    private static int uLeafP = -1;
    private static int uLeafQ = -1;
    private static int uTip = -1;
    private static int uSheen = -1;
    private static int uModel = -1;
    private static int uFlut = -1;
    private static int uHon = -1;
    private static int uHon2 = -1;
    private static int uPTurb = -1;
    private static int uLean = -1;
    private static int uCross = -1;
    private static int uBody = -1;
    private static int uLeafM = -1;
    private static int uFlick = -1;
    private static int uFlick2 = -1;
    private static int uLeafM2 = -1;
    private static int uCamA = -1;
    private static int uCamB = -1;
    private static final float[] plantU = new float[TreeSway.PLANT_UNIFORMS];

    // Up to eight diffuse and eight depth pages per run (units 0..7 and
    // depthBase..): consecutive quads rarely share a page pair, per-page
    // segments meant two binds per quad.
    static final int MAX_SLOTS = 8;
    static volatile int slotsWanted = MAX_SLOTS;
    private static int maxSlots = 0;
    private static int depthBase = MAX_SLOTS;
    private static final int[] unitBound = new int[2 * MAX_SLOTS];
    static final AtomicLong diagRuns = new AtomicLong();
    static final AtomicLong diagBinds = new AtomicLong();
    // Render thread, cumulative: quads dropped for a page without a GL
    // texture, binds that fell back to the engine's error texture, segments drawn.
    static int diagPageMiss;
    static int diagErrorTex;
    static int totalSegments;

    static String statusLine() {
        return "state=" + state + " program=" + programId + " segments=" + totalSegments
                + " pageMiss=" + diagPageMiss + " errorTex=" + diagErrorTex + " slots=" + maxSlots;
    }

    // A recompile keeps the Shader object and may reuse the GL name: the
    // locations and sampler units are looked up again either way.
    private static final IShaderProgramListener ON_COMPILE = p -> programId = 0;

    private static final class Run {
        final TextureID[] diffuse = new TextureID[MAX_SLOTS];
        final TextureID[] depth = new TextureID[MAX_SLOTS];
        int nDiffuse;
        int nDepth;
        int first;
        int count;
    }

    private static final ArrayList<Run> runs = new ArrayList<>(16);
    private static final ArrayList<Run> runPool = new ArrayList<>(16);

    // With the atlas on, depth slot 0 of every run is the atlas (null).
    private static boolean atlasOn;

    private static Run newRun(int first) {
        Run r = runPool.isEmpty() ? new Run() : runPool.remove(runPool.size() - 1);
        r.nDiffuse = 0;
        r.nDepth = atlasOn ? 1 : 0;
        r.depth[0] = null;
        r.first = first;
        r.count = 0;
        runs.add(r);
        return r;
    }

    private static void releaseRuns() {
        for (int i = 0; i < runs.size(); ++i) {
            Run r = runs.get(i);
            for (int k = 0; k < MAX_SLOTS; ++k) {
                r.diffuse[k] = null;
                r.depth[k] = null;
            }
            runPool.add(r);
        }
        runs.clear();
    }

    private static int slotOf(TextureID[] pages, int n, TextureID page) {
        for (int i = 0; i < n; ++i) {
            if (pages[i] == page) return i;
        }
        return -1;
    }

    public static final class GrassQuad {
        Texture tex;
        Texture depthTex;
        // Contrast carriers the merge barrier guards: walls, fences,
        // grass over ground snow.
        boolean wall;
        // Screen rect in the scene's zoomed pixel space.
        float ox;
        float oy;
        float w;
        float h;
        // ObjectRenderEffects corner fractions, TL/TR/BR/BL.
        float ox1;
        float oy1;
        float ox2;
        float oy2;
        float ox3;
        float oy3;
        float ox4;
        float oy4;
        // Diffuse UVs, flip applied at capture.
        float u0;
        float v0;
        float u1;
        float v1;
        // Depth-map UV rect (TileDepthModifier intersection).
        float du0;
        float dv0;
        float du1;
        float dv1;
        // tileWithDepth's zDepthBlendZ / zDepthBlendToZ, per vertex here.
        float zNear;
        float zFar;
        float r;
        float g;
        float b;
        float a;
        // Wall lighting: one colour per corner (WallShaper col[1..3]), r/g/b is corner 0.
        boolean lit;
        float r1;
        float g1;
        float b1;
        float r2;
        float g2;
        float b2;
        float r3;
        float g3;
        float b3;
        // Wind flora: lean-axis position (tiles), seed, lean amplitude (sprite px
        // per unit lean), period (s), reach per side, the object's frame in this
        // part's uv, barrier bits 2 (left) / 4 (right).
        float windS;
        // Position across the lean axis (tiles), for the crosswind octave.
        float windT;
        float windSeed;
        float windAmp;
        float windPeriod;
        float padL;
        float padR;
        float frameTop;
        float frameBottom;
        float frameLeft;
        float barrier;
        // Plant class of this part (-1 = not wind flora), leaf flutter
        // amplitude x/y (sprite px at the current wind), cell (px), rate
        // factor, top pad for the flutter, and the object's bend exponent
        // and blade spread factors.
        int cls = -1;
        // Bush genus of this part's sprite (-1 = none).
        int genus = -1;
        float leafX;
        float leafY;
        float leafCell;
        float leafRate;
        float padT;
        float bendPow = 1.0f;
        float bladeVar = 1.0f;
        // Class factors on the tip physics and the gust sheen; dry factors
        // on the damping and the flutter.
        float tipF;
        float sheenF;
        float dampF = 1.0f;
        float flutF = 1.0f;
        float steadyF = 1.0f;
        // Body of the object's class: stem-and-block profile share, swing
        // factor, lean inertia factor; lobe amplitude of this part (px).
        float block;
        float swingF = 1.0f;
        float inertiaF = 1.0f;
        float lobePx;
        // Leaf look of this part: flicker amplitude (brightness), mask share.
        float flickPx;
        float maskF;
        // World lane (world-space vertices): the square, its screen anchor
        // at capture (the exact-split base of the corner pixel offsets) and
        // the render-y depth shift.
        float sqX;
        float sqY;
        float sqZ;
        float anchorX;
        float anchorY;
        float depthShift;

        // Screen box of the padded, ORE-displaced quad, x1 y1 x2 y2 in
        // offscreen px.
        void bounds(float[] out) {
            out[0] = ox - padL + Math.min(ox1, ox4) * w;
            out[1] = oy - padT + Math.min(oy1, oy2) * h;
            out[2] = ox + w + padR + Math.max(ox2, ox3) * w;
            out[3] = oy + h + Math.max(oy3, oy4) * h;
        }

        // Back to the fresh-object state for every field buildPart and the
        // captures write only conditionally; the rest is overwritten
        // unconditionally on reuse.
        void reset() {
            tex = null;
            depthTex = null;
            wall = false;
            lit = false;
            ox1 = 0.0f; oy1 = 0.0f; ox2 = 0.0f; oy2 = 0.0f;
            ox3 = 0.0f; oy3 = 0.0f; ox4 = 0.0f; oy4 = 0.0f;
            r1 = 0.0f; g1 = 0.0f; b1 = 0.0f;
            r2 = 0.0f; g2 = 0.0f; b2 = 0.0f;
            r3 = 0.0f; g3 = 0.0f; b3 = 0.0f;
            windS = 0.0f; windT = 0.0f; windSeed = 0.0f;
            windAmp = 0.0f; windPeriod = 0.0f;
            padL = 0.0f; padR = 0.0f; padT = 0.0f;
            frameTop = 0.0f; frameBottom = 0.0f; frameLeft = 0.0f;
            barrier = 0.0f;
            cls = -1;
            genus = -1;
            leafX = 0.0f; leafY = 0.0f; leafCell = 0.0f; leafRate = 0.0f;
            bendPow = 1.0f; bladeVar = 1.0f;
            tipF = 0.0f; sheenF = 0.0f;
            dampF = 1.0f; flutF = 1.0f; steadyF = 1.0f;
            block = 0.0f; swingF = 1.0f; inertiaF = 1.0f;
            lobePx = 0.0f; flickPx = 0.0f; maskF = 0.0f;
            sqX = 0.0f; sqY = 0.0f; sqZ = 0.0f;
            anchorX = 0.0f; anchorY = 0.0f; depthShift = 0.0f;
        }
    }

    // Quad pool: capture allocated ~4750 quads per frame (~170 MB/s of
    // garbage at the frame cap). Both ends are the game thread: the capture
    // pops, postRender (from the render state's prePopulating) returns a
    // drawn batch in bulk.
    private static final int POOL_CAP = 20000;
    private static final ArrayList<GrassQuad> quadPool = new ArrayList<>(4096);
    static int poolHit5s;
    static int poolMiss5s;

    static GrassQuad obtainQuad() {
        ArrayList<GrassQuad> pool = quadPool;
        int n = pool.size();
        if (n == 0) {
            if (WindSwayMod.debugLog) poolMiss5s++;
            return new GrassQuad();
        }
        if (WindSwayMod.debugLog) poolHit5s++;
        return pool.remove(n - 1);
    }

    // After the last segment of a batch drew. The texture refs are dropped
    // so a pooled quad never pins a page past a world unload.
    private static void recycle(ArrayList<GrassQuad> qs) {
        ArrayList<GrassQuad> pool = quadPool;
        int room = POOL_CAP - pool.size();
        for (int i = 0; i < qs.size(); ++i) {
            GrassQuad q = qs.get(i);
            q.reset();
            if (room > 0) {
                pool.add(q);
                --room;
            }
        }
    }

    private ArrayList<GrassQuad> quads;
    // Interleave ranges: range r spans quads [cuts[r], cuts[r+1]) and is
    // queued as its own draw between two tree segments; the batch is
    // built and uploaded once, by the first range that renders.
    private int[] cuts;
    private int[] segRun;
    private boolean built;
    private int baseVert;
    private int live;

    // Camera snapshot of the capture frame (game thread); every segment
    // sends it, the values change per frame so a cache would always miss.
    private float camOffJX;
    private float camOffJY;
    private float camJigSqX;
    private float camJigSqY;
    private float camCentreX;
    private float camCentreY;
    private float camK1;
    private boolean world;

    public void setFrameCamera(float offJX, float offJY, float jigSqX, float jigSqY,
            float centreX, float centreY, float k1, boolean worldPath) {
        camOffJX = offJX;
        camOffJY = offJY;
        camJigSqX = jigSqX;
        camJigSqY = jigSqY;
        camCentreX = centreX;
        camCentreY = centreY;
        camK1 = k1;
        world = worldPath;
    }

    public void set(ArrayList<GrassQuad> quads) {
        setSegmented(quads, new int[] {0, quads.size()}, 1);
    }

    // live = number of ranges that get queued (postRender count).
    public void setSegmented(ArrayList<GrassQuad> quads, int[] cuts, int live) {
        this.quads = quads;
        this.cuts = cuts;
        this.live = live;
    }

    public TextureDraw.GenericDrawer segment(int range) {
        return new SegmentDrawer(this, range);
    }

    private void release() {
        if (--live > 0) return;
        ArrayList<GrassQuad> qs = quads;
        quads = null;
        cuts = null;
        segRun = null;
        built = false;
        if (qs != null) recycle(qs);
    }

    private static final class SegmentDrawer extends TextureDraw.GenericDrawer {
        private final WindSwayGrassDrawer owner;
        private final int range;

        SegmentDrawer(WindSwayGrassDrawer owner, int range) {
            this.owner = owner;
            this.range = range;
        }

        @Override
        public void render() {
            owner.renderSegment(range);
        }

        @Override
        public void postRender() {
            owner.release();
        }
    }

    private static void lookupUniforms() {
        ShaderProgram program = shader.getShaderProgram();
        uWind = loc(program, "uWind");
        uClock = loc(program, "uClock");
        uTurb = loc(program, "uTurb");
        uMix = loc(program, "uMix");
        uResp = loc(program, "uResp");
        uRing = loc(program, "uRing");
        uRing2 = loc(program, "uRing2");
        uPlant = loc(program, "uPlant");
        uPlant2 = loc(program, "uPlant2");
        uLeafP = loc(program, "uLeafP");
        uLeafQ = loc(program, "uLeafQ");
        uTip = loc(program, "uTip");
        uSheen = loc(program, "uSheen");
        uModel = loc(program, "uModel");
        uFlut = loc(program, "uFlut");
        uHon = loc(program, "uHon");
        uHon2 = loc(program, "uHon2");
        uPTurb = loc(program, "uPTurb");
        uLean = loc(program, "uLean");
        uCross = loc(program, "uCross");
        uBody = loc(program, "uBody");
        uLeafM = loc(program, "uLeafM");
        uFlick = loc(program, "uFlick");
        uFlick2 = loc(program, "uFlick2");
        uLeafM2 = loc(program, "uLeafM2");
        uCamA = loc(program, "uCamA");
        uCamB = loc(program, "uCamB");
        uniformsLooked = true;
    }

    private static int loc(ShaderProgram program, String name) {
        ShaderProgram.Uniform u = program.getUniform(name, 35666);
        return u == null ? -1 : u.loc;
    }

    private static void setU(int loc, int at) {
        if (loc < 0) return;
        if (uLoaded && plantU[at] == lastU[at] && plantU[at + 1] == lastU[at + 1]
                && plantU[at + 2] == lastU[at + 2] && plantU[at + 3] == lastU[at + 3]) {
            return;
        }
        GL20.glUniform4f(loc, plantU[at], plantU[at + 1], plantU[at + 2], plantU[at + 3]);
        lastU[at] = plantU[at];
        lastU[at + 1] = plantU[at + 1];
        lastU[at + 2] = plantU[at + 2];
        lastU[at + 3] = plantU[at + 3];
    }

    @Override
    public void render() {
        renderSegment(0);
    }

    // One interleave range. The first range that renders builds and
    // uploads the whole batch; every range pays its own state and program
    // setup (tree segments draw in between and clobber both).
    void renderSegment(int range) {
        if (state == FAILED) return;
        applyRearm();
        boolean diag = WindSwayMod.debugLog;
        long t0 = diag ? System.nanoTime() : 0L;
        boolean timed = false;
        boolean withVao = false;
        boolean glSection = false;
        boolean tornDown = false;
        try {
            if (debugFail) {
                throw new IllegalStateException("forced by setDebugGrassFail");
            }
            // Bakes are queued before any capture of the frame, so this
            // cannot happen from our own path; a foreign bake skips the
            // batch, it does not park the mod for the session.
            if (FBORenderChunkManager.instance.renderThreadCurrent != null) {
                return;
            }
            if (shader == null) {
                shader = ShaderManager.instance.getOrCreateShader("windsway_grass", true, false);
                shader.getShaderProgram().addCompileListener(ON_COMPILE);
            }
            ShaderProgram program = shader.getShaderProgram();
            if (!program.isCompiled() && !WindSwayMod.recompileShaderWithLog(program)) {
                fail("windsway_grass shader not compiled");
                return;
            }
            // A recompile moves the uniform locations and drops the sampler
            // units.
            int pid = program.getShaderID();
            if (pid != programId) {
                programId = pid;
                uniformsLooked = false;
                samplersSet = false;
                uLoaded = false;
            }
            if (!uniformsLooked) lookupUniforms();
            if (maxSlots == 0) {
                int units = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
                maxSlots = Math.max(1, Math.min(MAX_SLOTS, units / 2));
                depthBase = maxSlots;
            }
            state = READY;
            if (this.quads == null) {
                // Probe: create the atlas here too.
                DepthAtlas.beginBatch();
                return;
            }
            if (this.quads.isEmpty()) return;
            glSection = true;
            GlTrace.begin("grass segment");
            if (!built) {
                build(diag, t0);
            }
            int runFrom = segRun[range];
            int runTo = segRun[range + 1];
            if (runFrom >= runTo) return;
            long tF = diag ? System.nanoTime() : 0L;

            // Scene ortho on the Core stacks is already the right MVP.
            // setDirty before each set: the model pipeline writes depth
            // state through raw GL11 behind the trackers; an elided set can
            // leave the real mask on and the batch would write stalk depth,
            // punching holes into fences drawn after it.
            timed = diag && gpuTimer.begin();
            boolean glCheck = glProbe.begin();
            long tB = 0L;
            if (diag) {
                tB = System.nanoTime();
                cpuTimerNs.addAndGet(tB - tF);
            }
            GLStateRenderThread.Blend.setDirty();
            GLStateRenderThread.Blend.set(true);
            GLStateRenderThread.BlendFuncSeparate.setDirty();
            GLStateRenderThread.BlendFuncSeparate.set(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GLStateRenderThread.DepthTest.setDirty();
            GLStateRenderThread.DepthTest.set(true);
            GLStateRenderThread.DepthFunc.setDirty();
            GLStateRenderThread.DepthFunc.set(GL11.GL_LEQUAL);
            GLStateRenderThread.DepthMask.setDirty();
            GLStateRenderThread.DepthMask.set(false);
            // No setDirty: every engine writer of the stencil enable restores the
            // tracker.
            GLStateRenderThread.StencilTest.set(false);
            long t1 = 0L;
            if (diag) {
                t1 = System.nanoTime();
                cpuStateNs.addAndGet(t1 - tB);
            }

            if (!samplersSet) {
                samplersSet = true;
                shader.Start();
                for (int i = 0; i < MAX_SLOTS; ++i) {
                    program.setSamplerUnit("DIFFUSE" + i, i < maxSlots ? i : 0);
                    program.setSamplerUnit("DEPTH" + i, depthBase + (i < maxSlots ? i : 0));
                }
                shader.End();
            }

            long t2 = t1;

            // The engine never binds a VAO, so ours holds the 14 pointers (the
            // stream VBO id survives orphaning) and the default object keeps the ring
            // buffer's state. Without VAO: pointers per batch, extras disabled after.
            withVao = useVao && vaoOk();
            if (withVao) {
                if (vao == 0) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamVbo);
                    vao = GL30.glGenVertexArrays();
                    GL30.glBindVertexArray(vao);
                    setAttribPointers();
                    for (int i = 0; i < NUM_ATTRIBS; ++i) {
                        GL20.glEnableVertexAttribArray(i);
                    }
                } else {
                    GL30.glBindVertexArray(vao);
                }
            } else {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamVbo);
                setAttribPointers();
                for (int i = 0; i < NUM_ATTRIBS; ++i) {
                    GL20.glEnableVertexAttribArray(i);
                }
            }

            long t3 = 0L;
            if (diag) {
                t3 = System.nanoTime();
                cpuAttribNs.addAndGet(t3 - t2);
            }
            shader.Start();
            VertexBufferObject.setModelViewProjection(shader);
            TreeSway.fillPlantUniforms(plantU);
            setU(uWind, 0);
            setU(uClock, 4);
            setU(uTurb, 8);
            setU(uMix, 12);
            setU(uResp, 16);
            setU(uRing, 20);
            setU(uRing2, 24);
            setU(uPlant, 28);
            setU(uPlant2, 32);
            setU(uLeafP, 36);
            setU(uLeafQ, 40);
            setU(uTip, 44);
            setU(uSheen, 48);
            setU(uModel, 52);
            setU(uFlut, 56);
            setU(uHon, 60);
            setU(uHon2, 64);
            setU(uPTurb, 68);
            setU(uLean, 72);
            setU(uCross, 76);
            setU(uBody, 80);
            setU(uLeafM, 84);
            setU(uFlick, 88);
            setU(uFlick2, 92);
            setU(uLeafM2, 96);
            uLoaded = true;
            if (uCamA >= 0) {
                GL20.glUniform4f(uCamA, camOffJX, camOffJY, camJigSqX, camJigSqY);
            }
            if (uCamB >= 0) {
                GL20.glUniform4f(uCamB, camCentreX, camCentreY, camK1, world ? 1.0f : 0.0f);
            }
            long t4 = 0L;
            if (diag) {
                t4 = System.nanoTime();
                cpuProgNs.addAndGet(t4 - t3);
            }

            drawRuns(baseVert, runFrom, runTo);
            long t5 = 0L;
            if (diag) {
                t5 = System.nanoTime();
                cpuDrawNs.addAndGet(t5 - t4);
            }

            shader.End();
            teardown(withVao);
            tornDown = true;
            ++totalSegments;
            GlTrace.end("grass segment");
            if (timed) {
                gpuTimer.end();
                timed = false;
            }
            if (diag) {
                long t6 = System.nanoTime();
                cpuEndNs.addAndGet(t6 - t5);
                cpuNs.addAndGet(t6 - t0);
            }
            if (glCheck) {
                int err = glProbe.end();
                if (err != GL11.GL_NO_ERROR) {
                    fail("GL error 0x" + Integer.toHexString(err) + " after the batch draw");
                    return;
                }
            }
            if (!firstBatchLogged) {
                firstBatchLogged = true;
                WindSwayMod.trace("first grass batch rendered (" + this.quads.size() + " quads, "
                        + (world ? "world" : "screen") + " path)");
            }
        } catch (Throwable t) {
            fail(t);
        } finally {
            if (timed) gpuTimer.end();
            if (glSection && !tornDown) {
                teardown(withVao);
                GlTrace.end("grass segment (failed)");
            }
        }
    }

    // Render thread, after a segment drew or threw: unit 0 active, no VAO
    // or extra attribute arrays, no array buffer. A VAO left bound would
    // swallow the engine's attrib pointers for the rest of the session.
    // VBORenderer.flush()'s contract with the RingBuffer: without the
    // flags the next geometry run keeps attrib pointers into OUR buffer,
    // usually the very draw that triggered the flush. The flags are not
    // consumed between two generic drawers; without the cache invalidation
    // the shadow drawer samples our last page.
    private static void teardown(boolean withVao) {
        try {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            if (withVao) {
                GL30.glBindVertexArray(0);
            } else {
                for (int i = 5; i < NUM_ATTRIBS; ++i) {
                    GL20.glDisableVertexAttribArray(i);
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        } catch (Throwable ignored) {
        }
        Texture.lastTextureID = -1;
        SpriteRenderer.ringBuffer.restoreVbos = true;
        SpriteRenderer.ringBuffer.restoreBoundTextures = true;
    }

    // Render thread, once per batch: stage fill, the runs (broken at every
    // cut, so a range is a whole number of runs) and the VBO upload.
    private void build(boolean diag, long t0) {
        built = true;
        int nRanges = cuts.length - 1;
        segRun = new int[nRanges + 1];
        int maxBytes = this.quads.size() * 4 * STRIDE;
        if (stage.capacity() < maxBytes) {
            int cap = stage.capacity();
            while (cap < maxBytes) {
                cap *= 2;
            }
            stage = BufferUtils.createByteBuffer(cap);
            stageF = stage.asFloatBuffer();
        }
        stageF.clear();
        int slots = Math.max(1, Math.min(slotsWanted, maxSlots));
        DepthAtlas.beginBatch();
        atlasOn = DepthAtlas.active();
        releaseRuns();
        Run cur = null;
        int vertCount = 0;
        int range = 0;
        for (int i = 0; i < this.quads.size(); ++i) {
            while (range < nRanges - 1 && i >= cuts[range + 1]) {
                ++range;
                segRun[range] = runs.size();
                cur = null;
            }
            GrassQuad q = this.quads.get(i);
            if (q.tex == null || q.tex.getTextureId() == null) continue;
            if (q.depthTex == null || q.depthTex.getTextureId() == null) continue;
            TextureID diffuse = q.tex.getTextureId();
            TextureID depth = q.depthTex.getTextureId();
            // No GL texture yet (or gone): vanilla's bind would show the
            // engine's red-white error texture, the capture rejects these a
            // frame earlier; anything that slips through is dropped.
            if (diffuse.getID() == -1 || depth.getID() == -1) {
                ++diagPageMiss;
                continue;
            }
            DepthAtlas.Cell cell = atlasOn ? DepthAtlas.cellFor(q.depthTex) : null;
            // Rewritten in place into atlas space: a quad is built once,
            // then recycled.
            if (cell != null) {
                q.du0 = cell.u0 + q.du0 * cell.su;
                q.du1 = cell.u0 + q.du1 * cell.su;
                q.dv0 = cell.v0 + q.dv0 * cell.sv;
                q.dv1 = cell.v0 + q.dv1 * cell.sv;
            }
            int d = cur == null ? -1 : slotOf(cur.diffuse, cur.nDiffuse, diffuse);
            int z = cur == null ? -1 : cell != null ? 0 : slotOf(cur.depth, cur.nDepth, depth);
            if (cur == null || (d < 0 && cur.nDiffuse >= slots) || (z < 0 && cur.nDepth >= slots)) {
                cur = newRun(vertCount);
                d = -1;
                z = cell != null ? 0 : -1;
            }
            if (d < 0) {
                d = cur.nDiffuse;
                cur.diffuse[cur.nDiffuse++] = diffuse;
            }
            if (z < 0) {
                z = cur.nDepth;
                cur.depth[cur.nDepth++] = depth;
            }
            putQuad(stageF, q, d + MAX_SLOTS * z, world);
            cur.count += 4;
            vertCount += 4;
        }
        for (int r = range + 1; r <= nRanges; ++r) {
            segRun[r] = runs.size();
        }
        if (vertCount == 0) return;
        int bytes = vertCount * STRIDE;
        stage.position(0);
        stage.limit(bytes);
        long tF = 0L;
        if (diag) {
            tF = System.nanoTime();
            cpuFillNs.addAndGet(tF - t0);
            diagRuns.addAndGet(runs.size());
        }
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
        baseVert = streamOffset / STRIDE;
        streamOffset += bytes;
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
        if (diag) {
            cpuUploadNs.addAndGet(System.nanoTime() - tF);
        }
    }

    // Raw binds like the RingBuffer's texture1 path: TextureID.bind() turns the
    // engine's NEAREST depth maps bilinear for the session (edges leak through
    // walls). Diffuse pages keep the engine's filter state, the shader samples
    // texel centres. Unit cache per batch: vanilla rebinds units 0-2 in between.
    private static void drawRuns(int baseVert, int from, int to) {
        for (int u = 0; u < unitBound.length; ++u) {
            unitBound[u] = -1;
        }
        int active = 0;
        for (int i = from; i < to; ++i) {
            Run r = runs.get(i);
            for (int k = 0; k < r.nDiffuse; ++k) {
                active = bindUnit(k, r.diffuse[k], active, false);
            }
            for (int k = 0; k < r.nDepth; ++k) {
                TextureID depth = r.depth[k];
                active = depth == null ? bindUnitRaw(depthBase + k, DepthAtlas.textureId(), active)
                        : bindUnit(depthBase + k, depth, active, true);
            }
            if (active != 0) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                active = 0;
            }
            GL11.glDrawArrays(GL11.GL_QUADS, baseVert + r.first, r.count);
        }
    }

    // A page without a GL texture yet is created through the engine's
    // bind() on the active unit: switch first, or the new texture lands on
    // the previous unit behind the cache.
    private static int bindUnit(int unit, TextureID tex, int active, boolean nearest) {
        int id = tex.getID();
        if (id != -1 && unitBound[unit] == id) {
            // Still bound here, but the engine may have bound the same page
            // elsewhere since and re-applied its own filter.
            if (!nearest) return active;
            if (active != unit) GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            TreeRenderer.nearestAlways();
            return unit;
        }
        if (active != unit) GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        if (id == -1) {
            id = TreeRenderer.ensureId(tex);
            if (tex.getID() == -1) ++diagErrorTex;
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        if (nearest) TreeRenderer.nearestAlways();
        unitBound[unit] = id;
        diagBinds.incrementAndGet();
        return unit;
    }

    private static int bindUnitRaw(int unit, int id, int active) {
        if (unitBound[unit] == id) return active;
        if (active != unit) GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        unitBound[unit] = id;
        diagBinds.incrementAndGet();
        return unit;
    }

    @Override
    public void postRender() {
        release();
    }

    private static void putQuad(FloatBuffer b, GrassQuad q, int slotCode, boolean world) {
        // Texture.render(ObjectRenderEffects) corners, order TL/TR/BR/BL; wind
        // flora widened by its reach, uv extended to match.
        float padL = q.padL;
        float padR = q.padR;
        float padT = q.padT;
        float tU = (q.u1 - q.u0) / q.w;
        float tV = (q.v1 - q.v0) / q.h;
        float tDU = (q.du1 - q.du0) / q.w;
        float tDV = (q.dv1 - q.dv0) / q.h;
        float uL = q.u0 - padL * tU;
        float uR = q.u1 + padR * tU;
        float vT = q.v0 - padT * tV;
        float duL = q.du0 - padL * tDU;
        float duR = q.du1 + padR * tDU;
        float dvT = q.dv0 - padT * tDV;
        float xTL = q.ox - padL + q.ox1 * q.w;
        float yTL = q.oy - padT + q.oy1 * q.h;
        float xTR = q.ox + q.w + padR + q.ox2 * q.w;
        float yTR = q.oy - padT + q.oy2 * q.h;
        float xBR = q.ox + q.w + padR + q.ox3 * q.w;
        float yBR = q.oy + q.h + q.oy3 * q.h;
        float xBL = q.ox - padL + q.ox4 * q.w;
        float yBL = q.oy + q.h + q.oy4 * q.h;
        float uMin = Math.min(q.u0, q.u1);
        float uMax = Math.max(q.u0, q.u1);
        // aFrame.w: barrier bits 2/4, page slot pair above (times 8). The seed
        // travels in aWind.y.
        float frameW = q.barrier + 8.0f * slotCode;
        float r1 = q.lit ? q.r1 : q.r;
        float g1 = q.lit ? q.g1 : q.g;
        float b1 = q.lit ? q.b1 : q.b;
        float r2 = q.lit ? q.r2 : q.r;
        float g2 = q.lit ? q.g2 : q.g;
        float b2 = q.lit ? q.b2 : q.b;
        float r3 = q.lit ? q.r3 : q.r;
        float g3 = q.lit ? q.g3 : q.g;
        float b3 = q.lit ? q.b3 : q.b;
        float[] o = quad;
        putVertex(o, 0, xTL, yTL, q, q.r, q.g, q.b, uL, vT, duL, dvT, uMin, uMax, tU, tV, tDU, tDV, frameW);
        putVertex(o, FLOATS, xTR, yTR, q, r1, g1, b1, uR, vT, duR, dvT, uMin, uMax, tU, tV, tDU, tDV, frameW);
        putVertex(o, 2 * FLOATS, xBR, yBR, q, r2, g2, b2, uR, q.v1, duR, q.dv1, uMin, uMax, tU, tV, tDU, tDV, frameW);
        putVertex(o, 3 * FLOATS, xBL, yBL, q, r3, g3, b3, uL, q.v1, duL, q.dv1, uMin, uMax, tU, tV, tDU, tDV, frameW);
        if (world) {
            // World lane: aPosition takes the square, aDepthFar the depth
            // shift, aClass5.zw the corner's pixel offset from the anchor
            // (exact float split, the shader rebuilds the corner bit for bit).
            for (int c = 0; c < 4; ++c) {
                int at = c * FLOATS;
                float px = o[at];
                float py = o[at + 1];
                o[at] = q.sqX;
                o[at + 1] = q.sqY;
                o[at + 2] = q.sqZ;
                o[at + 11] = q.depthShift;
                o[at + 46] = px - q.anchorX;
                o[at + 47] = py - q.anchorY;
            }
        }
        b.put(o);
    }

    private static void putVertex(float[] o, int i, float x, float y, GrassQuad q,
            float r, float g, float bl,
            float u, float v, float du, float dv,
            float uMin, float uMax, float tU, float tV, float tDU, float tDV, float frameW) {
        o[i] = x;
        o[i + 1] = y;
        o[i + 2] = q.zNear;
        o[i + 3] = r;
        o[i + 4] = g;
        o[i + 5] = bl;
        o[i + 6] = q.a;
        o[i + 7] = u;
        o[i + 8] = v;
        o[i + 9] = du;
        o[i + 10] = dv;
        o[i + 11] = q.zFar;
        o[i + 12] = q.windS;
        o[i + 13] = q.windSeed;
        o[i + 14] = q.windAmp;
        o[i + 15] = q.windPeriod;
        o[i + 16] = uMin;
        o[i + 17] = uMax;
        o[i + 18] = q.v0;
        o[i + 19] = q.v1;
        o[i + 20] = tU;
        o[i + 21] = tV;
        o[i + 22] = tDU;
        o[i + 23] = tDV;
        o[i + 24] = q.frameTop;
        o[i + 25] = q.frameBottom;
        o[i + 26] = q.frameLeft;
        o[i + 27] = frameW;
        o[i + 28] = q.leafX;
        o[i + 29] = q.leafY;
        o[i + 30] = q.leafCell;
        o[i + 31] = q.leafRate;
        o[i + 32] = q.bendPow;
        o[i + 33] = q.bladeVar;
        o[i + 34] = q.tipF;
        o[i + 35] = q.sheenF;
        o[i + 36] = q.dampF;
        o[i + 37] = q.flutF;
        o[i + 38] = q.windT;
        o[i + 39] = q.steadyF;
        o[i + 40] = q.block;
        o[i + 41] = q.swingF;
        o[i + 42] = q.inertiaF;
        o[i + 43] = q.lobePx;
        o[i + 44] = q.flickPx;
        o[i + 45] = q.maskF;
        o[i + 46] = 0.0f;
        o[i + 47] = 0.0f;
    }
}
