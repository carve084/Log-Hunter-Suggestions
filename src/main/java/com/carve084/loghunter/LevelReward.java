package com.carve084.loghunter;

import lombok.Getter;
import net.runelite.api.Skill;

/**
 * Represents a reward that is the achievement of the next level in a given skill.
 * This is a transient object, created dynamically during calculation, not loaded from JSON.
 */
@Getter
public class LevelReward implements Reward {
    private final String type = "LEVEL";
    private final Skill skill;
    private final int targetLevel;

    /**
     * Constructs a new LevelReward.
     * @param skill The skill being trained.
     * @param targetLevel The next level to be achieved.
     */
    public LevelReward(Skill skill, int targetLevel) {
        this.skill = skill;
        this.targetLevel = targetLevel;
    }
}