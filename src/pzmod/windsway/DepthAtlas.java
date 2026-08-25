package pzmod.windsway;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjglx.opengl.Display;

import zombie.core.opengl.RenderThread;
import zombie.core.textures.Texture;
import zombie.core.textures.TextureID;
import zombie.iso.IsoCamera;

// One GL texture per tile depth map in the engine (TileDepthTexture), so a
// grass batch ran out of depth slots after eight quads. Each map seen is
// copied into this atlas on the GPU once the engine's upload has run (it
// sits on the render thread's invoke queue, outside the frame); an early
// copy freezes an empty cell for the session.
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
        boolean uploaded;
        boolean copied;
        TextureID key;
        Texture source;
        int lastUsed;
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
    private static Cell[] slots;
    // Frame clock for the cells' recency (the game-thread counter read here
    // is off by a frame or two, which is fine for an age).
    private static int tick;
    private static int lastFrameCount = Integer.MIN_VALUE;
    // A cell must sit unused this long before it is recycled: a working set
    // larger than the atlas then overflows onto the depth slots instead of
    // rotating through the copies every frame.
    private static final int EVICT_AGE = 30;

    static volatile int diagCopies;
    static volatile int diagCells;
    static volatile int diagCapacity;
    static volatile int diagEvictions;

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

    // Game thread. A copy or init failure parks the atlas; try again per
    // new world.
    static void rearm() {
        if (inited && mode == MODE_OFF && modeWanted != MODE_OFF) {
            reinit = true;
        }
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
            ++tick;
        }
        if (pending.isEmpty()) return;
        for (int i = pending.size() - 1; i >= 0; --i) {
            Cell c = pending.get(i);
            if (!c.uploaded) continue;
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
            if (used >= cols * rows) {
                c = evict();
                if (c == null) return null;
            } else {
                c = new Cell();
                c.cx = used % cols;
                c.cy = used / cols;
                c.u0 = (float) (c.cx * pitchX()) / size;
                c.v0 = (float) (c.cy * pitchY()) / size;
                c.su = (float) cellW / size;
                c.sv = (float) cellH / size;
                slots[used] = c;
                ++used;
                diagCells = used;
            }
            c.key = id;
            c.source = depthTex;
            c.srcId = -1;
            c.lastUsed = tick;
            cells.put(id, c);
            queue(c);
            return null;
        }
        c.lastUsed = tick;
        if (!c.copied) return null;
        if (c.srcId != id.getID()) {
            queue(c);
            return null;
        }
        return c;
    }

    // Least recently used copied cell past the age.
    private static Cell evict() {
        Cell best = null;
        for (int i = 0; i < used; ++i) {
            Cell c = slots[i];
            if (!c.copied || tick - c.lastUsed < EVICT_AGE) continue;
            if (best == null || c.lastUsed < best.lastUsed) best = c;
        }
        if (best == null) return null;
        cells.remove(best.key);
        ++diagEvictions;
        return best;
    }

    // The engine queued the map's upload from the game thread before this
    // frame was handed over; the marker lands behind it in the same FIFO.
    private static void queue(Cell c) {
        c.copied = false;
        c.uploaded = false;
        pending.add(c);
        RenderThread.queueInvokeOnRenderContext(() -> c.uploaded = true);
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
            // Every TilesetDepthTexture is built 2x, whatever tileScale says.
            cellW = 128;
            cellH = 256;
            cols = size / pitchX();
            rows = size / pitchY();
            texId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            // Unsized GL_RED like the engine's maps (copy_image needs one format
            // class). No data: a cell is only sampled once its copy overwrote it.
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RED, size, size, 0, GL11.GL_RED, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
            // The batch may return before it invalidates the bind cache.
            Texture.lastTextureID = 0;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            if (m == MODE_BLIT) {
                fboRead = GL30.glGenFramebuffers();
                fboDraw = GL30.glGenFramebuffers();
            }
            mode = m;
            slots = new Cell[cols * rows];
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
        slots = null;
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
