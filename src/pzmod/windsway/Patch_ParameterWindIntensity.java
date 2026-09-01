package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_ParameterWindIntensity {

    // Sound-only: calculateCurrentValue is the wind's single road into
    // FMOD (one instance, ticked by AmbientStreamManager); aiming, water,
    // weather FX and windchill read ClimateManager directly and never
    // pass through here.
    @Patch(className = "zombie.audio.parameters.ParameterWindIntensity",
           methodName = "calculateCurrentValue")
    public static class Patch_calculateCurrentValue {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) float result) {
            double floor = WindSwayMod.breezePlantFloor();
            if (WindSwayMod.enabled && WindSwayMod.windSound && floor > 0.0 && result < floor) {
                result = (float) floor;
            }
        }
    }
}
