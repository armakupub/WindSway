package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_Core {

    // Vanilla defaults the option to off, so most players would never see
    // the foliage sway. Forcing the getter keeps options.ini honest (the
    // ini write reads the ConfigOption directly) and stays live:
    // FBORenderCell.checkWindEffectsOption polls per frame and rebakes
    // chunks on every flip.
    @Patch(className = "zombie.core.Core",
           methodName = "getOptionDoWindSpriteEffects")
    public static class Patch_getOptionDoWindSpriteEffects {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) boolean result) {
            if (WindSwayMod.enabled) {
                result = true;
            }
        }
    }
}
