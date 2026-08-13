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

// Grass batch, vanilla-parity path: screen-space quads exactly as
// Texture.render(ObjectRenderEffects), tileWithDepth-style shading with
// the engine's authored depth maps. Depth TEST only, no alpha discard;
// edges stay soft because only depth-map texels of 0 discard. Ordering
// against no-depth-write translucents is handled capture-side by
// mid-pass flushes (WindSwayMod.onVanillaTranslucentDraw).
public class WindSwayGrassDrawer extends TextureDraw.GenericDrawer {

    private static Shader shader;
    private static volatile boolean samplersSet = false;
    private static volatile boolean renderFailedLogged = false;
    private static volatile boolean firstBatchLogged = false;

    // Streaming VBO, orphaned only on wrap. Not VBORenderer: its flush()
    // re-specs VBO+IBO via glBufferData every call — built for once per
    // pass, fatal at ~140 interleave breaks per frame. Here a break costs
    // one glBufferSubData append plus one glDrawArrays per texture
    // segment. Attrib layout follows the VBORenderer convention
    // (location == element index): pos3f, color4f, uv0, uv1, depth1f.
    private static final int STRIDE = 48;
    private static final int STREAM_CAPACITY = 4 * 1024 * 1024;
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
        // Screen rect in the scene's zoomed pixel space (vanilla quad base).
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
        // Diffuse UVs (flip already applied at capture).
        float u0;
        float v0;
        float u1;
        float v1;
        // Depth-map UV rect (TileDepthModifier intersection).
        float du0;
        float dv0;
        float du1;
        float dv1;
        // tileWithDepth uniforms zDepthBlendZ/zDepthBlendToZ, per-vertex here.
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

    private static volatile boolean bakeSkipLogged = false;

    @Override
    public void render() {
        try {
            if (FBORenderChunkManager.instance.renderThreadCurrent != null) {
                if (!bakeSkipLogged) {
                    bakeSkipLogged = true;
                    WindSwayMod.trace("batch skipped: render thread inside chunk bake");
                }
                return;
            }
            if (this.quads == null || this.quads.isEmpty()) return;
            if (shader == null) {
                shader = ShaderManager.instance.getOrCreateShader("windsway_grass", true, false);
            }
            if (!shader.getShaderProgram().isCompiled()) return;

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
            if (bytes > STREAM_CAPACITY) {
                if (!renderFailedLogged) {
                    renderFailedLogged = true;
                    WindSwayMod.trace("batch exceeds stream capacity: " + bytes);
                }
                return;
            }

            // Scene ortho on the Core stacks is already the right MVP.
            // setDirty before each set: the model pipeline writes depth
            // state through raw GL11 behind the trackers, an elided set can
            // leave the real mask on — the batch would then write stalk
            // depth and punch holes into fences drawn after it.
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
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, STREAM_CAPACITY, GL15.GL_STREAM_DRAW);
            } else {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, streamVbo);
            }
            if (streamOffset + bytes > STREAM_CAPACITY) {
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, STREAM_CAPACITY, GL15.GL_STREAM_DRAW);
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
                    s.depth.bind();
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
            if (!firstBatchLogged) {
                firstBatchLogged = true;
                WindSwayMod.trace("first grass batch rendered (" + this.quads.size() + " quads)");
            }
        } catch (Throwable t) {
            if (!renderFailedLogged) {
                renderFailedLogged = true;
                WindSwayMod.trace("grass batch render failed", t);
            }
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
