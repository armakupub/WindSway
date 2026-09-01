package pzmod.windsway;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import zombie.core.textures.Texture;

// Per-sprite silhouette metrics from tools/tree_profiles.py, rows in frame
// pixels, plus what the vanilla sheet name says about the tree: species,
// size family, growth stage and the season of the sheet index.
final class TreeProfile {

    static final int SEASON_BARE = 0;
    static final int SEASON_SNOW = 1;
    static final int SEASON_SPRING = 2;
    static final int SEASON_SUMMER = 3;
    static final int SEASON_LATE = 4;
    static final int SEASON_AUTUMN = 5;
    static final int SEASON_NONE = 6;

    final float baseRow;
    final float topRow;
    final float crownRow;
    final boolean leafy;
    final boolean rigid;
    final float stubTopRow;
    // Tapered crown that bends like a rod up to the tip; broad crowns move
    // as a block above the crown line instead.
    final boolean conifer;
    // NatureTrees order, -1 for mod sprites.
    final int species;
    // 0 regular, 1 JUMBO, 2 XL, 3 XXL.
    final int family;
    final int stage;
    final int season;
    // Spring overlay that is blossom rather than leaves.
    final boolean bloom;
    // Opaque area relative to the summer sprite of the same tree.
    final float density;
    // Painted leaf structure in px: luminance autocorrelation radii and the
    // area-weighted median size of same-shade blobs (0 = unmeasured).
    final float r50;
    final float r20;
    final float blob;
    final int treeClass;

    private TreeProfile(float baseRow, float topRow, float crownRow, boolean leafy,
                        boolean rigid, float stubTopRow, boolean conifer,
                        int species, int family, int stage, int season, boolean bloom, float density,
                        float r50, float r20, float blob) {
        this.baseRow = baseRow;
        this.topRow = topRow;
        this.crownRow = crownRow;
        this.leafy = leafy;
        this.rigid = rigid;
        this.stubTopRow = stubTopRow;
        this.conifer = conifer;
        this.species = species;
        this.family = family;
        this.stage = stage;
        this.season = season;
        this.bloom = bloom;
        this.density = density;
        this.r50 = r50;
        this.r20 = r20;
        this.blob = blob;
        this.treeClass = species >= 0 ? TreeClass.ofSpecies(species)
                : (conifer ? TreeClass.PENDULOUS : TreeClass.DENSE);
    }

    // Vanilla's evergreens: pine, hemlock and holly (drawn as a dark
    // blue-green cone, stiff glossy leaves); mod trees join by name.
    private static final Pattern CONIFER = Pattern.compile(
            "pine|hemlock|holly|spruce|fir(?!e)|cedar|juniper|cypress|larch|evergreen|conifer|tanne|fichte|kiefer");

    private static boolean coniferByName(String name) {
        return name != null && CONIFER.matcher(name.toLowerCase()).find();
    }

    private static final Pattern VANILLA = Pattern.compile("^e_([a-z]+?)(JUMBO(?:XL|XXL)?)?_1_(\\d+)$");
    // NatureTrees.trees order.
    static final String[] SPECIES = {"americanholly", "canadianhemlock", "virginiapine", "riverbirch",
            "cockspurhawthorn", "dogwood", "carolinasilverbell", "yellowwood", "easternredbud", "redmaple",
            "americanlinden"};
    private static final int HAWTHORN = 4;
    private static final int DOGWOOD = 5;
    private static final int REDBUD = 8;

    private static int speciesOf(String s) {
        for (int i = 0; i < SPECIES.length; ++i) {
            if (SPECIES[i].equals(s)) return i;
        }
        return -1;
    }

