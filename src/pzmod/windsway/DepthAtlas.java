package pzmod.windsway;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;

import zombie.core.Core;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.iso.IsoCamera;

// One GL texture per tile depth map in the engine (TileDepthTexture), so a
// grass batch ran out of depth slots after eight quads. Each map seen is
// copied into this atlas on the GPU, two frames later: the engine's upload
// is queued on the render thread and an early copy freezes an empty cell.
final class DepthAtlas {

    static final int MODE_AUTO = -1;
    static final int MODE_OFF = 0;
    static final int MODE_COPY_IMAGE = 1;
    static final int MODE_BLIT = 2;

    static volatile int modeWanted = MODE_AUTO;
    static volatile int sizeWanted = 4096;
    static volatile boolean reinit = false;

    static final class Cell {
        int cx;
        int cy;
        int srcId = -1;
        int frameAssigned;
        boolean copied;
        Texture source;
        // Atlas uv = u0 + tile uv * su.
        float u0;
        float v0;
        float su;
        float sv;
    }

    private static int mode = MODE_OFF;
    private static boolean inited;
    private static int texId;
    private static int size;
    private static int cellW;
    private static int cellH;
    private static int cols;
    private static int rows;
    private static int used;
    private static int fboRead;
    private static int fboDraw;
    private static final IdentityHashMap<TextureID, Cell> cells = new IdentityHashMap<>(512);
    private static final ArrayList<Cell> pending = new ArrayList<>();
    private static int frames;
    private static int lastFrameCount = Integer.MIN_VALUE;
    private static int lastCheckFrame = -1;

    static volatile int diagCopies;
    static volatile int diagCells;
    static volatile int diagCapacity;

    // One-texel gutter between cells.
    private static int pitchX() {
        return cellW + 1;
    }

    private static int pitchY() {
        return cellH + 1;
    }

    static int textureId() {
        return texId;
    }

    static boolean active() {
        return mode != MODE_OFF && texId != 0 && modeWanted != MODE_OFF;
    }

    static void beginBatch() {
        if (reinit) {
            reinit = false;
            destroy();
        }
        if (!inited) init();
        if (mode == MODE_OFF) return;
        int fc = IsoCamera.frameState.frameCount;
        if (fc != lastFrameCount) {
            lastFrameCount = fc;
            ++frames;
            // A lost GL context (video mode change) leaves a dead id.
            if (frames - lastCheckFrame >= 120) {
                lastCheckFrame = frames;
                if (!GL11.glIsTexture(texId)) {
                    WindSwayMod.trace("depth atlas texture lost, rebuilding");
                    destroy();
                    init();
                    if (mode == MODE_OFF) return;
                }
            }
        }
        if (pending.isEmpty()) return;
        for (int i = pending.size() - 1; i >= 0; --i) {
            Cell c = pending.get(i);
            if (frames - c.frameAssigned < 2) continue;
            TextureID id = c.source.getTextureId();
            int src = id != null ? id.getID() : -1;
            if (src == -1) continue;
            if (copy(src, c)) {
                c.srcId = src;
                c.copied = true;
                ++diagCopies;
            }
            pending.remove(i);
        }
    }

    // Null until the copy is done.
    static Cell cellFor(Texture depthTex) {
        if (mode == MODE_OFF || modeWanted == MODE_OFF) return null;
        TextureID id = depthTex.getTextureId();
        if (id == null) return null;
        Cell c = cells.get(id);
        if (c == null) {
            if (depthTex.getWidthHW() != cellW || depthTex.getHeightHW() != cellH) return null;
            if (used >= cols * rows) return null;
            c = new Cell();
            c.cx = used % cols;
            c.cy = used / cols;
            ++used;
            diagCells = used;
            c.source = depthTex;
            c.frameAssigned = frames;
            c.u0 = (float) (c.cx * pitchX()) / size;
            c.v0 = (float) (c.cy * pitchY()) / size;
            c.su = (float) cellW / size;
            c.sv = (float) cellH / size;
            cells.put(id, c);
            pending.add(c);
            return null;
        }
        if (!c.copied) return null;
        if (c.srcId != id.getID()) {
            c.copied = false;
            c.frameAssigned = frames;
            pending.add(c);
            return null;
        }
        return c;
    }

