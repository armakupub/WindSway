package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_IsoTree {

    // A highlighted tree (chop cursor, chop action) skips the tree list and
    // is drawn as a plain sprite, whose ORE corners move by x * sprite width:
    // the pool's bush sway and the axe's +-0.18 shear a jumbo by hundreds of
    // pixels. The draw gets a scratch ORE instead.
    @Patch(className = "zombie.iso.objects.IsoTree",
           methodName = "renderChopTreeIndicators")
    public static class Patch_renderChopTreeIndicators {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            WindSwayMod.beginChopHighlight();
            return false;
        }

        @Patch.OnExit(onThrowable = Throwable.class)
        public static void exit() {
            WindSwayMod.endChopHighlight();
        }
    }
}