    // Sheet layout (NatureTrees.init): regular bare 0-3, snow 4-7, then
    // spring/summer/late summer/autumn overlays x 4 stages; JUMBO the same
    // in pairs; XL/XXL 0-5 one per season, 6-11 the treetop halves in the
    // same order, 12+ trunk and burnt pieces.
    private static int[] parse(String name) {
        Matcher m = VANILLA.matcher(name);
        if (!m.matches()) return null;
        int species = speciesOf(m.group(1));
        String fam = m.group(2);
        int family = fam == null ? 0 : fam.equals("JUMBO") ? 1 : fam.equals("JUMBOXL") ? 2 : 3;
        int idx = Integer.parseInt(m.group(3));
        boolean evergreen = species >= 0 && species <= 2;
        int season;
        int stage;
        if (family == 0) {
            stage = idx % 4;
            season = evergreen ? (idx < 8 ? idx / 4 : SEASON_NONE) : Math.min(idx / 4, SEASON_NONE);
        } else if (family == 1) {
            stage = idx % 2;
            season = Math.min(idx / 2, SEASON_NONE);
        } else {
            stage = 0;
            season = idx < 6 ? idx : idx < 12 ? idx - 6 : SEASON_NONE;
        }
        return new int[]{species, family, stage, season};
    }

    private static volatile HashMap<String, TreeProfile> table;
    private static final IdentityHashMap<Texture, TreeProfile> cache = new IdentityHashMap<>(1024);
    private static volatile boolean tableFailed;

    // Any thread.
    static void warm() {
        if (table == null) loadTable();
    }

    private static synchronized void loadTable() {
        if (table != null) return;
        HashMap<String, TreeProfile> map = new HashMap<>(1024);
        try (InputStream in = TreeProfile.class.getResourceAsStream("tree_profiles.txt")) {
            if (in == null) throw new IllegalStateException("tree_profiles.txt missing from jar");
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                String[] f = line.split(" ");
                if (f.length < 8) continue;
                float base = Float.parseFloat(f[1]);
                float top = Float.parseFloat(f[2]);
                float crownFrac = Float.parseFloat(f[3]);
                boolean leafy = f[4].equals("1");
                boolean rigid = f[7].equals("1");
                float stubTop = f.length > 8 ? Float.parseFloat(f[8]) : base;
                float density = f.length > 10 ? Float.parseFloat(f[10]) : 1.0f;
                float r50 = f.length > 13 ? Float.parseFloat(f[11]) : 0.0f;
                float r20 = f.length > 13 ? Float.parseFloat(f[12]) : 0.0f;
                float blob = f.length > 13 ? Float.parseFloat(f[13]) : 0.0f;
                float crownRow = base - crownFrac * (base - top);
                int[] id = parse(f[0]);
                int species = id == null ? -1 : id[0];
                int family = id == null ? 0 : id[1];
                int stage = id == null ? 0 : id[2];
                int season = id == null ? (leafy ? SEASON_SUMMER : SEASON_BARE) : id[3];
                boolean bloom = season == SEASON_SPRING
                        && (species == REDBUD || species == DOGWOOD || species == HAWTHORN);
                map.put(f[0], new TreeProfile(base, top, crownRow, leafy, rigid, stubTop, coniferByName(f[0]),
                        species, family, stage, season, bloom, density, r50, r20, blob));
            }
        } catch (Throwable t) {
            tableFailed = true;
            System.out.println("[WindSway] tree profile table unavailable, using defaults: " + t);
        }
        table = map;
    }

    private static int familyOf(int widthOrig) {
        return widthOrig >= 896 ? 3 : widthOrig >= 640 ? 2 : widthOrig >= 384 ? 1 : 0;
    }

    // Unknown sprites (mods): leafy, crown a fifth of the way up. Burned small
    // and JUMBO trees draw a fencing_burnt sprite (IsoGridSquare.BurnWalls).
    private static TreeProfile fallback(Texture tex) {
        float top = tex.getOffsetY();
        float base = top + tex.getHeight();
        float h = base - top;
        String name = tex.getName();
        boolean burnt = name != null && name.startsWith("fencing_burnt");
        return new TreeProfile(base, top, base - 0.2f * h, !burnt, burnt, base - 0.05f * h, coniferByName(name),
                -1, familyOf(tex.getWidthOrig()), 0, burnt ? SEASON_BARE : SEASON_SUMMER, false, 1.0f,
                0.0f, 0.0f, 0.0f);
    }

    static TreeProfile of(Texture tex) {
        TreeProfile p = cache.get(tex);
        if (p != null) return p;
        if (table == null) loadTable();
        String name = tex.getName();
        if (name != null && !tableFailed) {
            p = table.get(name);
        }
        if (p == null) p = fallback(tex);
        cache.put(tex, p);
        return p;
    }
}