    private static void init() {
        inited = true;
        mode = MODE_OFF;
        try {
            GLCapabilities caps = Display.capabilities;
            int wanted = modeWanted;
            boolean copyImage = caps.OpenGL43 || caps.GL_ARB_copy_image;
            boolean blit = caps.OpenGL30 || caps.GL_ARB_framebuffer_object;
            int m;
            if (wanted == MODE_COPY_IMAGE && copyImage) m = MODE_COPY_IMAGE;
            else if (wanted == MODE_BLIT && blit) m = MODE_BLIT;
            else if (wanted == MODE_AUTO || wanted == MODE_COPY_IMAGE || wanted == MODE_BLIT) {
                m = copyImage ? MODE_COPY_IMAGE : blit ? MODE_BLIT : MODE_OFF;
            } else m = MODE_OFF;
            if (m == MODE_OFF) {
                WindSwayMod.trace("depth atlas off (no copy_image, no framebuffer blit)");
                return;
            }
            int maxSize = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            size = Math.min(Math.max(1024, sizeWanted), maxSize);
            cellW = 64 * Core.tileScale;
            cellH = 128 * Core.tileScale;
            cols = size / pitchX();
            rows = size / pitchY();
            texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // Unsized GL_RED like the engine's maps (copy_image needs one format
            // class); a zero depth texel discards.
            ByteBuffer zero = BufferUtils.createByteBuffer(size * size);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, size, size, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, zero);
            // The batch may return before it invalidates the bind cache.
            Texture.lastTextureID = 0;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (m == MODE_BLIT) {
                fboRead = GL30.glGenFramebuffers();
                fboDraw = GL30.glGenFramebuffers();
            }
            mode = m;
            diagCapacity = cols * rows;
            diagCells = 0;
            WindSwayMod.trace("depth atlas " + size + "x" + size + ", " + cols * rows + " cells of " + cellW + "x" + cellH
                    + ", " + (m == MODE_COPY_IMAGE ? "copy_image" : "framebuffer blit"));
        } catch (Throwable t) {
            WindSwayMod.trace("depth atlas disabled: " + t, t);
            destroy();
            inited = true;
            mode = MODE_OFF;
        }
    }

    private static void destroy() {
        if (texId != 0) {
            GL11.glDeleteTextures(texId);
            texId = 0;
        }
        if (fboRead != 0) {
            GL30.glDeleteFramebuffers(fboRead);
            fboRead = 0;
        }
        if (fboDraw != 0) {
            GL30.glDeleteFramebuffers(fboDraw);
            fboDraw = 0;
        }
        cells.clear();
        pending.clear();
        used = 0;
        inited = false;
        mode = MODE_OFF;
    }

    private static boolean copy(int src, Cell c) {
        int x = c.cx * pitchX();
        int y = c.cy * pitchY();
        try {
            if (mode == MODE_COPY_IMAGE) {
                GL43.glCopyImageSubData(src, GL11.GL_TEXTURE_2D, 0, 0, 0, 0,
                        texId, GL11.GL_TEXTURE_2D, 0, x, y, 0, cellW, cellH, 1);
                return true;
            }
            int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboRead);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, src, 0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboDraw);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
            GL30.glBlitFramebuffer(0, 0, cellW, cellH, x, y, x + cellW, y + cellH, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, 0, 0);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            return true;
        } catch (Throwable t) {
            WindSwayMod.trace("depth atlas copy failed, atlas off: " + t, t);
            mode = MODE_OFF;
            return false;
        }
    }
}
