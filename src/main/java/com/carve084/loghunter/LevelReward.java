package com.carve084.loghunter;

import lombok.Getter;
import net.runelite.api.Skill;

@Getter
public class LevelReward implements Reward {
    private final String type = "LEVEL";
    private final Skill skill;
    private final int targetLevel;

    public LevelReward(Skill skill, int targetLevel) {
        this.skill = skill;
        this.targetLevel = targetLevel;
    }
}