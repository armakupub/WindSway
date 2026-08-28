# Wind Sway

Trees, grass and bushes sway with the wind, from light breeze to storm.

I built this for immersion: in vanilla the vegetation stands still most of the time, and the optional "Wind Sprite Effects" only shear sprites between random poses. Wind Sway replaces the animation and the renderer:

- **Trees bend** from the trunk up. Leaves flutter, broad crowns move as a block, evergreens as a stiff cone. Scaled to tree size, jumbos included.
- **Wind has a direction and gusts**: crowns lean with the rain, gusts roll through the forest, storms bend crowns over and hold them.
- **Sway in still air**: two sliders, plants and trees, set the baseline. Stronger weather shows as it is, 0 = vanilla wind only.
- **Every plant moves**: bushes and all wind plants, stiffer ones a little less.
- **Render-only**: water, sounds and aiming stay on the real weather.
- **Own renderer**: grass and trees drawn in batches instead of one draw call per sprite.

Options → Mods → Wind Sway, no other setup: the mod turns the game's wind effects on by itself and ignores the vanilla "Wind Sprite Effects" option while it runs. Turn the mod off and your vanilla setting applies again.

[![Wind Sway teaser](https://img.youtube.com/vi/hekASEhRPB8/maxresdefault.jpg)](https://www.youtube.com/watch?v=hekASEhRPB8)

## Maintenance status

Work in progress: bugs are possible.

## Requirements

- **Project Zomboid** Build 42.20 or later
- **[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853)**, one-time install for any Java mod

## Installation

1. Subscribe to **[ZombieBuddy](https://steamcommunity.com/sharedfiles/filedetails/?id=3619862853)** on the Steam Workshop and follow its one-time setup instructions.
2. Subscribe to **[Wind Sway](https://steamcommunity.com/sharedfiles/filedetails/?id=3782670683)**.
3. Enable both mods in the in-game mod list and launch the game.

On first launch ZombieBuddy asks whether to load Wind Sway's JAR, tick Allow.

## Compatibility

- Safe to add or remove mid-save, the mod stores nothing in the save.

## Multiplayer / dedicated servers

Wind Sway is client-side: the server runs none of its code, each player only sees the effect on their own screen. Two things still have to be in place:

- **On the server**: both mods go into the server's `.ini`, so joining clients download and enable them (keep your other entries, separate with semicolons; `3619862853` = ZombieBuddy, `3782670683` = Wind Sway):

  ```ini
  Mods=ZombieBuddy;WindSway
  WorkshopItems=3619862853;3782670683
  ```

- **On every player's PC**: ZombieBuddy's one-time setup from its Workshop page. The server can't do that part for you.

## Settings

Everything sits under Options → Mods → Wind Sway.

<img src="screenshots/settings.png" alt="Mod options panel">

| Setting | Range / Default | What it does |
|---|---|---|
| Enable Wind Sway | on | Master switch: turns on the game's wind effects and draws swaying vegetation with the mod's own renderer. Off = back to your vanilla settings. |
| Minimum sway (plants) | 0–0.5, default 0.2 | Sway in still air. Stronger weather shows as it is. 0 = vanilla wind only. |
| Minimum sway (trees) | 0–0.5, default 0.2 | Same for tree crowns. 0 = vanilla wind only. |
| Tree detail | High / Medium / Low, default High | Lower levels drop the fine branch and leaf motion, the crown still bends. For weaker GPUs. |

## FAQ

**What does it cost in FPS?**
Some, anything that moves is drawn every frame. Wind Sway batches it and stays well below vanilla's wind option; tree-heavy areas even run faster than vanilla on my machine.

**Why ZombieBuddy, can't this work without it?**
No. The wind effects and the renderer are Java, Lua can't reach them.

**Options → Mods → Wind Sway says "Java part did not load"?**
ZombieBuddy isn't installed, or you declined the prompt. Check the install steps on its Workshop page. To undo a decline, edit `%USERPROFILE%\.zombie_buddy\mod_approvals.json`: in the `"id": "WindSway"` block flip `"decision": false` to `true`, restart PZ.

**Added it to an existing save and nothing changed?**
Subscribing doesn't switch a mod on in saves that already exist. Load Game → select the save → "Choose Mods..." → tick ZombieBuddy and Wind Sway.

**Something looks wrong or doesn't work?**
Report it at [github.com/armakupub/WindSway/issues](https://github.com/armakupub/WindSway/issues), ideally with screenshots and the console.txt of that session (in your Zomboid folder).

**Does it work with mod XYZ?**
¯\_(ツ)_/¯

## Building from Source

One-time setup:

1. Copy `build.local.example` to `build.local` and set `PZ_DIR` to your PZ install (and `JDK_DIR` if your JDK is elsewhere).
2. Ensure `ZombieBuddy.jar` sits next to `projectzomboid.jar` in your PZ install.

Then `./build.sh` compiles, packages `windsway.jar`, and installs to `%USERPROFILE%/Zomboid/mods/WindSway`. PZ must be closed during build (the script aborts otherwise).

## Links

- **GitHub:** https://github.com/armakupub/WindSway
- **Steam Workshop:** https://steamcommunity.com/sharedfiles/filedetails/?id=3782670683
- **Teaser video:** https://www.youtube.com/watch?v=hekASEhRPB8

## Attribution

The idea comes from [Wind Tree Sway](https://steamcommunity.com/sharedfiles/filedetails/?id=3775279084) by Waizee.
Wind Sway is an independent implementation with its own shader and batched renderer.

## License

MIT, see `LICENSE`.
