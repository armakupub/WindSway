package pzmod.windsway;

import me.zed_0xff.zombie_buddy.Patch;

import zombie.core.textures.Texture;

public class Patch_IsoCell {

    // Video mode: a missing mask takes vanilla's early-out in drawStencilMask,
    // nothing writes stencil 128, no tree goes translucent. Skipping the method
    // itself would drop the frame clear and leave its GL state behind.
    @Patch(className = "zombie.iso.IsoCell",
           methodName = "getStencilTexture")
    public static class Patch_getStencilTexture {

        @Patch.OnExit
        public static void exit(@Patch.Return(readOnly = false) Texture result) {
            if (WindSwayMod.enabled && WindSwayMod.videoMode) {
                result = null;
            }
        }
    }
}
