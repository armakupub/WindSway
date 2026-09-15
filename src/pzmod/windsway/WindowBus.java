package pzmod.windsway;

import fmod.javafmod;

// The window rattle (two loops on event:/World/Ambient/Window, from 0.6 up,
// indoors only) hangs on the same global parameter the mod raises, and under a
// clear sky it reads as a zombie at the frame. This bus carries nothing but
// doors and windows, so muting it costs no other ambience.
// Public throughout: the advice body reaches in here (IllegalAccessError).
public final class WindowBus {

    public static final double GATE = 0.6;

    // A gust riding the gate flips the bus every few seconds. The loops are
    // silent at 0.6 and only swell towards 1.0, so releasing late is free.
    public static final double RELEASE = 0.55;

    private static final String PATH = "bus:/InGame/Ambiance/World/Ambient/DoorWindow";

    private static long bus;
    private static boolean resolved;
    private static float saved = 1.0f;
    private static boolean muted;

    private WindowBus() {
    }

    public static void apply(double sent, float raw) {
        if (raw >= GATE) set(false);
        else if (sent >= GATE) set(true);
        else if (sent < RELEASE) set(false);
    }

    public static void release() {
        set(false);
    }

    private static void set(boolean mute) {
        if (mute == muted) return;
        if (!resolve()) return;
        try {
            javafmod.FMOD_Studio_Bus_SetVolume(bus, mute ? 0.0f : saved);
            muted = mute;
            if (WindSwayMod.debugWindSound) {
                WindSwayMod.trace("window bus " + (mute ? "muted" : "restored to " + saved));
            }
        } catch (Throwable t) {
            bus = 0L;
            WindSwayMod.trace("window bus volume failed, leaving it to vanilla", t);
        }
    }

    private static boolean resolve() {
        if (!resolved) {
            resolved = true;
            try {
                bus = javafmod.FMOD_Studio_System_GetBus(PATH);
                if (bus != 0L) saved = javafmod.FMOD_Studio_Bus_GetVolume(bus);
                else WindSwayMod.trace("window bus not found: " + PATH);
            } catch (Throwable t) {
                bus = 0L;
                WindSwayMod.trace("window bus lookup failed", t);
            }
        }
        return bus != 0L;
    }

    public static String statusLine() {
        if (!resolved) return "untouched";
        if (bus == 0L) return "unavailable";
        return muted ? "muted" : "open";
    }

    // A new world reloads the banks; the handle and its stored volume go stale.
    public static void rearm() {
        if (muted) set(false);
        resolved = false;
        bus = 0L;
        saved = 1.0f;
    }
}
