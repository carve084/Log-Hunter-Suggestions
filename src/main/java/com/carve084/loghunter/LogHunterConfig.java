package com.carve084.loghunter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup("loghunter")
public interface LogHunterConfig extends Config
{
    @ConfigItem(
            keyName = "useIronmanRates",
            name = "Use Ironman Rates",
            description = "If checked, uses completion rates tailored for ironman accounts.",
            position = 1
    )
    default boolean useIronmanRates()
    {
        return false;
    }

    @ConfigItem(
            keyName = "enforceRecommendations",
            name = "Enforce Recommendations",
            description = "Hides activities if you do not meet the recommended stats.",
            position = 2
    )
    default boolean enforceRecommendations()
    {
        return true;
    }

    @ConfigItem(
            keyName = "suggestCollectionLog",
            name = "Suggest Collection Log",
            description = "If checked, the plugin will suggest activities to unlock new collection log slots.",
            position = 3
    )
    default boolean suggestCollectionLog()
    {
        return true;
    }

    @ConfigItem(
            keyName = "suggestLevels",
            name = "Suggest Levels",
            description = "If checked, the plugin will suggest activities to reach your next skill level.",
            position = 4
    )
    default boolean suggestLevels()
    {
        return true;
    }

    @Range(
            min = 1,
            max = 50
    )
    @ConfigItem(
            keyName = "suggestionCount",
            name = "Suggestion Count",
            description = "Number of top activities to display in the list.",
            position = 5
    )
    default int suggestionCount()
    {
        return 5;
    }

    @ConfigItem(
            keyName = "debugMode",
            name = "Debug Mode",
            description = "Shows debug info and allows manual item toggling.",
            position = 6
    )
    default boolean debugMode()
    {
        return false;
    }
}