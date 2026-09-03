package pzmod.windsway;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import zombie.core.opengl.GLStateRenderThread;
import zombie.core.opengl.IOpenGLState;

// Render thread, console only (setDebugGlTrace): the engine's state
// trackers against the real GL state before and after each of our draws,
// and the raw state a draw left changed. A tracker that disagrees with GL
// elides its next set, so the draw after it runs with the wrong state;
// this names the state and the side that is wrong.
final class GlTrace {

    static volatile boolean enabled;

    private static final String[] NAMES = {
        "program", "activeUnit", "tex2D", "blend", "blendSrcRgb", "blendDstRgb", "blendSrcA", "blendDstA",
        "depthTest", "depthFunc", "depthMask", "colorMaskR", "colorMaskG", "colorMaskB", "colorMaskA",
        "cullFace", "alphaTest", "alphaFunc", "alphaRefx1000", "stencilTest", "stencilFunc", "stencilRef",
        "stencilValueMask", "stencilWriteMask", "stencilFail", "stencilZFail", "stencilZPass", "scissorTest",
        "vao", "arrayBuffer", "elementBuffer", "framebuffer", "polygonFront", "polygonBack", "texture2Denable",
        "unpackAlign", "attribEnableBits", "tex2D_unit1", "tex2D_unit2", "viewX", "viewY", "viewW", "viewH",
    };
    private static final int N = NAMES.length;
    // Changes a draw of ours is expected to leave: the program (ShaderHelper
    // caches it), the tracked states (their tracker moved with them, the
    // tracker check covers a split), units 0-2 and the array buffer (the ring
    // buffer restores them on its next run). What remains is untracked state
    // nobody restores.
    private static final boolean[] EXPECTED = new boolean[N];
    static {
        int[] e = {0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 29, 37, 38};
        for (int i : e) EXPECTED[i] = true;
    }
    private static final int[] before = new int[N];
    private static final int[] after = new int[N];
    private static final IntBuffer ints4 = BufferUtils.createIntBuffer(16);
    private static final ByteBuffer bools4 = BufferUtils.createByteBuffer(16);
    private static int logged;
    private static final int LOG_CAP = 80;
    private static boolean dumpRequested = true;
    private static boolean reflectOk = true;
    private static Field currentValueField;

    private GlTrace() {
    }

    // Game thread, per world and from the console: one full dump at the next
    // traced draw, the log cap starts over.
    static void requestDump() {
        dumpRequested = true;
        logged = 0;
    }

    static void begin(String where) {
        if (!enabled) return;
        try {
            checkTrackers(where + " before");
            snapshot(before);
            if (dumpRequested) {
                dumpRequested = false;
                dump(where, before);
            }
        } catch (Throwable t) {
            enabled = false;
            WindSwayMod.trace("gl trace off: " + t, t);
        }
    }

    static void end(String where) {
        if (!enabled) return;
        try {
            snapshot(after);
            diff(where);
            checkTrackers(where + " after");
        } catch (Throwable t) {
            enabled = false;
            WindSwayMod.trace("gl trace off: " + t, t);
        }
    }

