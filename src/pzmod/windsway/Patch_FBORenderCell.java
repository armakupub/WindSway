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
            return GrassCapture.tryCaptureGrass(object);
        }
    }

    // Fences, walls, doors: mid-pass draws without depth writes. Plain
    // walls join the batch in paint order; anything else flushes the
    // pending grass behind it first and draws in vanilla.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "renderMinusFloor_DoorOrWall")
    public static class Patch_renderMinusFloor_DoorOrWall {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            return GrassCapture.tryCaptureWall(object);
        }
    }

    // Transparent floors and shore water take renderTranslucent's floor
    // branch, the one non-tree draw passing neither method above; the
    // IsoObject argument picks the overload.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderCell",
           methodName = "renderFloor")
    public static class Patch_renderFloor {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) IsoObject object) {
            GrassCapture.onVanillaFloorDraw(object);
            return false;
        }
    }

}
