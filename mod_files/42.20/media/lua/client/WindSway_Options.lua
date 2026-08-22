WindSway = WindSway or {}

-- Kahlua global from @Exposer.LuaClass; nil when the user declined the
-- mod's JAR at ZombieBuddy's startup prompt.
local ModJava = WindSwayMod
WindSway.javaReady = ModJava ~= nil

local function applyToJava(setterName, value)
    if not WindSway.javaReady then return end
    local fn = ModJava[setterName]
    if not fn then
        WindSway.javaReady = false
        print("[WindSway] Java setter missing: " .. setterName)
        return
    end
    local ok, err = pcall(fn, value)
    if not ok then
        WindSway.javaReady = false
        print("[WindSway] Java call " .. setterName .. " failed: " .. tostring(err))
    end
end

local modOptions = PZAPI.ModOptions:create("WindSway", "Wind Sway")

if not WindSway.javaReady then
    modOptions:addDescription("Wind Sway's Java part did not load (ZombieBuddy prompt declined?). Options below have no effect.")
end

local enableOpt = modOptions:addTickBox(
    "enable",
    "Enable Wind Sway",
    true,
    "Master switch: turns on the game's wind path and draws swaying vegetation with the mod's batched renderer. Off = back to your vanilla settings.")
enableOpt.onChangeApply = function(self, value)
    applyToJava("setEnabled", value)
end

modOptions:addDescription("Sway in still air. Wind adds on top.")

-- No custom slider-label formatter (the setName shim is unreliable);
-- the scale hint lives in the description.
-- Renamed keys: saved values of the old additive floor must not carry over.
local windFloorOpt = modOptions:addSlider(
    "plantBaseline",
    "Minimum sway (plants)",
    0.0, 0.5, 0.05,
    0.2,
    "0 = vanilla wind only.")
windFloorOpt.onChangeApply = function(self, value)
    applyToJava("setWindFloor", value)
end

local treeWindFloorOpt = modOptions:addSlider(
    "treeBaseline",
    "Minimum sway (trees)",
    0.0, 0.5, 0.05,
    0.2,
    "0 = vanilla wind only.")
treeWindFloorOpt.onChangeApply = function(self, value)
    applyToJava("setTreeWindFloor", value)
end

-- PZAPI.ModOptions:load() only auto-runs on first Options-screen open.
-- Load+push on OnGameBoot so patches see saved values from frame one.
local function syncToJava()
    PZAPI.ModOptions:load()
    applyToJava("setEnabled", enableOpt:getValue())
    applyToJava("setWindFloor", windFloorOpt:getValue())
    applyToJava("setTreeWindFloor", treeWindFloorOpt:getValue())
end

Events.OnGameBoot.Add(syncToJava)

WindSway.syncToJava = syncToJava
WindSway.enableOpt = enableOpt
WindSway.windFloorOpt = windFloorOpt
WindSway.treeWindFloorOpt = treeWindFloorOpt
