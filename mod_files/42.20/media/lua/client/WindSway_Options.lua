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

-- The vanilla tick box reads the forced getter and Apply writes it back.
-- Hooked on toUI (options screen open), not create: MainScreen:create has
-- no pcall around mainOptions:create(), a throw there empties the main menu.
if MainOptions then
    local toUI = MainOptions.toUI
    function MainOptions:toUI()
        if WindSway.javaReady and ModJava.vanillaWindSpriteEffects and not self.windSwayHooked then
            self.windSwayHooked = true
            local opt = self.gameOptions:get("doWindSpriteEffects")
            if opt then
                function opt.toUI(self)
                    local ok, stored = pcall(ModJava.vanillaWindSpriteEffects)
                    if not ok then stored = getCore():getOptionDoWindSpriteEffects() end
                    self.control:setSelected(1, stored)
                end
            end
        end
        toUI(self)
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

-- Preset bands from the wind study (vanilla median 0.12, 74 % below
-- 0.2); Custom = the two sliders. Index order matches addItem below.
local presetBands = {
    { 0.0, 0.0 },   -- Vanilla
    { 0.15, 0.3 },  -- Calm
    { 0.2, 0.4 },   -- Normal
    { 0.3, 0.55 },  -- Windy
}

local windPresetOpt, windFloorOpt, windCeilOpt

local function applyBand()
    local band = presetBands[windPresetOpt:getValue()]
    local lo = band and band[1] or windFloorOpt:getValue()
    local hi = band and band[2] or windCeilOpt:getValue()
    applyToJava("setWindFloor", lo)
    applyToJava("setTreeWindFloor", lo)
    applyToJava("setWindCeil", hi)
end

windPresetOpt = modOptions:addComboBox(
    "windPreset",
    "Wind",
    "The mod's own wind while the game's weather is idle. Vanilla = the game's wind only. Custom uses the two sliders below.")
windPresetOpt:addItem("Vanilla")
windPresetOpt:addItem("Calm")
windPresetOpt:addItem("Normal")
windPresetOpt:addItem("Windy", true)
windPresetOpt:addItem("Custom")
windPresetOpt.onChangeApply = function(self, selected)
    applyBand()
end

-- No custom slider-label formatter (the setName shim is unreliable);
-- the scale hint lives in the description.
windFloorOpt = modOptions:addSlider(
    "baseline",
    "Minimum wind (custom)",
    0.0, 1.0, 0.05,
    0.2,
    "Only with Custom. Both sliders 0 = vanilla wind.")
windFloorOpt.onChangeApply = function(self, value)
    applyBand()
end

windCeilOpt = modOptions:addSlider(
    "baselineMax",
    "Maximum wind (custom)",
    0.0, 1.0, 0.05,
    0.85,
    "Only with Custom. At or below the minimum: a steady wind at the minimum.")
windCeilOpt.onChangeApply = function(self, value)
    applyBand()
end

local weatherTakeoverOpt = modOptions:addTickBox(
    "weatherTakeover",
    "Weather overrides the wind setup",
    true,
    "During rain, fog and storms the game's wind rules, even below your wind band. Off: the band stays as a floor, weather only shows above it. No effect on Vanilla.")
weatherTakeoverOpt.onChangeApply = function(self, value)
    applyToJava("setWeatherTakeover", value)
end

local windSoundOpt = modOptions:addTickBox(
    "windSound",
    "Wind sound",
    true,
    "The wind ambience follows the mod's wind. Sound only, gameplay untouched.")
windSoundOpt.onChangeApply = function(self, value)
    applyToJava("setWindSound", value)
end

-- Combo index 1..3 -> Java level 2..0.
local treeDetailLevels = { 2, 1, 0 }
local treeDetailOpt = modOptions:addComboBox(
    "treeDetail",
    "Tree detail",
    "Lower levels drop the fine branch and leaf motion, the crown still bends.")
treeDetailOpt:addItem("High", true)
treeDetailOpt:addItem("Medium")
treeDetailOpt:addItem("Low")
treeDetailOpt.onChangeApply = function(self, selected)
    applyToJava("setTreeDetail", treeDetailLevels[selected] or 2)
end

-- PZAPI.ModOptions:load() only auto-runs on first Options-screen open.
-- Load+push on OnGameBoot so patches see saved values from frame one.
local function syncToJava()
    PZAPI.ModOptions:load()
    applyToJava("setEnabled", enableOpt:getValue())
    applyBand()
    applyToJava("setWeatherTakeover", weatherTakeoverOpt:getValue())
    applyToJava("setWindSound", windSoundOpt:getValue())
    applyToJava("setTreeDetail", treeDetailLevels[treeDetailOpt:getValue()] or 2)
    if WindSway.javaReady and ModJava.warmUp then
        pcall(ModJava.warmUp)
    end
end

Events.OnGameBoot.Add(syncToJava)

WindSway.syncToJava = syncToJava
WindSway.enableOpt = enableOpt
WindSway.windPresetOpt = windPresetOpt
WindSway.windFloorOpt = windFloorOpt
WindSway.windCeilOpt = windCeilOpt
WindSway.weatherTakeoverOpt = weatherTakeoverOpt
WindSway.windSoundOpt = windSoundOpt
WindSway.treeDetailOpt = treeDetailOpt
