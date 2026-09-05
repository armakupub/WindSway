package pzmod.windsway;

// Motion classes from the sprite inventory: factors on the common tree
// model, indexed by class. The literature's crown contrasts read as straws
// next to statues in game; class contrast stays within +-15 %, the species
// show in the twig and leaf layers.
final class TreeClass {

    // Holly: dense stiff evergreen cone, tiny leathery leaves.
    static final int CONE = 0;
    // Hemlock: hanging branch tips; the crown leans as one, no tier bob.
    static final int PENDULOUS = 1;
    // Pine: long bare trunk, open stiff layers, needle tufts.
    static final int PINE = 2;
    // Birch, redbud, silverbell: thin stems, small leaves on fine twigs.
    static final int FINE = 3;
    // Maple, linden, yellowwood: broad dense crown painted as leaf masses.
    static final int DENSE = 4;
    // Hawthorn, dogwood: short, dense, stiff, dogwood in layers.
    static final int UNDERSTORY = 5;
    static final int COUNT = 6;

    // NatureTrees order: holly, hemlock, pine, birch, hawthorn, dogwood,
    // silverbell, yellowwood, redbud, maple, linden.
    private static final int[] BY_SPECIES = {CONE, PENDULOUS, PINE, FINE, UNDERSTORY, UNDERSTORY, FINE, DENSE,
            FINE, DENSE, DENSE};

    static final double[] lean = {0.85, 1.0, 0.9, 1.0, 1.0, 0.85};
    static final double[] period = {1.0, 1.0, 1.1, 1.0, 1.0, 1.0};
    static final double[] periodExp = {0.35, 0.35, 0.35, 0.35, 0.35, 0.35};
    static final double[] ring = {0.9, 1.0, 1.0, 1.0, 1.0, 0.9};
    static final double[] lobe = {0.8, 0.0, 0.6, 1.2, 1.0, 0.5};
    static final double[] lobeCell = {1.0, 1.0, 1.6, 0.7, 1.2, 1.3};
    static final double[] lobeY = {0.8, 1.2, 0.8, 1.0, 1.0, 0.8};
    static final double[] leafAmp = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0};
    static final double[] leafRate = {1.0, 1.0, 1.0, 1.0, 1.0, 1.0};

    private TreeClass() {
    }

    static int ofSpecies(int species) {
        return species >= 0 && species < BY_SPECIES.length ? BY_SPECIES[species] : DENSE;
    }

    static void set(int c, double leanF, double periodF, double periodExpF, double ringF, double lobeF,
                    double lobeCellF, double lobeYF, double leafAmpF, double leafRateF) {
        if (c < 0 || c >= COUNT) return;
        lean[c] = leanF;
        period[c] = periodF;
        periodExp[c] = periodExpF;
        ring[c] = ringF;
        lobe[c] = lobeF;
        lobeCell[c] = lobeCellF;
        lobeY[c] = lobeYF;
        leafAmp[c] = leafAmpF;
        leafRate[c] = leafRateF;
    }

    static String name(int c) {
        switch (c) {
            case CONE: return "cone";
            case PENDULOUS: return "pendulous";
            case PINE: return "pine";
            case FINE: return "fine";
            case DENSE: return "dense";
            case UNDERSTORY: return "understory";
            default: return "?";
        }
    }
}
