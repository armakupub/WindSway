package pzmod.windsway;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjglx.opengl.Display;

import zombie.core.textures.Texture;

// Per-sprite leaf-material masks (255 = leaf, 0 = painted wood) at quarter
// sprite resolution in one atlas, built offline by
// Sandbox/pz-tree-inventory/leaf_masks.py: the fragment shader taps it once
// so trunks and branches painted into the crown band stop shimmering with
// the leaf flutter. Loaded once per session on the render thread.
final class LeafMaskAtlas {

    static int textureId;
    // Sprite texel -> atlas uv (the masks are quarter resolution).
    static float scaleU;
    static float scaleV;

    private static boolean inited;
    private static boolean ok;
    private static HashMap<String, float[]> byName;
    private static final IdentityHashMap<Texture, float[]> cache = new IdentityHashMap<>(1024);
    private static final float[] MISS = new float[0];

    // Render thread, per world.
    static void clearCache() {
        cache.clear();
    }

    private LeafMaskAtlas() {
    }

    static boolean active() {
        return ok;
    }

    // Render thread.
    static void ensure() {
        if (inited) return;
        inited = true;
        try {
            BufferedImage img;
            try (InputStream in = LeafMaskAtlas.class.getResourceAsStream("leaf_masks.png")) {
                if (in == null) throw new IllegalStateException("leaf_masks.png missing from jar");
                img = ImageIO.read(in);
            }
            int w = img.getWidth();
            int h = img.getHeight();
            byte[] data;
            if (img.getType() == BufferedImage.TYPE_BYTE_GRAY) {
                data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            } else {
                data = new byte[w * h];
                for (int y = 0; y < h; ++y) {
                    for (int x = 0; x < w; ++x) {
                        data[y * w + x] = (byte) (img.getRGB(x, y) & 0xFF);
                    }
                }
            }
            HashMap<String, float[]> map = new HashMap<>(1024);
            try (InputStream in = LeafMaskAtlas.class.getResourceAsStream("leaf_masks.txt")) {
                if (in == null) throw new IllegalStateException("leaf_masks.txt missing from jar");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                int skipped = 0;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    try {
                        String[] f = line.split(" ");
                        if (f.length < 5) {
                            ++skipped;
                            continue;
                        }
                        float x = Float.parseFloat(f[1]);
                        float y = Float.parseFloat(f[2]);
                        float cw = Float.parseFloat(f[3]);
                        float ch = Float.parseFloat(f[4]);
                        map.put(f[0], new float[]{x / w, y / h, (x + cw) / w, (y + ch) / h});
                    } catch (RuntimeException e) {
                        ++skipped;
                    }
                }
                if (skipped > 0) WindSwayMod.trace("leaf masks: " + skipped + " bad lines skipped");
            }
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h);
            buf.put(data).flip();
            int id = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
            boolean rg = Display.capabilities.OpenGL30 || Display.capabilities.GL_ARB_texture_rg;
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, rg ? GL30.GL_R8 : GL11.GL_LUMINANCE8, w, h, 0,
                    rg ? GL11.GL_RED : GL11.GL_LUMINANCE, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            // The bind cache may hold the id of whatever was on this unit.
            Texture.lastTextureID = 0;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            scaleU = 0.25f / w;
            scaleV = 0.25f / h;
            byName = map;
            textureId = id;
            ok = true;
            WindSwayMod.trace("leaf mask atlas " + w + "x" + h + ", " + map.size() + " sprites");
        } catch (Throwable t) {
            WindSwayMod.trace("leaf mask atlas unavailable: " + t, t);
            ok = false;
        }
    }

    // Render thread (build phase). Null when the sprite has no mask.
    static float[] rectFor(Texture tex) {
        if (!ok) return null;
        float[] r = cache.get(tex);
        if (r != null) return r == MISS ? null : r;
        String name = tex.getName();
        r = name != null ? byName.get(name) : null;
        cache.put(tex, r == null ? MISS : r);
        return r;
    }
}
