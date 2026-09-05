package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.characters.IsoPlayer;

public class Patch_ParameterWindIntensity {

    // Sound-only: calculateCurrentValue is the wind's single road into
    // FMOD (one instance, ticked by AmbientStreamManager); aiming, water,
    // weather FX and windchill read ClimateManager directly and never
    // pass through here. The parameter ticks in the main menu too, where
    // there is no breeze. The gust field at the player rides on top, so
    // a front that sweeps the screen is heard. Quantised like vanilla:
    // FMODParameter.update sets the parameter only on a change.
    @Patch(className = "zombie.audio.parameters.ParameterWindIntensity",
           methodName = "calculateCurrentValue")
    public static class Patch_calculateCurrentValue {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) float result) {
            if (!WindSwayMod.enabled || !WindSwayMod.windSound) return;
            if (IsoPlayer.getInstance() == null) return;
            float raw = result;
            double floor = WindSwayMod.breezePlantFloor() * WindSwayMod.windSoundLevel;
            double v = raw;
            if (floor > 0.0 && v < floor) v = floor;
            double g = WindSwayMod.gustSound;
            if (g >= 0.0) {
                double c = 2.0 * g - 1.0;
                v *= 1.0 + (c < 0.0 ? TreeSway.soundGustDown : TreeSway.soundGustUp) * c;
                if (v > 1.0) v = 1.0;
            }
            result = (float) ((int) (v * 1000.0)) / 1000.0f;
            if (WindSwayMod.debugWindSound) WindSwayMod.logWindSound(raw, floor, g, result);
        }
    }
}
