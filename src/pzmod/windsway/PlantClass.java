package pzmod.windsway;

import java.util.IdentityHashMap;

import zombie.iso.sprite.IsoSprite;

// Motion classes for the wind flora, from the sheet name and what the
// erosion categories plant where: factors on the common plant knobs
// (lean, period, bend profile, blade spread) and the leaf flutter layer
// per part. Unknown sprites (mods) are blades, or a crown when isBush.
final class PlantClass {

    // Grass tufts, dry blades, wheat: the column model as it was.
    static final int BLADES = 0;
    // Broadleaf weeds, seedlings, bean plants: stiffer stems, leaves flutter.
    static final int LEAFY = 1;
    // Ferns and rosettes: the plant stands, the fronds bob.
    static final int ROSETTE = 2;
    // Dead stalks, bare bush bases in winter: tips only, quick and small.
    static final int TWIG = 3;
    // Leafy bush crowns and berry bushes: block bend, tree-like leaves.
    static final int CROWN = 4;
    // Dead corn and beans: dry stems sway, the ground tangle rests.
    static final int STALK = 5;
    // Flower spikes and standing stem bundles over the weeds: the stems
    // ride the object's bend, no leaf warp on them.
    static final int FLOWER = 6;
    // Spring and autumn bush crowns: isolated tufts (4-21 % of the summer
    // mass) on visible bare wood. The body swings near bare, each tuft
    // plays on its own; no canopy light on lone tufts.
    static final int SPARSE = 7;
    static final int COUNT = 8;

    static final double[] lean = {1.0, 0.6, 0.5, 0.25, 0.4, 0.55, 0.5, 0.3};
    static final double[] period = {1.0, 1.2, 1.3, 0.65, 1.3, 1.1, 1.0, 0.8};
    static final double[] bendPow = {1.0, 0.8, 0.65, 1.33, 0.8, 1.35, 1.0, 1.15};
    static final double[] bladeVar = {1.0, 0.5, 0.5, 1.0, 0.3, 1.0, 0.8, 0.3};
    static final double[] leafAmp = {0.0, 1.0, 1.0, 0.0, 1.0, 0.0, 0.0, 1.1};
    static final double[] leafX = {1.0, 1.0, 0.5, 1.0, 1.0, 1.0, 1.0, 1.0};
    static final double[] leafY = {0.3, 0.3, 1.0, 0.3, 0.3, 0.5, 0.3, 0.3};
    static final double[] leafCell = {6.0, 5.0, 8.0, 6.0, 6.0, 10.0, 8.0, 7.0};
    static final double[] leafRate = {1.0, 1.0, 0.5, 1.0, 0.7, 0.6, 1.0, 0.85};
    // Tip physics (phase lead, fast share, snap lift) and gust sheen: blades
    // in full, ferns none (the fronds bob on their own), stiff parts little.
    static final double[] tip = {1.0, 0.5, 0.0, 0.3, 0.3, 0.5, 0.6, 0.3};
    static final double[] sheen = {1.0, 0.5, 0.3, 0.0, 0.3, 0.5, 0.3, 0.1};
    // Body (woody classes): share of the stem-and-block profile (stems
    // pivot at the foot, the crown rides above the knee as one piece)
    // against the bend exponent; factor on the sine swing; factor on the
    // lean inertia share; factor on the crown lobes (setPlantLobe).
    static final double[] block = {0.0, 0.0, 0.0, 0.5, 1.0, 0.0, 0.0, 0.6};
    static final double[] swing = {1.0, 1.0, 1.0, 0.7, 0.5, 1.0, 1.0, 0.65};
    static final double[] inertia = {1.0, 1.0, 1.0, 1.5, 2.0, 1.5, 1.2, 1.6};
    static final double[] lobe = {0.0, 0.0, 0.0, 0.5, 1.0, 0.0, 0.0, 0.4};
    // Leaf look as on the trees: cluster cell in px (0 = the leaf
    // cell; bush crowns paint 4-8 px leaf shapes that move as themselves, a
    // 14 px cluster warped whole regions), flicker contrast (brightness on
    // leaf-sized cells, 0 = none), share of the drifting mask patches.
    static final double[] cluster = {0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0};
    static final double[] flick = {0.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0};
    static final double[] mask = {0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0};
    // Steady share: scales the constant floors (standing lean, swing rest,
    // leaf and lobe base, wind excitation), so a low class moves on the
    // fronts alone, as the grass does.
    static final double[] steady = {1.0, 0.35, 0.3, 0.25, 0.3, 0.25, 0.35, 0.25};

    // Game thread: parts captured per class since the last log line.
    static final int[] diag = new int[COUNT];

    private static final IdentityHashMap<IsoSprite, Integer> cache = new IdentityHashMap<>(2048);
    private static final IdentityHashMap<IsoSprite, Boolean> dryCache = new IdentityHashMap<>(2048);

    // Game thread. Dead or dry flora: the tan and orange grass sets
    // (e_newgrass season 3 and 4), dry blades and dead stalks, dry weeds,
    // bare bush bases, dead corn.
    static boolean dry(IsoSprite spr) {
        Boolean d = dryCache.get(spr);
        if (d != null) return d;
        boolean dry = dryOf(spr);
        dryCache.put(spr, dry);
        return dry;
    }

