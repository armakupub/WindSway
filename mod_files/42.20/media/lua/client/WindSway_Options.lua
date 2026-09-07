WindSway = WindSway or {}

-- Kahlua global from @Exposer.LuaClass; nil when the user declined the
-- mod's JAR at ZombieBuddy's startup prompt.
local ModJava = WindSwayMod
WindSway.javaReady = ModJava ~= nil

-- One setter failing must not take the master switch down with it.
local warned = {}

local function applyToJava(setterName, value)
    if not WindSway.javaReady then return end
    local fn = ModJava[setterName]
    if not fn then
        if not warned[setterName] then
            warned[setterName] = true
            print("[WindSway] Java setter missing: " .. setterName)
        end
        return
    end
    local ok, err = pcall(fn, value)
    if not ok and not warned[setterName] then
        warned[setterName] = true
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
            local opt = self.gameOptions:get("doWindSpriteEffects")
            if opt then
                self.windSwayHooked = true
                function opt.toUI(self)
                    local ok, stored = pcall(ModJava.vanillaWindSpriteEffects)
                    if not ok then stored = false end
                    self.control:setSelected(1, stored)
                end
            end
        end
        toUI(self)
    end
end

-- Texts are translation keys (Translate/<LANG>/UI.json): the options
-- screen runs getText on names and tooltips itself, ModOptions on combo
-- items and descriptions.
local modOptions = PZAPI.ModOptions:create("WindSway", "UI_WindSway_ModName")

if not WindSway.javaReady then
    modOptions:addDescription("UI_WindSway_JavaMissingDescription")
end

local enableOpt = modOptions:addTickBox(
    "enable",
    "UI_WindSway_EnableLabel",
    true,
    "UI_WindSway_EnableTooltip")
enableOpt.onChangeApply = function(self, value)
    applyToJava("setEnabled", value)
end

-- Preset bands from the wind study (vanilla median 0.12, 74 % below
-- 0.2); Custom = the two sliders. Index order matches addItem below and
-- is what ModOptions.ini stores: never reorder, append only.
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
    "UI_WindSway_WindPresetLabel",
    "UI_WindSway_WindPresetTooltip")
windPresetOpt:addItem("UI_WindSway_WindPresetVanilla")
windPresetOpt:addItem("UI_WindSway_WindPresetCalm")
windPresetOpt:addItem("UI_WindSway_WindPresetNormal", true)
windPresetOpt:addItem("UI_WindSway_WindPresetWindy")
windPresetOpt:addItem("UI_WindSway_WindPresetCustom")
-- Apply fires before the screen stores the new value; getValue() in the
-- callback still reads the old one.
windPresetOpt.onChangeApply = function(self, selected)
    self:setValue(selected)
    applyBand()
end

-- No custom slider-label formatter (the setName shim is unreliable);
-- the scale hint lives in the description.
windFloorOpt = modOptions:addSlider(
    "baseline",
    "UI_WindSway_WindFloorLabel",
    0.0, 1.0, 0.05,
    0.2,
    "UI_WindSway_WindFloorTooltip")
windFloorOpt.onChangeApply = function(self, value)
    self:setValue(value)
    applyBand()
end

windCeilOpt = modOptions:addSlider(
    "baselineMax",
    "UI_WindSway_WindCeilLabel",
    0.0, 1.0, 0.05,
    0.85,
    "UI_WindSway_WindCeilTooltip")
windCeilOpt.onChangeApply = function(self, value)
    self:setValue(value)
    applyBand()
end

local weatherTakeoverOpt = modOptions:addTickBox(
    "weatherTakeover",
    "UI_WindSway_WeatherTakeoverLabel",
    true,
    "UI_WindSway_WeatherTakeoverTooltip")
weatherTakeoverOpt.onChangeApply = function(self, value)
    applyToJava("setWeatherTakeover", value)
end

local windSoundOpt = modOptions:addTickBox(
    "windSound",
    "UI_WindSway_WindSoundLabel",
    true,
    "UI_WindSway_WindSoundTooltip")
windSoundOpt.onChangeApply = function(self, value)
    applyToJava("setWindSound", value)
end

local windSoundLevelOpt = modOptions:addSlider(
    "windSoundLevel",
    "UI_WindSway_WindSoundLevelLabel",
    0.0, 1.0, 0.05,
    0.5,
    "UI_WindSway_WindSoundLevelTooltip")
windSoundLevelOpt.onChangeApply = function(self, value)
    applyToJava("setWindSoundLevel", value)
end

-- Combo index 1..3 -> Java level 2..0. The index is what ModOptions.ini
-- stores: never reorder, append only.
local treeDetailLevels = { 2, 1, 0 }
local treeDetailOpt = modOptions:addComboBox(
    "treeDetail",
    "UI_WindSway_TreeDetailLabel",
    "UI_WindSway_TreeDetailTooltip")
treeDetailOpt:addItem("UI_WindSway_TreeDetailHigh", true)
treeDetailOpt:addItem("UI_WindSway_TreeDetailMedium")
treeDetailOpt:addItem("UI_WindSway_TreeDetailLow")
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
    applyToJava("setWindSoundLevel", windSoundLevelOpt:getValue())
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
WindSway.windSoundLevelOpt = windSoundLevelOpt
WindSway.treeDetailOpt = treeDetailOpt
