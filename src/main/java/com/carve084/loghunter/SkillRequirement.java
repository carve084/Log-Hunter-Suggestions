package com.carve084.loghunter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Skill;
import java.util.Set;

@Slf4j
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class SkillRequirement implements Requirement {
    private String type = "SKILL";
    private String skill;
    private int level;

    // transient means Gson ignores this variable when saving/loading JSON
    private transient Skill cachedSkill = null;
    private transient boolean invalidSkill = false;

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