    private static boolean dryOf(IsoSprite spr) {
        String name = spr.name;
        if (name == null) return false;
        int us = name.lastIndexOf('_');
        if (us <= 0) return false;
        int idx;
        try {
            idx = Integer.parseInt(name.substring(us + 1));
        } catch (NumberFormatException e) {
            return false;
        }
        switch (name.substring(0, us)) {
            case "e_newgrass_1":
                return idx >= 72;
            case "d_generic_1":
                return idx < 32;
            case "d_plants_1":
                return idx >= 8 && idx < 16;
            case "f_bushes_1":
                return idx < 32;
            case "vegetation_farm_01":
                return idx >= 32 && idx < 40;
            default:
                return false;
        }
    }

    private PlantClass() {
    }

    // Game thread.
    static int of(IsoSprite spr) {
        Integer c = cache.get(spr);
        if (c != null) return c;
        int cls = classOf(spr);
        cache.put(spr, cls);
        return cls;
    }

    private static int classOf(IsoSprite spr) {
        String name = spr.name;
        if (name == null) return spr.isBush ? CROWN : BLADES;
        int us = name.lastIndexOf('_');
        int idx = -1;
        String sheet = name;
        if (us > 0) {
            try {
                idx = Integer.parseInt(name.substring(us + 1));
                sheet = name.substring(0, us);
            } catch (NumberFormatException ignored) {
            }
        }
        switch (sheet) {
            case "e_newgrass_1":
            case "blends_grassoverlays_01":
            case "d_streetcracks_1":
                return BLADES;
            case "d_generic_1":
                // NatureGeneric: 0-15 dry blades, 16-31 their grown dead stalks,
                // 48-55 / 80-87 ferns (+16 snow).
                if (idx >= 16 && idx <= 31) return TWIG;
                if ((idx >= 48 && idx <= 71) || (idx >= 80 && idx <= 103)) return ROSETTE;
                return BLADES;
            case "d_plants_1":
                // Standing stem bundles (18/19) with their dot blooms (26/27),
                // and the spike and arc blooms that rise over the foliage;
                // embedded flower heads stay with the leaves.
                if (idx == 18 || idx == 19 || idx == 26 || idx == 27) return FLOWER;
                if (idx == 31 || (idx >= 42 && idx <= 47) || idx == 60 || idx == 63) return FLOWER;
                return LEAFY;
            case "f_bushes_1":
                // NatureBush: 0-15 bases, 16-31 snow bases, 32-63 spring and
                // autumn crowns, 64-79/96-111 summer crowns, 80-95/112-127
                // blooms. Only the summer crown is a closed canopy; spring
                // and autumn are scattered tufts. Blooms stay crown: they are
                // embedded in the summer foliage and must ride its leaf
                // field. The St. John's wort flower plumes rise out of the
                // crown.
                if (idx == 95 || idx == 127) return FLOWER;
                if (idx >= 0 && idx < 32) return TWIG;
                if (idx >= 32 && idx < 64) return SPARSE;
                return CROWN;
            case "vegetation_foliage_01":
                return CROWN;
            case "vegetation_farm_01":
                if (idx >= 32 && idx <= 47) return STALK;
                return BLADES;
            default:
                return spr.isBush ? CROWN : BLADES;
        }
    }

    static void set(int c, double leanF, double periodF, double bendPowF, double bladeVarF,
                    double leafAmpF, double leafXF, double leafYF, double leafCellPx, double leafRateF) {
        if (c < 0 || c >= COUNT) return;
        lean[c] = leanF;
        period[c] = periodF;
        bendPow[c] = bendPowF;
        bladeVar[c] = bladeVarF;
        leafAmp[c] = leafAmpF;
        leafX[c] = leafXF;
        leafY[c] = leafYF;
        leafCell[c] = leafCellPx;
        leafRate[c] = leafRateF;
    }

    static void setTip(int c, double tipF, double sheenF) {
        if (c < 0 || c >= COUNT) return;
        tip[c] = tipF;
        sheen[c] = sheenF;
    }

    static void setLeafLook(int c, double clusterPx, double flickF, double maskF) {
        if (c < 0 || c >= COUNT) return;
        cluster[c] = Math.max(0.0, clusterPx);
        flick[c] = Math.max(0.0, flickF);
        mask[c] = Math.max(0.0, Math.min(1.0, maskF));
    }

    static void setBody(int c, double blockF, double swingF, double inertiaF, double lobeF) {
        if (c < 0 || c >= COUNT) return;
        block[c] = Math.max(0.0, Math.min(1.0, blockF));
        swing[c] = Math.max(0.0, swingF);
        inertia[c] = Math.max(0.0, inertiaF);
        lobe[c] = Math.max(0.0, lobeF);
    }

    static void setSteady(int c, double steadyF) {
        if (c < 0 || c >= COUNT) return;
        steady[c] = Math.max(0.0, Math.min(1.0, steadyF));
    }

    static String name(int c) {
        switch (c) {
            case BLADES: return "blades";
            case LEAFY: return "leafy";
            case ROSETTE: return "rosette";
            case TWIG: return "twig";
            case CROWN: return "crown";
            case STALK: return "stalk";
            case FLOWER: return "flower";
            case SPARSE: return "sparse";
            default: return "?";
        }
    }

    static String diagLine() {
        StringBuilder sb = new StringBuilder("plant parts:");
        for (int c = 0; c < COUNT; ++c) {
            sb.append(' ').append(name(c)).append('=').append(diag[c]);
            diag[c] = 0;
        }
        return sb.toString();
    }
}
