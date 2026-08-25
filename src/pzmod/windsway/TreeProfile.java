package pzmod.windsway;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.regex.Pattern;

import zombie.core.textures.Texture;

// Per-sprite silhouette metrics from tools/tree_profiles.py, rows in frame
// pixels.
final class TreeProfile {

    final float baseRow;
    final float topRow;
    final float crownRow;
    final boolean leafy;
    final boolean rigid;
    final float stubTopRow;
    // Tapered crown that bends like a rod up to the tip; broad crowns move
    // as a block above the crown line instead.
    final boolean conifer;

    private TreeProfile(float baseRow, float topRow, float crownRow, boolean leafy,
                        boolean rigid, float stubTopRow, boolean conifer) {
        this.baseRow = baseRow;
        this.topRow = topRow;
        this.crownRow = crownRow;
        this.leafy = leafy;
        this.rigid = rigid;
        this.stubTopRow = stubTopRow;
        this.conifer = conifer;
    }

    // Vanilla's evergreens: pine, hemlock and holly (drawn as a dark
    // blue-green cone, stiff glossy leaves); mod trees join by name.
    private static final Pattern CONIFER = Pattern.compile(
            "pine|hemlock|holly|spruce|fir(?!e)|cedar|juniper|cypress|larch|evergreen|conifer|tanne|fichte|kiefer");

    private static boolean coniferByName(String name) {
        return name != null && CONIFER.matcher(name.toLowerCase()).find();
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
                float crownRow = base - crownFrac * (base - top);
                map.put(f[0], new TreeProfile(base, top, crownRow, leafy, rigid, stubTop, coniferByName(f[0])));
            }
        } catch (Throwable t) {
            tableFailed = true;
            System.out.println("[WindSway] tree profile table unavailable, using defaults: " + t);
        }
        table = map;
    }

    // Unknown sprites (mods): leafy, crown a fifth of the way up. Burned small
    // and JUMBO trees draw a fencing_burnt sprite (IsoGridSquare.BurnWalls).
    private static TreeProfile fallback(Texture tex) {
        float top = tex.getOffsetY();
        float base = top + tex.getHeight();
        float h = base - top;
        String name = tex.getName();
        boolean burnt = name != null && name.startsWith("fencing_burnt");
        return new TreeProfile(base, top, base - 0.2f * h, !burnt, false, base - 0.05f * h, coniferByName(name));
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
