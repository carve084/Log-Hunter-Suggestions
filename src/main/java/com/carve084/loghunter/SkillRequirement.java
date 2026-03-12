package com.carve084.loghunter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import java.util.Set;

/**
 * A requirement based on a player's level in a specific skill.
 */
@Slf4j
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class SkillRequirement implements Requirement {
    private String type = "SKILL";
    private String skill;
    private int level;

    /**
     * A transient cache for the parsed Skill enum.
     * This prevents expensive String-to-Enum parsing on every calculation,
     * which is a significant performance optimization.
     */
    private transient Skill cachedSkill = null;
    private transient boolean invalidSkill = false;

    /**
     * Checks if the player's real skill level meets or exceeds the required level.
     * This implementation includes a caching layer to avoid repeated, expensive
     * Enum parsing of the skill name string.
     *
     * @param client The RuneLite Client object to check skill levels.
     * @param mockedIncomplete Unused for this requirement type.
     * @return {@code true} if the skill level is sufficient, {@code false} otherwise.
     */
    @Override
    public boolean isMet(Client client, Set<String> mockedIncomplete) {
        if (skill == null || skill.isEmpty()) return false;

        // Cache the parsed Enum so we only do the expensive String operation once
        if (cachedSkill == null && !invalidSkill) {
            try {
                cachedSkill = Skill.valueOf(skill.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Data Entry Error: Unknown skill '{}' found in requirements. Please check your spreadsheet.", skill);
                invalidSkill = true;
            }
        }

        return cachedSkill != null && client.getRealSkillLevel(cachedSkill) >= level;
    }
}