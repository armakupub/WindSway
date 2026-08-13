package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.iso.IsoObject;

public class Patch_FBORenderCell {

    // OnExit: the FBORenderTrees flush at the end of this method has put
    // tree and model depth in the buffer before our batch draws.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "renderTranslucentObjects")
    public static class Patch_renderTranslucentObjects {

        @Patch.OnExit
        public static void exit(@Patch.Argument(0) int playerIndex,
                                @Patch.Argument(1) int z) {
            WindSwayMod.onTranslucentPassDone(playerIndex, z);
        }
    }

    // Single-sprite translucents on vanilla's tile-depth path leave the
    // per-object draw and join the batch; objects that stay vanilla flush
    // the pending batch first if they can overlap it.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "renderMinusFloor_NotDoorOrWall")
    public static class Patch_renderMinusFloor_NotDoorOrWall {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            return WindSwayMod.tryCaptureGrass(object);
        }
    }

    // Fences, walls, doors: mid-pass draws without depth writes — pending
    // grass behind them must flush first. boolean+skipOn with constant
    // false is deliberate: the void OnEnter form kills the whole advice
    // unit (ZB weave shape).
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "renderMinusFloor_DoorOrWall")
    public static class Patch_renderMinusFloor_DoorOrWall {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            WindSwayMod.onVanillaTranslucentDraw(object, true);
            return false;
        }
    }

}
