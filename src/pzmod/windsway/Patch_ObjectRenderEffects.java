package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;
import zombie.iso.objects.ObjectRenderEffects;

public class Patch_ObjectRenderEffects {

    // Vanilla feeds one global wind value to both pool families
    // (updateStatic reads getWindTickFinal once for plants AND trees);
    // rewriting the arg for isTree pools is the only seam that separates
    // the tree channel without replicating the pool loop.
    @Patch(className = "zombie.iso.objects.ObjectRenderEffects",
           methodName = "update")
    public static class Patch_update {

        // boolean+skipOn returning constant false: non-skipping arg
        // rewrite. The @Argument binding also disambiguates the overload:
        // only update(float, float) matches, not the no-arg update().
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(@Patch.This ObjectRenderEffects self,
                                    @Patch.Argument(value = 0, readOnly = false) float wind) {
            wind = WindSwayMod.treePoolWind(self, wind);
            return false;
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
