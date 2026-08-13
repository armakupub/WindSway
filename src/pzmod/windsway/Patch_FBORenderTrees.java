package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.Texture;
import zombie.iso.objects.ObjectRenderEffects;

public class Patch_FBORenderTrees {

    // renderTexture displaces tree corners by fixed 128/256 texel units,
    // so a jumbo sways 3-7x less than a small tree relative to its crown.
    // addTree copies the corners immediately; one scratch ORE is safe.
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
}
