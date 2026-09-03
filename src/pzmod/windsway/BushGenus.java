package pzmod.windsway;

import java.util.IdentityHashMap;

import zombie.iso.sprite.IsoSprite;

// Bush genus from the f_bushes_1 crown or bloom index: factors on the
// crown class values. A hydrangea catches the gust and swings after it,
// a small-leaved dome barely bends but its leaves play, a ninebark spray
// whips from the foot like grass, late.
final class BushGenus {

    // Large soft leaves, heavy mass: Beautyberry, Wild hydrangea.
    static final int LARGE = 0;
    // Dense dome of small leaves: Spicebush, Azalea, Viburnum, Chokeberry.
    static final int DOME = 1;
    // Upright twig sprays, sparse: Ninebark, St. John's wort.
    static final int SPRAY = 2;
    // Loose medium foliage, the crown class as it is: Blueberry,
    // Blackberry, New jersey tea.
    static final int LOOSE = 3;
    static final int COUNT = 4;

    // Species 0-15 (NatureBush order): Spicebush, Ninebark x2, Blueberry,
    // Blackberry, Piedmont azalea x2, Arrowwood viburnum, Red chokeberry x2,
    // Beautyberry, New jersey tea x2, Wild hydrangea x2, St. John's wort.
    static final int[] GENUS = {DOME, SPRAY, SPRAY, LOOSE, LOOSE, DOME, DOME, DOME,
                                DOME, DOME, LARGE, LOOSE, LOOSE, LARGE, LARGE, SPRAY};

    static final double[] lean = {1.15, 0.6, 0.85, 1.0};
    static final double[] period = {1.15, 0.9, 1.1, 1.0};
    static final double[] swing = {1.3, 0.8, 1.15, 1.0};
    static final double[] steady = {1.3, 0.8, 0.55, 1.0};
    static final double[] leafAmp = {1.15, 1.15, 0.6, 1.0};
    static final double[] leafCell = {1.4, 0.7, 0.7, 1.0};
    static final double[] flick = {1.0, 1.1, 0.5, 1.0};
    static final double[] mask = {1.0, 0.7, 0.5, 1.0};
    static final double[] block = {1.0, 1.0, 0.35, 1.0};
    static final double[] bendPow = {1.0, 1.0, 1.3, 1.0};

    private static final IdentityHashMap<IsoSprite, Integer> cache = new IdentityHashMap<>(256);

    static void clearCache() {
        cache.clear();
    }

    // Game thread. Genus of a crown or bloom part, -1 when the sprite does
    // not name a species (bare bases are ambiguous: two species share each
    // stem drawing).
    static int of(IsoSprite spr) {
        Integer g = cache.get(spr);
        if (g != null) return g;
        int v = genusOf(spr);
        cache.put(spr, v);
        return v;
    }

    private static int genusOf(IsoSprite spr) {
        String name = spr == null ? null : spr.name;
        if (name == null || !name.startsWith("f_bushes_1_")) return -1;
        int idx;
        try {
            idx = Integer.parseInt(name.substring(11));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (idx < 64 || idx >= 128) return -1;
        return GENUS[idx & 15];
    }

    static void set(int g, double leanF, double periodF, double swingF, double steadyF,
            double leafAmpF, double leafCellF, double flickF, double maskF,
            double blockF, double bendPowF) {
        if (g < 0 || g >= COUNT) return;
        lean[g] = Math.max(0.0, leanF);
        period[g] = Math.max(0.05, periodF);
        swing[g] = Math.max(0.0, swingF);
        steady[g] = Math.max(0.0, steadyF);
        leafAmp[g] = Math.max(0.0, leafAmpF);
        leafCell[g] = Math.max(0.1, leafCellF);
        flick[g] = Math.max(0.0, flickF);
        mask[g] = Math.max(0.0, maskF);
        block[g] = Math.max(0.0, Math.min(3.0, blockF));
        bendPow[g] = Math.max(0.1, bendPowF);
    }
}
