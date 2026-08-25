package pzmod.windsway;

import java.nio.ByteBuffer;
import java.util.List;

import zombie.core.textures.ImageData;
import zombie.iso.IsoCell;
import zombie.iso.IsoWorld;

// The see-through hole: vanilla stamps the alpha of two PNGs (player mask,
// aim cursor) with stencil ref 128, but isInStencil picks the trees by a
// rect about thirty times the stamp's area. Stamp bbox read once from the
// PNGs.
final class StencilHole {

    private static final class Inset {
        int w;
        int h;
        float x0;
        float y0;
        float x1;
        float y1;
    }

    private static final String[] FILES = {
        "media/mask_transparency_player.png",
        "media/mask_transparency_cursor.png",
    };
    private static Inset[] insets;
    private static boolean loaded;

    static void load() {
        if (loaded) return;
        loaded = true;
        Inset[] r = new Inset[FILES.length];
        for (int i = 0; i < FILES.length; ++i) {
            try {
                r[i] = read(FILES[i]);
            } catch (Throwable t) {
                WindSwayMod.trace("stencil mask " + FILES[i] + " not read, using the full rect: " + t);
            }
        }
        insets = r;
    }

    private static Inset read(String file) throws Exception {
        ImageData img = new ImageData(file);
        try {
            int w = img.getWidth();
            int h = img.getHeight();
            int stride = img.getWidthHW() * 4;
            ByteBuffer buf = img.getData().getBuffer();
            int minX = w;
            int minY = h;
            int maxX = -1;
            int maxY = -1;
            for (int y = 0; y < h; ++y) {
                int row = y * stride;
                for (int x = 0; x < w; ++x) {
                    if ((buf.get(row + x * 4 + 3) & 0xFF) > 25) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < 0) throw new IllegalStateException("mask is empty");
            Inset in = new Inset();
            in.w = w;
            in.h = h;
            in.x0 = minX / (float) w;
            in.x1 = (maxX + 1) / (float) w;
            in.y0 = minY / (float) h;
            in.y1 = (maxY + 1) / (float) h;
            WindSwayMod.trace(String.format("stencil mask %s: %dx%d, stamp %d..%d x %d..%d", file, w, h, minX, maxX, minY, maxY));
            return in;
        } finally {
            img.dispose();
        }
    }

    // Game thread. Stamped rects of this frame as x1, y1, x2, y2 in offscreen
    // pixels; returns the count.
    static int rects(float[] out) {
        if (!loaded) load();
        IsoCell cell = IsoWorld.instance != null ? IsoWorld.instance.currentCell : null;
        if (cell == null) return 0;
        List<IsoCell.StencilArea> areas = cell.getStencilAreas();
        int n = 0;
        for (int i = 0; i < areas.size() && n * 4 + 3 < out.length; ++i) {
            IsoCell.StencilArea a = areas.get(i);
            float x1 = a.stencilX1();
            float y1 = a.stencilY1();
            float x2 = a.stencilX2();
            float y2 = a.stencilY2();
            Inset in = insetFor(a.texWidth(), a.texHeight());
            if (in != null) {
                float w = x2 - x1;
                float h = y2 - y1;
                x2 = x1 + in.x1 * w;
                x1 = x1 + in.x0 * w;
                y2 = y1 + in.y1 * h;
                y1 = y1 + in.y0 * h;
            }
            out[n * 4] = x1;
            out[n * 4 + 1] = y1;
            out[n * 4 + 2] = x2;
            out[n * 4 + 3] = y2;
            ++n;
        }
        return n;
    }

    private static Inset insetFor(int texW, int texH) {
        if (insets == null) return null;
        for (int i = 0; i < insets.length; ++i) {
            Inset in = insets[i];
            if (in != null && in.w == texW && in.h == texH) return in;
        }
        return null;
    }
}
