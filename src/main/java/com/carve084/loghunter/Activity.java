package com.carve084.loghunter;

import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.Skill;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Represents a single in-game activity that can be suggested to the player.
 * This class holds all the static data about an activity, such as its name,
 * kill rates, requirements, and potential rewards. It also contains the core
 * calculation logic to determine the time required to obtain the next reward.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class Activity
{
	private String name;
	private String wikiLink;
	private double killsPerHour;
	private List<Requirement> requirements;
    private List<Requirement> recommended;
    private Map<String, Double> experienceRates;
    private List<Reward> rewards;
    private String difficulty;
    private int totalItemRewards;

    /**
     * A transient cache for parsed Skill enums from the experienceRates map.
     * This prevents expensive String-to-Enum parsing on every game tick,
     * significantly improving performance during calculation loops.
     */
    private transient Map<Skill, Double> cachedSkillRates = null;

    /**
     * A simple data class to hold the results of a time-to-complete calculation.
     * An instance of this is returned by the main calculate method.
     */
    @Getter
    @AllArgsConstructor
    public static class CalculationResult {
        /**
         * The estimated time in hours to achieve the fastest reward.
         * This value will be Double.MAX_VALUE if no rewards are achievable
         * or if requirements are not met.
         */
        private final double hours;
        /**
         * The specific Reward object (either ItemReward or LevelReward)
         * that can be obtained in the shortest amount of time.
         */
        private final Reward fastestReward;
        /** The total number of unique item rewards for this activity that the player is still missing. */
        private final int itemRewardsLeft;
    }

    /**
     * Calculates the estimated time to the next achievable reward from this activity
     * based on the player's current stats, collection log, and plugin configuration.
     * <p>
     * The logic flows in several steps:
     * 1. Checks if all hard `requirements` are met.
     * 2. If configured, checks if all `recommended` stats are met.
     * 3. Calculates the time to the fastest individual `ItemReward` the player is missing.
     * 4. If configured, calculates the time to the fastest `LevelReward` (next level in any skill).
     * 5. Compares these times and returns a result representing the fastest overall option.
     *
     * @param client The RuneLite Client object, used to access player stats and quest states.
     * @param playerLog A map of the player's collection log data (ItemID -> 1 if owned, 0 if not).
     * @param config The plugin's configuration object, used to check user preferences.
     * @param mockedQuests A set of quests to treat as incomplete for debugging purposes.
     * @return A {@link CalculationResult} containing the estimated hours and details of the fastest reward.
     *         If requirements are not met, the hours will be Double.MAX_VALUE.
     */
    public CalculationResult calculate(Client client, Map<Integer, Integer> playerLog, LogHunterConfig config, Set<String> mockedQuests)
    {
        // 1. Check Requirements
        if (requirements != null) {
            for (Requirement req : requirements) {
                if (!req.isMet(client, mockedQuests)) {
                    return new CalculationResult(Double.MAX_VALUE, null, 0);
                }
            }
        }

        // --- Recommended Check ---
        if (config.enforceRecommendations() && recommended != null) {
            for (Requirement req : recommended) {
                if (!req.isMet(client, mockedQuests)) {
                    // If the config is checked and a recommendation isn't met, treat it as a hard requirement failure
                    return new CalculationResult(Double.MAX_VALUE, null, 0);
                }
            }
        }

        double probNoRNGDrop = 1.0;
        double minExactAttempts = Double.MAX_VALUE;
        Reward fastestReward = null;
        double fastestRewardHours = Double.MAX_VALUE;

        Set<Integer> uniqueMissingItems = new HashSet<>();
        boolean previousOwned = true;

        // 2. Calculate Item Rewards
        for (Reward reward : rewards)
        {
            if (!(reward instanceof ItemReward)) continue;

            ItemReward itemReward = (ItemReward) reward;
            boolean isOwned = playerLog.getOrDefault(itemReward.getItemId(), 0) > 0;

            if (!isOwned)
            {
                // We always add it to the set so the UI knows the correct completion percentage!
                uniqueMissingItems.add(itemReward.getItemId());

                // Only calculate TIME if the user wants Log Suggestions
                if (config.suggestCollectionLog())
                {
                    if (itemReward.isRequiresPrevious() && !previousOwned) { /* Skip */ }
                    else
                    {
                        double expectedAttempts = itemReward.getAttempts();

                        if (expectedAttempts <= 0) continue;

                        if (!itemReward.isExact()) {
                            probNoRNGDrop *= (1.0 - (1.0 / expectedAttempts));
                        } else if (expectedAttempts < minExactAttempts) {
                            minExactAttempts = expectedAttempts;
                        }

                        double hoursForItem = expectedAttempts / killsPerHour;
                        if (hoursForItem < fastestRewardHours)
                        {
                            fastestRewardHours = hoursForItem;
                            fastestReward = itemReward;
                        }
                    }
                }
            }
            previousOwned = isOwned;
        }

        int itemRewardsLeft = uniqueMissingItems.size();

        // 3. Calculate Level Rewards (Only if toggled ON)
        if (config.suggestLevels() && experienceRates != null) {

            // Populate the cached Enums if this is the first time running
            if (cachedSkillRates == null) {
                cachedSkillRates = new HashMap<>();
                for (Map.Entry<String, Double> entry : experienceRates.entrySet()) {
                    try {
                        Skill parsedSkill = Skill.valueOf(entry.getKey().toUpperCase());
                        cachedSkillRates.put(parsedSkill, entry.getValue());
                    } catch (IllegalArgumentException e) {
                        // Ignore unknown skills
                    }
                }
            }

            for (Map.Entry<Skill, Double> entry : cachedSkillRates.entrySet()) {
                double expPerHour = entry.getValue();
                if (expPerHour <= 0) continue;

                Skill skill = entry.getKey();
                int currentXp = client.getSkillExperience(skill);
                int currentLevel = Experience.getLevelForXp(currentXp);

                if (currentLevel < 99) {
                    int targetLevel = currentLevel + 1;
                    int xpForNextLevel = Experience.getXpForLevel(targetLevel);
                    int xpRemaining = xpForNextLevel - currentXp;

                    double hoursToNextLevel = xpRemaining / expPerHour;

                    if (hoursToNextLevel < fastestRewardHours) {
                        fastestRewardHours = hoursToNextLevel;
                        fastestReward = new LevelReward(skill, targetLevel);
                    }
                }
            }
        }

        // 4. Final Math
        if (fastestReward == null)
        {
            return new CalculationResult(Double.MAX_VALUE, null, itemRewardsLeft);
        }

        double expectedItemHours = Double.MAX_VALUE;
        // Only calculate aggregate item time if the user wants Log Suggestions
        if (config.suggestCollectionLog() && itemRewardsLeft > 0) {
            double expectedRngAttempts = (probNoRNGDrop < 1.0) ? 1.0 / (1.0 - probNoRNGDrop) : Double.MAX_VALUE;
            double overallExpectedAttempts = Math.min(minExactAttempts, expectedRngAttempts);
            expectedItemHours = overallExpectedAttempts / killsPerHour;
        }

        double totalHours = Math.min(expectedItemHours, fastestRewardHours);

        return new CalculationResult(totalHours, fastestReward, itemRewardsLeft);
    }
}