package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.characters.IsoPlayer;

public class Patch_ParameterWindIntensity {

    // Sound-only: calculateCurrentValue is the wind's single road into
    // FMOD (one instance, ticked by AmbientStreamManager); aiming, water,
    // weather FX and windchill read ClimateManager directly and never
    // pass through here. The parameter ticks in the main menu too, where
    // there is no breeze. Quantised like vanilla: FMODParameter.update
    // sets the parameter only on a change.
    @Patch(className = "zombie.audio.parameters.ParameterWindIntensity",
           methodName = "calculateCurrentValue")
    public static class Patch_calculateCurrentValue {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) float result) {
            if (!WindSwayMod.enabled || !WindSwayMod.windSound) return;
            if (IsoPlayer.getInstance() == null) return;
            double floor = WindSwayMod.breezePlantFloor();
            if (floor > 0.0 && result < floor) {
                result = (float) ((int) (floor * 1000.0)) / 1000.0f;
            }
        }
    }
}
