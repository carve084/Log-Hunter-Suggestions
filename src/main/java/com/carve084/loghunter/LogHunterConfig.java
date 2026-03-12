package com.carve084.loghunter;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

/**
 * Defines the configuration settings for the Log Hunter plugin.
 * Users can change these settings in the RuneLite plugin configuration panel.
 */
@ConfigGroup("loghunter")
public interface LogHunterConfig extends Config
{
    /**
     * Determines whether to use completion rates tailored for main accounts or ironman accounts.
     * @return true to use ironman rates, false for main account rates.
     */
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

    /**
     * Determines whether activities should be hidden if the player does not meet recommended stats.
     * @return true to enforce recommended stats, false to show all activities where hard requirements are met.
     */
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

    /**
     * Determines whether the plugin should suggest activities for completing the collection log.
     * @return true to include collection log slots in the calculation, false to ignore them.
     */
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

    /**
     * Determines whether the plugin should suggest activities for gaining the next skill level.
     * @return true to include skill level-ups in the calculation, false to ignore them.
     */
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

    /**
     * Configures the number of "runner-up" suggestions to display below the top result.
     * @return The maximum number of suggestions to display.
     */
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

    /**
     * Toggles the visibility of the debug panel, which allows for manual data manipulation and inspection.
     * @return true to enable debug mode, false to hide it.
     */
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