package pzmod.windsway;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import zombie.iso.IsoCamera;
import zombie.iso.IsoObject;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.weather.ClimateManager;

// Counters and the 5s console dump behind setDebugLog. Game thread, except
// the renderer values read in the dump (written on the render thread; a
// stale read only skews a debug count).
final class DebugStats {

    static long lastWindLog;
    static int diagAlphaSkips;
    static int lastFrameCount = -1;
    static int frames5s;

    // Counts and names objects handed back to vanilla.
    private static final HashMap<String, Integer> rejectCounts = new HashMap<>();
    private static final ArrayList<String> rejectSeen = new ArrayList<>();
    private static final HashSet<String> rejectSeenSet = new HashSet<>();
    private static int rejectSeenPrinted;

    static int flushCount5s;
    static int flushQuads5s;
    static int maxBatch5s;

    // Flush causes.
    static int flushDoor5s;
    static int flushObj5s;
    static int flushTree5s;
    static int flushPass5s;
    static int gateSkip5s;
    static int wallsCaptured5s;
    static int grassSubDraws5s;

    // Game-thread nanotimers. The five hook accounts are exclusive (the
    // hooks never nest: a capture's reject-gate bills the capture, drained
    // segments re-enter behind the treeFlushing guard); drain is a
    // sub-account of whichever hook drained. ~2 nanoTime calls per hook.
    static long gtGrassNs;
    static long gtWallNs;
    static long gtFloorNs;
    static long gtTreeNs;
    static long gtPassNs;
    static long gtDrainNs;
    static int gtGrassN;
    static int gtWallN;
    static int gtFloorN;
    static int gtTreeN;

    private static final ArrayList<String> flushSeen = new ArrayList<>();
    private static final HashSet<String> flushSeenSet = new HashSet<>();
    private static int flushSeenPrinted;

    static int held5s;
    static int merged5s;
    static int treeFlushObj5s;
    static int treeSplit5s;
    static int treeSegDraws5s;
    static int treeFlushSee5s;
    static int treeFlushPass5s;
    static int treeGateSkip5s;
    static int mergedTrees5s;
    static int mergedMax5s;
    static int seeStencil5s;
    static int seeTransp5s;
    static int seeFade5s;
    static int seeCut5s;
    // Dry run of the see-through flush skip.
    static int seeLists5s;
    static int seeSkipRect5s;
    static int seeSkipBbox5s;
    static int seeSkipQuad5s;

    private DebugStats() {
    }

    static boolean reject(String reason, IsoSprite sprite) {
        if (!WindSwayMod.debugLog) return false;
        rejectCounts.merge(reason, 1, Integer::sum);
        if (rejectSeenSet.size() < 80) {
            String entry = reason + ":" + (sprite != null && sprite.name != null ? sprite.name : "?");
            if (rejectSeenSet.add(entry)) {
                rejectSeen.add(entry);
            }
        }
        return false;
    }

    static void noteFlushTrigger(IsoObject object, boolean doorOrWall) {
        if (!WindSwayMod.debugLog) return;
        if (doorOrWall) {
            flushDoor5s++;
        } else {
            flushObj5s++;
        }
        if (flushSeenSet.size() < 40) {
            IsoSprite spr = object != null ? object.getSprite() : null;
            String entry = (doorOrWall ? "door:" : "obj:")
                    + (spr != null && spr.name != null ? spr.name
                            : object != null ? object.getClass().getSimpleName() : "null");
            if (flushSeenSet.add(entry)) {
                flushSeen.add(entry);
            }
        }
    }

