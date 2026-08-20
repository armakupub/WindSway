package pzmod.windsway;

import java.nio.ByteBuffer;
import java.util.ArrayList;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import zombie.core.SpriteRenderer;
import zombie.core.opengl.GLStateRenderThread;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.core.skinnedmodel.shader.Shader;
import zombie.core.skinnedmodel.shader.ShaderManager;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureDraw;
import zombie.core.textures.TextureID;
import zombie.iso.fboRenderChunk.FBORenderChunkManager;

// Grass batch on vanilla's tileWithDepth contract: screen-space quads as
// Texture.render(ObjectRenderEffects), the engine's authored depth maps,
// depth TEST only, no alpha discard (only depth-map texels of 0 discard,
// so edges stay soft). Ordering against no-depth-write translucents is
// handled capture-side by mid-pass flushes.
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
        probeQueued = false;
        glProbe.reset();
        state = UNKNOWN;
        WindSwayMod.trace("grass batch re-armed, probing again");
    }

    private static final WindSwayMod.GlProbe glProbe = new WindSwayMod.GlProbe();

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
    // pos3f, color4f, uv0, uv1, depth1f.
    private static final int STRIDE = 48;
    private static int streamCapacity = 4 * 1024 * 1024;
    private static int streamVbo = 0;
    private static int streamOffset = 0;
    private static ByteBuffer stage = BufferUtils.createByteBuffer(256 * 1024);

    private static final class Seg {
        TextureID diffuse;
        TextureID depth;
        int first;
        int count;
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
    }

    private ArrayList<GrassQuad> quads;

    public void set(ArrayList<GrassQuad> quads) {
        this.quads = quads;
    }

    @Override
    public void render() {
        if (state == FAILED) return;
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
            state = READY;
            if (this.quads == null || this.quads.isEmpty()) return;

            int maxBytes = this.quads.size() * 4 * STRIDE;
            if (stage.capacity() < maxBytes) {
                int cap = stage.capacity();
                while (cap < maxBytes) {
                    cap *= 2;
                }
                stage = BufferUtils.createByteBuffer(cap);
            }
            stage.clear();
            ArrayList<Seg> segs = new ArrayList<>(4);
            Seg cur = null;
            int vertCount = 0;
            for (int i = 0; i < this.quads.size(); ++i) {
                GrassQuad q = this.quads.get(i);
                if (q.tex == null || q.tex.getTextureId() == null) continue;
                if (q.depthTex == null || q.depthTex.getTextureId() == null) continue;
                TextureID diffuse = q.tex.getTextureId();
                TextureID depth = q.depthTex.getTextureId();
                if (cur == null || cur.diffuse != diffuse || cur.depth != depth) {
                    cur = new Seg();
                    cur.diffuse = diffuse;
                    cur.depth = depth;
                    cur.first = vertCount;
                    segs.add(cur);
                }
                putQuad(stage, q);
                cur.count += 4;
                vertCount += 4;
            }
            if (vertCount == 0) return;
            stage.flip();
            int bytes = vertCount * STRIDE;

            // Scene ortho on the Core stacks is already the right MVP.
            // setDirty before each set: the model pipeline writes depth
            // state through raw GL11 behind the trackers; an elided set can
            // leave the real mask on and the batch would write stalk depth,
            // punching holes into fences drawn after it.
            boolean glCheck = glProbe.begin();
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
            GLStateRenderThread.StencilTest.setDirty();
            GLStateRenderThread.StencilTest.set(false);

            if (!samplersSet) {
                samplersSet = true;
                shader.Start();
                shader.getShaderProgram().setSamplerUnit("DIFFUSE", 0);
                shader.getShaderProgram().setSamplerUnit("DEPTH", 1);
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

            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, STRIDE, 0L);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, STRIDE, 12L);
            GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, STRIDE, 28L);
            GL20.glVertexAttribPointer(3, 2, GL11.GL_FLOAT, false, STRIDE, 36L);
            GL20.glVertexAttribPointer(4, 1, GL11.GL_FLOAT, false, STRIDE, 44L);
            for (int i = 0; i < 5; ++i) {
                GL20.glEnableVertexAttribArray(i);
            }

            shader.Start();
            VertexBufferObject.setModelViewProjection(shader);

            TextureID curDiffuse = null;
            TextureID curDepth = null;
            for (int i = 0; i < segs.size(); ++i) {
                Seg s = segs.get(i);
                if (s.depth != curDepth) {
                    GL13.glActiveTexture(GL13.GL_TEXTURE1);
                    // Raw bind like the RingBuffer's texture1 path: TextureID.bind()
                    // re-applies the TextureID's LINEAR default filters and turns the
                    // engine's NEAREST depth maps bilinear for the whole session
                    // (silhouette texels land nearer, edges leak through walls).
                    int depthId = s.depth.getID();
                    if (depthId == -1) {
                        s.depth.bind();
                    } else {
                        GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthId);
                    }
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
                    GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
                    GL13.glActiveTexture(GL13.GL_TEXTURE0);
                    curDepth = s.depth;
                }
                if (s.diffuse != curDiffuse) {
                    s.diffuse.bind();
                    curDiffuse = s.diffuse;
                }
                GL11.glDrawArrays(GL11.GL_QUADS, baseVert + s.first, s.count);
            }

            shader.End();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            // VBORenderer.flush()'s contract with the RingBuffer: without
            // these flags the next geometry run keeps attrib pointers into
            // OUR buffer, usually the very draw that triggered the flush.
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
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
            SpriteRenderer.ringBuffer.restoreVbos = true;
            SpriteRenderer.ringBuffer.restoreBoundTextures = true;
            fail(t);
        }
    }

    @Override
    public void postRender() {
        this.quads = null;
    }

    private static void putQuad(ByteBuffer b, GrassQuad q) {
        // Texture.render(ObjectRenderEffects): corner = rect corner + ore
        // fraction × sprite pixel size, order TL/TR/BR/BL.
        float xTL = q.ox + q.ox1 * q.w;
        float yTL = q.oy + q.oy1 * q.h;
        float xTR = q.ox + q.w + q.ox2 * q.w;
        float yTR = q.oy + q.oy2 * q.h;
        float xBR = q.ox + q.w + q.ox3 * q.w;
        float yBR = q.oy + q.h + q.oy3 * q.h;
        float xBL = q.ox + q.ox4 * q.w;
        float yBL = q.oy + q.h + q.oy4 * q.h;
        putVertex(b, xTL, yTL, q, q.u0, q.v0, q.du0, q.dv0);
        putVertex(b, xTR, yTR, q, q.u1, q.v0, q.du1, q.dv0);
        putVertex(b, xBR, yBR, q, q.u1, q.v1, q.du1, q.dv1);
        putVertex(b, xBL, yBL, q, q.u0, q.v1, q.du0, q.dv1);
    }

    private static void putVertex(ByteBuffer b, float x, float y, GrassQuad q,
            float u, float v, float du, float dv) {
        b.putFloat(x).putFloat(y).putFloat(q.zNear);
        b.putFloat(q.r).putFloat(q.g).putFloat(q.b).putFloat(q.a);
        b.putFloat(u).putFloat(v);
        b.putFloat(du).putFloat(dv);
        b.putFloat(q.zFar);
    }
}
