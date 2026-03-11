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

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class Activity
{
    private String name;
    private double killsPerHour;
    private List<Requirement> requirements;
    private List<Requirement> recommended;
    private Map<String, Double> experienceRates;

    private List<Reward> rewards;
    private String difficulty;
    private int totalItemRewards;

    // Transient cached map to prevent parsing Enums every tick
    private transient Map<Skill, Double> cachedSkillRates = null;

    @Getter
    @AllArgsConstructor
    public static class CalculationResult {
        private final double hours;
        private final Reward fastestReward;
        private final int itemRewardsLeft;
    }

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

        // --- NEW: Recommended Check ---
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

                // NEW: Only calculate TIME if the user wants Log Suggestions
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
        // NEW: Only calculate aggregate item time if the user wants Log Suggestions
        if (config.suggestCollectionLog() && itemRewardsLeft > 0) {
            double expectedRngAttempts = (probNoRNGDrop < 1.0) ? 1.0 / (1.0 - probNoRNGDrop) : Double.MAX_VALUE;
            double overallExpectedAttempts = Math.min(minExactAttempts, expectedRngAttempts);
            expectedItemHours = overallExpectedAttempts / killsPerHour;
        }

        double totalHours = Math.min(expectedItemHours, fastestRewardHours);

        return new CalculationResult(totalHours, fastestReward, itemRewardsLeft);
    }
}