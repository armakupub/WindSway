package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.Texture;
import zombie.iso.fboRenderChunk.FBORenderTrees;
import zombie.iso.objects.ObjectRenderEffects;

public class Patch_FBORenderTrees {

    // Strips the shared pool sway from tree OREs while the batch renderer
    // draws and scales the rest to the jumbo width (renderTexture
    // displaces corners by fixed 128/256 texels, so a jumbo would sway
    // 3-7x less relative to its crown). addTree copies the corners
    // immediately; one scratch ORE is safe.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderTrees",
           methodName = "addTree")
    public static class Patch_addTree {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) Texture texture,
                                    @Patch.Argument(1) Texture texture2,
                                    @Patch.Argument(value = 9, readOnly = false) ObjectRenderEffects ore) {
            ore = WindSwayMod.scaleTreeOre(texture, texture2, ore);
            return false;
        }
    }

    // Vanilla renders each tree of the list on its own (matrix, state,
    // runs, flush); the batch renderer draws the whole list at once and
    // vanilla skips. Chunk-texture bakes and any failure fall through.
    @Patch(className = "zombie.iso.fboRenderChunk.FBORenderTrees",
           methodName = "render")
    public static class Patch_render {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This FBORenderTrees self) {
            return TreeRenderer.render(self);
        }
    }
}
