package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_ClimateManager {

    // Render-only: the getter feeds only ObjectRenderEffects' wind pools
    // (precip FX reads the field, gameplay never calls it). The floor is
    // the wandering breeze around the slider.
    @Patch(className = "zombie.iso.weather.ClimateManager",
           methodName = "getWindTickFinal")
    public static class Patch_getWindTickFinal {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) double result) {
            double floor = WindSwayMod.breezePlantFloor();
            if (WindSwayMod.enabled && floor > 0.0) {
                result = Math.max(floor, result);
            }
        }
    }
}
