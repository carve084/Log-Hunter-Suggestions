package com.carve084.loghunter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Represents a reward that is a specific item for the Collection Log.
 * This is primarily a data class holding information about the item's drop mechanics.
 */
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class ItemReward implements Reward {
    private String type = "ITEM";
    private int itemId;
    private double attempts;
    private boolean exact;
    private boolean independent;
    private boolean requiresPrevious;
}