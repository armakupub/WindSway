package pzmod.windsway;

// Leaf traits per vanilla species (NatureTrees order): flutter amplitude
// and rate factors from petiole length over blade length and blade
// texture (leathery leaves never reach flutter onset), blade length in cm
// for the leaf cell. Evergreens keep the conifer needle tuning (factor 1).
final class TreeSpecies {

    static final int COUNT = 11;

    // holly, hemlock, pine, birch, hawthorn, dogwood, silverbell,
    // yellowwood, redbud, maple, linden
    // Birch: the sprite's 4 px ovals cannot carry its flutter, that goes
    // to the flicker; the offset stays small.
    // Hawthorn: the leathery gloss put it near zero, but the dense fine
    // foliage read as dead in game; tuned toward dogwood's look.
    // Redbud: its top flutter amplitude on 10 cm cells read as heat
    // shimmer on the thin upward stems; the motion sits in the lobes now.
    static final double[] leafAmp = {0.7, 1.0, 1.0, 0.9, 0.75, 0.75, 0.8, 0.9, 0.7, 1.1, 0.8};
    static final double[] leafRate = {1.0, 1.0, 1.0, 1.3, 1.0, 1.0, 0.9, 0.9, 1.0, 1.0, 0.6};
    // Lobe factors on the class values: birch's narrow crown of fine paint
    // and white stems turns to jelly on the fine class's 0.7 cells; redbud's
    // upward branches rock as units on bigger cells the same way.
    static final double[] lobe = {1.0, 1.0, 1.0, 0.6, 1.0, 1.3, 1.0, 1.0, 1.3, 1.0, 1.0};
    static final double[] lobeCell = {1.0, 1.0, 1.0, 1.7, 1.0, 1.0, 1.0, 1.0, 1.6, 1.0, 1.0};
    static final double[] leafCm = {5.0, 8.0, 8.0, 6.0, 8.0, 8.0, 9.0, 7.0, 10.0, 9.0, 13.0};
    // Underside contrast: how much a turning leaf lightens (silvery linden,
    // pale birch, glaucous maple; leathery leaves never turn).
    static final double[] flick = {0.0, 0.0, 0.0, 1.0, 0.6, 0.6, 0.6, 0.6, 0.7, 0.8, 0.5};
    // Painted leaf size on the XXL sprite in px, by eye; regular and JUMBO
    // paint at about half that.
    static final double[] paintPx = {5.0, 0.0, 0.0, 4.0, 5.0, 6.0, 3.0, 4.0, 4.0, 8.0, 4.0};

    private TreeSpecies() {
    }

    static double flick(int s) {
        return s >= 0 && s < COUNT ? flick[s] : 0.6;
    }

    static double paintPx(int s) {
        return s >= 0 && s < COUNT ? paintPx[s] : 4.0;
    }

    static double lobe(int s) {
        return s >= 0 && s < COUNT ? lobe[s] : 1.0;
    }

    static double lobeCell(int s) {
        return s >= 0 && s < COUNT ? lobeCell[s] : 1.0;
    }

    static void setLobes(int s, double lobeF, double cellF) {
        if (s < 0 || s >= COUNT) return;
        lobe[s] = lobeF;
        lobeCell[s] = cellF;
    }

    static double leafAmp(int s) {
        return s >= 0 && s < COUNT ? leafAmp[s] : 1.0;
    }

    static double leafRate(int s) {
        return s >= 0 && s < COUNT ? leafRate[s] : 1.0;
    }

    // Unknown species: the global leaf cell in cm.
    static double leafCm(int s) {
        return s >= 0 && s < COUNT ? leafCm[s] : TreeRenderer.leafCell / TreeRenderer.PX_PER_CM;
    }

    static void set(int s, double amp, double rate, double cm, double flickF, double paint) {
        if (s < 0 || s >= COUNT) return;
        leafAmp[s] = amp;
        leafRate[s] = rate;
        leafCm[s] = cm;
        flick[s] = flickF;
        paintPx[s] = paint;
    }
}
