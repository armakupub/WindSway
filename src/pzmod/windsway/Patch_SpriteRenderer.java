package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.TextureDraw;

public class Patch_SpriteRenderer {

    // Tree lists are queued through drawGeneric mid-pass; grass captured
    // before a see-through tree must be queued ahead of it. boolean+skipOn
    // with constant false is the ZB weave shape for a plain OnEnter.
    @Patch(className = "zombie.core.SpriteRenderer",
           methodName = "drawGeneric")
    public static class Patch_drawGeneric {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) TextureDraw.GenericDrawer drawer) {
            WindSwayMod.onTreeListDraw(drawer);
            return false;
        }
    }
}
