package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.core.textures.TextureDraw;

public class Patch_SpriteRenderer {

    // Tree lists are queued through drawGeneric mid-pass. True = the list is
    // held and merged into a pending one; the skipped call returns null, which
    // every vanilla caller ignores.
    @Patch(className = "zombie.core.SpriteRenderer",
           methodName = "drawGeneric")
    public static class Patch_drawGeneric {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.Argument(0) TextureDraw.GenericDrawer drawer) {
            return BatchSequencer.onTreeListDraw(drawer);
        }
    }
}
