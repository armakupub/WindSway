package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.objects.ObjectRenderEffects;

public class Patch_ObjectRenderEffects {

    // updateStatic feeds one global wind value to both pool families;
    // taking over the per-pool update is the only seam that gives each
    // family its own channel and motion without replicating the pool loop.
    // Unknown pools return false and run vanilla.
    @Patch(className = "zombie.iso.objects.ObjectRenderEffects",
           methodName = "update")
    public static class Patch_update {

        // The @Argument bindings disambiguate the overload: only
        // update(float, float) matches, not the no-arg update().
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This ObjectRenderEffects self,
                                    @Patch.Argument(0) float wind,
                                    @Patch.Argument(1) float angle) {
            return TreeSway.update(self, angle);
        }
    }

    // updateStatic is the frame's last writer of all rustle values;
    // rewriting on exit lands before any draw reads them.
    @Patch(className = "zombie.iso.objects.ObjectRenderEffects",
           methodName = "updateStatic")
    public static class Patch_updateStatic {

        @Patch.OnExit
        public static void exit() {
            WindSwayMod.attenuateRustles();
        }
    }
}