    // Pass end: frame count plus the dump every five seconds.
    static void onPassEnd() {
        if (!WindSwayMod.debugLog) return;
        int fc = IsoCamera.frameState.frameCount;
        if (fc != lastFrameCount) {
            lastFrameCount = fc;
            frames5s++;
        }
        long now = System.currentTimeMillis();
        if (now - lastWindLog <= 5000L) return;
        lastWindLog = now;
        WindSwayMod.trace(String.format("plantWind=%.3f raw=%.3f treeW=%.3f dir=%.2f poolX=%.3f rustleG=%.2f | flushes=%d quads=%d maxBatch=%d | alphaskip=%d",
                ClimateManager.getWindTickFinal(), TreeSway.raw, TreeSway.w, TreeSway.dir, TreeSway.lastX, WindSwayMod.lastRustleGain, flushCount5s, flushQuads5s, maxBatch5s, diagAlphaSkips));
        WindSwayMod.trace(String.format("flush causes: door=%d obj=%d tree=%d passEnd=%d | gateSkips=%d | walls captured=%d | grass draws=%d",
                flushDoor5s, flushObj5s, flushTree5s, flushPass5s, gateSkip5s, wallsCaptured5s, grassSubDraws5s));
        wallsCaptured5s = 0;
        grassSubDraws5s = 0;
        WindSwayMod.trace(String.format("trees: lists=%d trees=%d draws=%d binds=%d pageMiss=%d maxList=%d",
                TreeRenderer.diagRenders, TreeRenderer.diagTrees, TreeRenderer.diagDraws, TreeRenderer.diagBinds,
                TreeRenderer.diagPageMiss, TreeRenderer.diagMaxTrees));
        WindSwayMod.trace(TreeRenderer.diagClassLine());
        WindSwayMod.trace(PlantClass.diagLine());
        TreeRenderer.diagBinds = 0;
        WindSwayMod.trace(String.format("tree merge: held=%d merged=%d split=%d | drain obj=%d see=%d passEnd=%d | gateSkips=%d | trees/draw=%.1f draws=%d max=%d | see lists: stencil=%d transp=%d fade=%d cut=%d",
                held5s, merged5s, treeSplit5s, treeFlushObj5s, treeFlushSee5s, treeFlushPass5s, treeGateSkip5s,
                mergedTrees5s / (double) Math.max(1, treeSegDraws5s), treeSegDraws5s, mergedMax5s,
                seeStencil5s, seeTransp5s, seeFade5s, seeCut5s));
        WindSwayMod.trace(String.format("see skip: noRect=%d noBbox=%d noQuad=%d of %d lists",
                seeSkipRect5s, seeSkipBbox5s, seeSkipQuad5s, seeLists5s));
        seeLists5s = 0;
        seeSkipRect5s = 0;
        seeSkipBbox5s = 0;
        seeSkipQuad5s = 0;
        seeStencil5s = 0;
        seeTransp5s = 0;
        seeFade5s = 0;
        seeCut5s = 0;
        held5s = 0;
        merged5s = 0;
        treeSplit5s = 0;
        treeSegDraws5s = 0;
        treeFlushObj5s = 0;
        treeFlushSee5s = 0;
        treeFlushPass5s = 0;
        treeGateSkip5s = 0;
        mergedTrees5s = 0;
        mergedMax5s = 0;
        int frames = Math.max(1, frames5s);
        WindSwayMod.trace(String.format("gpu: trees %s | grass %s | frames=%d",
                TreeRenderer.gpuTimer.report(frames), WindSwayGrassDrawer.gpuTimer.report(frames), frames5s));
        long grassNs = WindSwayGrassDrawer.cpuNs.getAndSet(0L);
        long grassFillNs = WindSwayGrassDrawer.cpuFillNs.getAndSet(0L);
        long grassRuns = WindSwayGrassDrawer.diagRuns.getAndSet(0L);
        long grassBinds = WindSwayGrassDrawer.diagBinds.getAndSet(0L);
        WindSwayMod.trace(String.format("cpu (render thread): tree build %.3f draw %.3f ms/frame | grass batch %.3f ms/frame (fill %.3f, gl %.3f, runs/batch %.1f, binds/batch %.1f) | depth atlas cells %d/%d copies %d evictions %d",
                TreeRenderer.cpuBuildNs.getAndSet(0L) / 1.0e6 / frames,
                TreeRenderer.cpuDrawNs.getAndSet(0L) / 1.0e6 / frames,
                grassNs / 1.0e6 / frames,
                grassFillNs / 1.0e6 / frames,
                (grassNs - grassFillNs) / 1.0e6 / frames,
                grassRuns / (double) Math.max(1, flushCount5s),
                grassBinds / (double) Math.max(1, flushCount5s),
                DepthAtlas.diagCells, DepthAtlas.diagCapacity, DepthAtlas.diagCopies, DepthAtlas.diagEvictions));
        DepthAtlas.diagCopies = 0;
        DepthAtlas.diagEvictions = 0;
        double perBatch = 1.0e3 / Math.max(1, flushCount5s);
        WindSwayMod.trace(String.format("grass gl per batch (us): timer %.2f state %.2f upload %.2f attrib %.2f prog %.2f draw %.2f end %.2f",
                WindSwayGrassDrawer.cpuTimerNs.getAndSet(0L) * perBatch / 1.0e6,
                WindSwayGrassDrawer.cpuStateNs.getAndSet(0L) * perBatch / 1.0e6,
                WindSwayGrassDrawer.cpuUploadNs.getAndSet(0L) * perBatch / 1.0e6,
                WindSwayGrassDrawer.cpuAttribNs.getAndSet(0L) * perBatch / 1.0e6,
                WindSwayGrassDrawer.cpuProgNs.getAndSet(0L) * perBatch / 1.0e6,
                WindSwayGrassDrawer.cpuDrawNs.getAndSet(0L) * perBatch / 1.0e6,
                WindSwayGrassDrawer.cpuEndNs.getAndSet(0L) * perBatch / 1.0e6));
        WindSwayMod.trace(String.format("cpu (game thread): captureGrass %.3f captureWall %.3f floorGate %.3f treeList %.3f passEnd %.3f ms/frame (of it drain %.3f) | calls/frame grass=%.1f wall=%.1f floor=%.1f tree=%.1f | quad pool hit=%.1f miss=%.1f",
                gtGrassNs / 1.0e6 / frames, gtWallNs / 1.0e6 / frames, gtFloorNs / 1.0e6 / frames,
                gtTreeNs / 1.0e6 / frames, gtPassNs / 1.0e6 / frames, gtDrainNs / 1.0e6 / frames,
                gtGrassN / (double) frames, gtWallN / (double) frames,
                gtFloorN / (double) frames, gtTreeN / (double) frames,
                WindSwayGrassDrawer.poolHit5s / (double) frames,
                WindSwayGrassDrawer.poolMiss5s / (double) frames));
        WindSwayGrassDrawer.poolHit5s = 0;
        WindSwayGrassDrawer.poolMiss5s = 0;
        gtGrassNs = 0L;
        gtWallNs = 0L;
        gtFloorNs = 0L;
        gtTreeNs = 0L;
        gtPassNs = 0L;
        gtDrainNs = 0L;
        gtGrassN = 0;
        gtWallN = 0;
        gtFloorN = 0;
        gtTreeN = 0;
        frames5s = 0;
        TreeRenderer.diagRenders = 0;
        TreeRenderer.diagPageMiss = 0;
        TreeRenderer.diagTrees = 0;
        TreeRenderer.diagDraws = 0;
        TreeRenderer.diagMaxTrees = 0;
        flushDoor5s = 0;
        flushObj5s = 0;
        flushTree5s = 0;
        flushPass5s = 0;
        gateSkip5s = 0;
        while (flushSeenPrinted < flushSeen.size()) {
            WindSwayMod.trace("flush trigger: " + flushSeen.get(flushSeenPrinted));
            flushSeenPrinted++;
        }
        flushCount5s = 0;
        flushQuads5s = 0;
        maxBatch5s = 0;
        diagAlphaSkips = 0;
        if (!rejectCounts.isEmpty()) {
            StringBuilder sb = new StringBuilder("rejects:");
            for (Map.Entry<String, Integer> e : rejectCounts.entrySet()) {
                sb.append(' ').append(e.getKey()).append('=').append(e.getValue());
            }
            WindSwayMod.trace(sb.toString());
            rejectCounts.clear();
        }
        while (rejectSeenPrinted < rejectSeen.size()) {
            WindSwayMod.trace("reject sprite: " + rejectSeen.get(rejectSeenPrinted));
            rejectSeenPrinted++;
        }
    }
}
