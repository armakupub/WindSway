package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

public class Patch_LuaManager {

    // The advice outlives the mod in the JVM: after a mod-list change the
    // getter would stay forced without the mod. Every Lua reload starts
    // here (Core.ResetLua, and IngameState.exit, which reloads the default
    // mod set without ResetLua) and ends with OnGameBoot, where the mod's
    // Lua re-arms us if it is still loaded.
    @Patch(className = "zombie.Lua.LuaManager",
           methodName = "init")
    public static class Patch_init {

        @Patch.OnEnter(skipOn = true)
        public static boolean enter() {
            WindSwayMod.enabled = false;
            return false;
        }
    }
}
