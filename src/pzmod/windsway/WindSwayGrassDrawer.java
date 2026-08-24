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
        if (s == UNKNOWN && !probeQueued) {
            probeQueued = true;
            SpriteRenderer.instance.drawGeneric(new WindSwayGrassDrawer());
        }
        return false;
    }

    static void onPassDone() {
        probeQueued = false;
    }

    // Game thread. After a latch: probe again, once per new world or on
    // request from the console.
    static void rearm() {
        if (state != FAILED) return;
        shader = null;
        samplersSet = false;
        uniformsLooked = false;
        uLoaded = false;
        probeQueued = false;
        glProbe.reset();
        state = UNKNOWN;
        WindSwayMod.trace("grass batch re-armed, probing again");
    }

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
    // pos3f, color4f, uv0, uv1, depth1f, wind4f, rect4f, texel4f, frame4f
    // (layout contract with windsway_grass_static.vert).
    private static final int STRIDE = 112;
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
    }
    private static int uWind = -1;
    private static int uClock = -1;
    private static int uTurb = -1;
    private static int uMix = -1;
    private static int uResp = -1;
    private static int uRing = -1;
    private static int uRing2 = -1;
    private static int uPlant = -1;
    private static int uPlant2 = -1;
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
        // Wind flora: lean-axis position (tiles), seed, lean amplitude (sprite px
        // per unit lean), period (s), reach per side, the object's frame in this
        // part's uv, barrier bits 2 (left) / 4 (right).
        float windS;
        float windSeed;
        float windAmp;
        float windPeriod;
        float padL;
        float padR;
        float frameTop;
        float frameBottom;
        float frameLeft;
        float barrier;
    }

    private ArrayList<GrassQuad> quads;

    public void set(ArrayList<GrassQuad> quads) {
        this.quads = quads;
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
        if (state == FAILED) return;
        boolean diag = WindSwayMod.debugLog;
        long t0 = diag ? System.nanoTime() : 0L;
        boolean timed = false;
        try {
            if (debugFail) {
                throw new IllegalStateException("forced by setDebugGrassFail");
            }
            if (FBORenderChunkManager.instance.renderThreadCurrent != null) {
                fail("render thread inside a chunk bake");
                return;
            }
            if (shader == null) {
                shader = ShaderManager.instance.getOrCreateShader("windsway_grass", true, false);
            }
            if (!shader.getShaderProgram().isCompiled()
                    && !WindSwayMod.recompileShaderWithLog(shader.getShaderProgram())) {
                fail("windsway_grass shader not compiled");
                return;
            }
            if (!uniformsLooked) lookupUniforms();
            if (maxSlots == 0) {
                int units = GL11.glGetInteger(GL20.GL_MAX_TEXTURE_IMAGE_UNITS);
                maxSlots = Math.max(1, Math.min(MAX_SLOTS, units / 2));
                depthBase = maxSlots;
            }
            state = READY;
            if (this.quads == null || this.quads.isEmpty()) return;

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
            for (int i = 0; i < this.quads.size(); ++i) {
                GrassQuad q = this.quads.get(i);
                if (q.tex == null || q.tex.getTextureId() == null) continue;
                if (q.depthTex == null || q.depthTex.getTextureId() == null) continue;
                TextureID diffuse = q.tex.getTextureId();
                TextureID depth = q.depthTex.getTextureId();
                DepthAtlas.Cell cell = atlasOn ? DepthAtlas.cellFor(q.depthTex) : null;
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
                putQuad(stageF, q, d + MAX_SLOTS * z);
                cur.count += 4;
                vertCount += 4;
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
                ShaderProgram program = shader.getShaderProgram();
                for (int i = 0; i < MAX_SLOTS; ++i) {
                    program.setSamplerUnit("DIFFUSE" + i, i < maxSlots ? i : 0);
                    program.setSamplerUnit("DEPTH" + i, depthBase + (i < maxSlots ? i : 0));
                }
                shader.End();
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
            int baseVert = streamOffset / STRIDE;
            streamOffset += bytes;
            long t2 = 0L;
            if (diag) {
                t2 = System.nanoTime();
                cpuUploadNs.addAndGet(t2 - t1);
            }

            // The engine never binds a VAO, so ours holds the nine pointers (the
            // stream VBO id survives orphaning) and the default object keeps the ring
            // buffer's state. Without VAO: pointers per batch, extras disabled after.
            boolean withVao = useVao && vaoOk();
            if (withVao) {
                if (vao == 0) {
                    vao = GL30.glGenVertexArrays();
                    GL30.glBindVertexArray(vao);
                    setAttribPointers();
                    for (int i = 0; i < 9; ++i) {
                        GL20.glEnableVertexAttribArray(i);
                    }
                } else {
                    GL30.glBindVertexArray(vao);
                }
            } else {
                setAttribPointers();
                for (int i = 0; i < 9; ++i) {
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
            uLoaded = true;
            long t4 = 0L;
            if (diag) {
                t4 = System.nanoTime();
                cpuProgNs.addAndGet(t4 - t3);
            }

            drawRuns(baseVert);
            long t5 = 0L;
            if (diag) {
                t5 = System.nanoTime();
                cpuDrawNs.addAndGet(t5 - t4);
            }

            shader.End();
            if (withVao) {
                GL30.glBindVertexArray(0);
            } else {
                for (int i = 5; i < 9; ++i) {
                    GL20.glDisableVertexAttribArray(i);
                }
            }
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
            if (timed) {
                gpuTimer.end();
                timed = false;
            }

            // VBORenderer.flush()'s contract with the RingBuffer: without
            // these flags the next geometry run keeps attrib pointers into
            // OUR buffer, usually the very draw that triggered the flush.
            // The flags are not consumed between two generic drawers; without
            // the cache invalidation the shadow drawer samples our last page.
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
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
                WindSwayMod.trace("first grass batch rendered (" + this.quads.size() + " quads)");
            }
        } catch (Throwable t) {
            if (timed) gpuTimer.end();
            Texture.lastTextureID = -1;
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            fail(t);
        }
    }

    // Raw binds like the RingBuffer's texture1 path: TextureID.bind() turns the
    // engine's NEAREST depth maps bilinear for the session (edges leak through
    // walls). Diffuse pages keep the engine's filter state, the shader samples
    // texel centres. Unit cache per batch: vanilla rebinds units 0-2 in between.
    private static void drawRuns(int baseVert) {
        for (int u = 0; u < unitBound.length; ++u) {
            unitBound[u] = 0;
        }
        int active = 0;
        for (int i = 0; i < runs.size(); ++i) {
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
        if (id != -1 && unitBound[unit] == id) return active;
        if (active != unit) GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        if (id == -1) id = TreeRenderer.ensureId(tex);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        if (nearest) TreeRenderer.nearestOnce(tex, id);
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
        this.quads = null;
    }

    private static void putQuad(FloatBuffer b, GrassQuad q, int slotCode) {
        // Texture.render(ObjectRenderEffects) corners, order TL/TR/BR/BL; wind
        // flora widened by its reach, uv extended to match.
        float padL = q.padL;
        float padR = q.padR;
        float tU = (q.u1 - q.u0) / q.w;
        float tV = (q.v1 - q.v0) / q.h;
        float tDU = (q.du1 - q.du0) / q.w;
        float tDV = (q.dv1 - q.dv0) / q.h;
        float uL = q.u0 - padL * tU;
        float uR = q.u1 + padR * tU;
        float duL = q.du0 - padL * tDU;
        float duR = q.du1 + padR * tDU;
        float xTL = q.ox - padL + q.ox1 * q.w;
        float yTL = q.oy + q.oy1 * q.h;
        float xTR = q.ox + q.w + padR + q.ox2 * q.w;
        float yTR = q.oy + q.oy2 * q.h;
        float xBR = q.ox + q.w + padR + q.ox3 * q.w;
        float yBR = q.oy + q.h + q.oy3 * q.h;
        float xBL = q.ox - padL + q.ox4 * q.w;
        float yBL = q.oy + q.h + q.oy4 * q.h;
        float uMin = Math.min(q.u0, q.u1);
        float uMax = Math.max(q.u0, q.u1);
        // aFrame.w: seed fraction, barrier bits 2/4, page slot pair above (times 8).
        float frameW = q.windSeed + q.barrier + 8.0f * slotCode;
        float[] o = quad;
        putVertex(o, 0, xTL, yTL, q, uL, q.v0, duL, q.dv0, uMin, uMax, tU, tV, tDU, tDV, frameW);
        putVertex(o, FLOATS, xTR, yTR, q, uR, q.v0, duR, q.dv0, uMin, uMax, tU, tV, tDU, tDV, frameW);
        putVertex(o, 2 * FLOATS, xBR, yBR, q, uR, q.v1, duR, q.dv1, uMin, uMax, tU, tV, tDU, tDV, frameW);
        putVertex(o, 3 * FLOATS, xBL, yBL, q, uL, q.v1, duL, q.dv1, uMin, uMax, tU, tV, tDU, tDV, frameW);
        b.put(o);
    }

    private static void putVertex(float[] o, int i, float x, float y, GrassQuad q,
            float u, float v, float du, float dv,
            float uMin, float uMax, float tU, float tV, float tDU, float tDV, float frameW) {
        o[i] = x;
        o[i + 1] = y;
        o[i + 2] = q.zNear;
        o[i + 3] = q.r;
        o[i + 4] = q.g;
        o[i + 5] = q.b;
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
    }
}
