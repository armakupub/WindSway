package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_ClimateManager {

    // Render-only remap: this getter feeds only ObjectRenderEffects' wind
    // pools; precip FX reads the backing field directly and gameplay never
    // consumes the getter (42.20). Linear squeeze, not max(), so real
    // weather dynamics scale through instead of flattening while calm.
    @Patch(className = "zombie.iso.weather.ClimateManager",
           methodName = "getWindTickFinal")
    public static class Patch_getWindTickFinal {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) double result) {
            double floor = WindSwayMod.windFloor;
            if (WindSwayMod.enabled && floor > 0.0) {
                result = floor + (1.0 - floor) * result;
            }
        }
    }
}