    private static void snapshot(int[] s) {
        s[0] = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        s[1] = active - GL13.GL_TEXTURE0;
        s[2] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        s[3] = GL11.glIsEnabled(GL11.GL_BLEND) ? 1 : 0;
        s[4] = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        s[5] = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        s[6] = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        s[7] = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        s[8] = GL11.glIsEnabled(GL11.GL_DEPTH_TEST) ? 1 : 0;
        s[9] = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        s[10] = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK) ? 1 : 0;
        bools4.clear();
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bools4);
        for (int i = 0; i < 4; ++i) s[11 + i] = bools4.get(i) != 0 ? 1 : 0;
        s[15] = GL11.glIsEnabled(GL11.GL_CULL_FACE) ? 1 : 0;
        s[16] = GL11.glIsEnabled(GL11.GL_ALPHA_TEST) ? 1 : 0;
        s[17] = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        s[18] = Math.round(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF) * 1000.0f);
        s[19] = GL11.glIsEnabled(GL11.GL_STENCIL_TEST) ? 1 : 0;
        s[20] = GL11.glGetInteger(GL11.GL_STENCIL_FUNC);
        s[21] = GL11.glGetInteger(GL11.GL_STENCIL_REF);
        s[22] = GL11.glGetInteger(GL11.GL_STENCIL_VALUE_MASK);
        s[23] = GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK);
        s[24] = GL11.glGetInteger(GL11.GL_STENCIL_FAIL);
        s[25] = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_FAIL);
        s[26] = GL11.glGetInteger(GL11.GL_STENCIL_PASS_DEPTH_PASS);
        s[27] = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST) ? 1 : 0;
        s[28] = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        s[29] = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        s[30] = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        s[31] = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        ints4.clear();
        GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, ints4);
        s[32] = ints4.get(0);
        s[33] = ints4.get(1);
        s[34] = GL11.glIsEnabled(GL11.GL_TEXTURE_2D) ? 1 : 0;
        s[35] = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int bits = 0;
        for (int i = 0; i < 16; ++i) {
            if (GL20.glGetVertexAttribi(i, GL20.GL_VERTEX_ATTRIB_ARRAY_ENABLED) != 0) bits |= 1 << i;
        }
        s[36] = bits;
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        s[37] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE2);
        s[38] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(active);
        ints4.clear();
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, ints4);
        for (int i = 0; i < 4; ++i) s[39 + i] = ints4.get(i);
    }

    private static void diff(String where) {
        StringBuilder sb = null;
        for (int i = 0; i < N; ++i) {
            if (EXPECTED[i] || before[i] == after[i]) continue;
            if (sb == null) sb = new StringBuilder("gl trace: ").append(where).append(" left:");
            sb.append(' ').append(NAMES[i]).append(' ').append(before[i]).append("->").append(after[i]);
        }
        if (sb != null) log(sb.toString());
    }

    private static void dump(String where, int[] s) {
        StringBuilder sb = new StringBuilder("gl trace: state at ").append(where).append(':');
        for (int i = 0; i < N; ++i) {
            sb.append(' ').append(NAMES[i]).append('=').append(s[i]);
        }
        WindSwayMod.trace(sb.toString());
    }

    // The trackers' current values against GL. A mismatch before our draw is
    // the engine's (or another mod's) raw call; after our draw it is ours.
    private static void checkTrackers(String where) {
        if (!reflectOk) return;
        try {
            checkBool("Blend", GLStateRenderThread.Blend, GL11.glIsEnabled(GL11.GL_BLEND), where);
            checkInts4("BlendFuncSeparate", GLStateRenderThread.BlendFuncSeparate,
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                    GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA), where);
            checkBool("DepthTest", GLStateRenderThread.DepthTest, GL11.glIsEnabled(GL11.GL_DEPTH_TEST), where);
            checkInt("DepthFunc", GLStateRenderThread.DepthFunc, GL11.glGetInteger(GL11.GL_DEPTH_FUNC), where);
            checkBool("DepthMask", GLStateRenderThread.DepthMask, GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK), where);
            bools4.clear();
            GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, bools4);
            checkBools4("ColorMask", GLStateRenderThread.ColorMask, bools4.get(0) != 0, bools4.get(1) != 0,
                    bools4.get(2) != 0, bools4.get(3) != 0, where);
            checkBool("StencilTest", GLStateRenderThread.StencilTest, GL11.glIsEnabled(GL11.GL_STENCIL_TEST), where);
            checkBool("AlphaTest", GLStateRenderThread.AlphaTest, GL11.glIsEnabled(GL11.GL_ALPHA_TEST), where);
            checkBool("ScissorTest", GLStateRenderThread.ScissorTest, GL11.glIsEnabled(GL11.GL_SCISSOR_TEST), where);
            checkInt("StencilMask", GLStateRenderThread.StencilMask, GL11.glGetInteger(GL11.GL_STENCIL_WRITEMASK), where);
        } catch (Throwable t) {
            reflectOk = false;
            WindSwayMod.trace("gl trace: tracker check unavailable: " + t);
        }
    }

    private static Object current(IOpenGLState<?> tracker) throws Exception {
        Field f = currentValueField;
        if (f == null) {
            f = IOpenGLState.class.getDeclaredField("currentValue");
            f.setAccessible(true);
            currentValueField = f;
        }
        return f.get(tracker);
    }

    private static Object field(Object value, String name) throws Exception {
        Field f = value.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(value);
    }

    private static void checkBool(String name, IOpenGLState<?> tracker, boolean gl, String where) throws Exception {
        boolean tracked = (Boolean) field(current(tracker), "value");
        if (tracked != gl) log("gl trace: " + where + ": tracker " + name + "=" + tracked + " but GL=" + gl);
    }

    private static void checkInt(String name, IOpenGLState<?> tracker, int gl, String where) throws Exception {
        int tracked = (Integer) field(current(tracker), "value");
        if (tracked != gl) log("gl trace: " + where + ": tracker " + name + "=" + tracked + " but GL=" + gl);
    }

    private static void checkInts4(String name, IOpenGLState<?> tracker, int a, int b, int c, int d, String where) throws Exception {
        Object v = current(tracker);
        int ta = (Integer) field(v, "a");
        int tb = (Integer) field(v, "b");
        int tc = (Integer) field(v, "c");
        int td = (Integer) field(v, "d");
        if (ta != a || tb != b || tc != c || td != d) {
            log("gl trace: " + where + ": tracker " + name + "=(" + ta + "," + tb + "," + tc + "," + td
                    + ") but GL=(" + a + "," + b + "," + c + "," + d + ")");
        }
    }

    private static void checkBools4(String name, IOpenGLState<?> tracker, boolean a, boolean b, boolean c, boolean d, String where) throws Exception {
        Object v = current(tracker);
        boolean ta = (Boolean) field(v, "a");
        boolean tb = (Boolean) field(v, "b");
        boolean tc = (Boolean) field(v, "c");
        boolean td = (Boolean) field(v, "d");
        if (ta != a || tb != b || tc != c || td != d) {
            log("gl trace: " + where + ": tracker " + name + "=(" + ta + "," + tb + "," + tc + "," + td
                    + ") but GL=(" + a + "," + b + "," + c + "," + d + ")");
        }
    }

    private static void log(String msg) {
        if (logged >= LOG_CAP) return;
        ++logged;
        WindSwayMod.trace(msg);
        if (logged == LOG_CAP) WindSwayMod.trace("gl trace: log cap reached, silent from here");
    }
}
