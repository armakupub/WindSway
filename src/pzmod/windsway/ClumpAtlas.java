package pzmod.windsway;

import java.awt.image.BufferedImage;
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

import zombie.core.textures.Texture;

// Clump maps of the mass-painted crowns (linden, yellowwood), built offline
// by Sandbox/pz-tree-inventory/clump_maps.py: per texel the core weight, the
// offset to the painted clump's centroid (px, 128 = 0) and the clump's nod
// amplitude. The fragment shader moves each clump as one piece. The index
// region covers the sprite's trimmed rect; treetops and late-summer sprites
// point into their partner's map. Loaded once per session on the render
// thread.
final class ClumpAtlas {

    static int textureId;

    private static boolean inited;
    private static boolean ok;
    // name -> {u0, v0, u1, v1, median clump diameter px}
    private static HashMap<String, float[]> byName;
    private static final IdentityHashMap<Texture, float[]> cache = new IdentityHashMap<>(256);
    private static final float[] MISS = new float[0];

    private ClumpAtlas() {
    }

    // Render thread, per world.
    static void clearCache() {
        cache.clear();
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
            try (InputStream in = ClumpAtlas.class.getResourceAsStream("clump_maps.png")) {
                if (in == null) throw new IllegalStateException("clump_maps.png missing from jar");
                img = ImageIO.read(in);
            }
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = BufferUtils.createByteBuffer(w * h * 4);
            for (int p : argb) {
                buf.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
            }
            buf.flip();
            HashMap<String, float[]> map = new HashMap<>(128);
            try (InputStream in = ClumpAtlas.class.getResourceAsStream("clump_maps.txt")) {
                if (in == null) throw new IllegalStateException("clump_maps.txt missing from jar");
                BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                int skipped = 0;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.charAt(0) == '#') continue;
                    try {
                        String[] f = line.split(" ");
                        if (f.length < 6) {
                            ++skipped;
                            continue;
                        }
                        float x = Float.parseFloat(f[1]);
                        float y = Float.parseFloat(f[2]);
                        float cw = Float.parseFloat(f[3]);
                        float ch = Float.parseFloat(f[4]);
                        float cell = Float.parseFloat(f[5]);
                        map.put(f[0], new float[]{x / w, y / h, (x + cw) / w, (y + ch) / h, cell});
                    } catch (RuntimeException e) {
                        ++skipped;
                    }
                }
                if (skipped > 0) WindSwayMod.trace("clump maps: " + skipped + " bad lines skipped");
            }
            int id = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            // The bind cache may hold the id of whatever was on this unit.
            Texture.lastTextureID = 0;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            byName = map;
            textureId = id;
            ok = true;
            WindSwayMod.trace("clump atlas " + w + "x" + h + ", " + map.size() + " sprites");
        } catch (Throwable t) {
            WindSwayMod.trace("clump atlas unavailable: " + t, t);
            ok = false;
        }
    }

    // Render thread (build phase). Null when the sprite has no clump map.
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
