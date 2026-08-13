# Wind Sway

Trees, grass and bushes sway with the wind, from light breeze to storm.

Vanilla can already animate foliage: the "Wind Sprite Effects" option (Display & Performance) lets grass and bushes move with the weather. Trees stay out of it, and on calm days the wind is so low that even the foliage stands still for hours. Wind Sway is about immersion, bringing some life into an otherwise dead world:

- **Trees join in**: crowns sway with the weather, and the sway scales with tree size, so the big jumbos show real crown play instead of standing frozen.
- **Calm days keep a breeze**: two sliders set the sway in still air for plants and trees. Real weather adds on top, storms still hit like storms.
- **Render-only**: only the sway animation reads the raised wind. Water, sounds, aiming and everything else stay on the real weather.
- **No twitching**: vanilla's random no-cause jitter is removed from trees. Brushing through grass and bushes still rustles them, fading out once the wind sway masks it.

[![Wind Sway teaser](https://img.youtube.com/vi/zXTunrHDK6w/maxresdefault.jpg)](https://www.youtube.com/watch?v=zXTunrHDK6w)

## Maintenance status

Work in progress: I keep optimizing the renderer as time allows. Last tested against PZ build 42.20.2.

Source is MIT-licensed. Forks and improvements are welcome and encouraged.

## Requirements

- **Project Zomboid** Build 42.20 or newer
- **[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853)**: Java bytecode patching framework (required, one-time setup)

## Installation

1. Subscribe to **[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853)** on the Steam Workshop and follow its one-time setup instructions. This step is only needed once: all mods that depend on ZombieBuddy work automatically afterwards.
2. Subscribe to **Wind Sway**.
3. Enable both mods in the in-game mod list and launch the game.

Because Wind Sway ships a Java JAR, the **first** time you launch the game after installing it, ZombieBuddy will show a native approval dialog with the mod name and an `updated` date. Tick `Allow` to approve this specific JAR. A persist-decision checkbox at the bottom saves your choice for future updates.

No other setup: Wind Sway switches the game's wind path on by itself and ignores the vanilla "Wind Sprite Effects" option while it runs. Turn the mod off and the game follows your vanilla setting again.

## Compatibility

- Safe to add or remove mid-save: no save data touched.
- Client-side only (the server runs none of it), but in multiplayer it still has to be on the server's mod list.
- Not compatible with the original Wind Tree Sway mod (declared incompatible in `mod.info`; both drive the same wind path).

## Settings

Everything sits under Options → Mods → Wind Sway.

<img src="screenshots/settings.png" alt="Mod options panel">

| Setting | Range / Default | What it does |
|---|---|---|
| Enable Wind Sway | on | Master switch. While on, the mod drives the game's wind path and draws swaying vegetation with its own batched renderer. |
| Minimum sway (plants) | 0–0.5, default 0.1 | Sway in still air. Wind adds on top. 0 = vanilla wind only. |
| Minimum sway (trees) | 0–0.5, default 0.1 | Same for tree crowns. 0 = vanilla wind only. |

## FAQ

**What does it cost in FPS?**
Some. Swaying vegetation is drawn every frame, so it can never be as cheap as a fully static world. Wind Sway batches that work into a handful of draw calls, which keeps the cost well below the vanilla "Wind Sprite Effects" path; on my machine a hit only shows zoomed far out with a screen full of swaying trees.

**Do I need to enable "Wind Sprite Effects" in Display & Performance?**
No. While Wind Sway is on it drives the game's wind path itself and ignores that option, the mod's master switch is the only one that matters.

**Does it work in multiplayer?**
Yes, client-side only. Every client also needs ZombieBuddy installed.

## Building from Source

One-time setup:

1. Copy `build.local.example` to `build.local` and set `PZ_DIR` to your PZ install (and `JDK_DIR` if your JDK is elsewhere).
2. Ensure `ZombieBuddy.jar` sits next to `projectzomboid.jar` in your PZ install.

Then `./build.sh` compiles, packages `windsway.jar`, and installs to `%USERPROFILE%/Zomboid/mods/WindSway`. PZ must be closed during build (the script aborts otherwise).

## Links

- **GitHub:** https://github.com/armakupub/WindSway
- **Teaser video:** https://www.youtube.com/watch?v=zXTunrHDK6w

## Attribution

The idea comes from [Wind Tree Sway](https://steamcommunity.com/sharedfiles/filedetails/?id=3775279084) by Waizee. Wind Sway is an independent implementation with its own shader and batched renderer, and it fixes the side effects of the original approach: raising the wind through the global climate value also sped up the water animation, shifted the ambient wind sound and added a permanent outdoor aim penalty. Wind Sway raises only the value the vegetation sway reads.

## License

MIT, see `LICENSE`.
