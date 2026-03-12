package com.carve084.loghunter;

/**
 * An interface representing a potential reward from an Activity.
 * This allows for polymorphic handling of different reward types, such as
 * collection log items or skill levels, deserialized from JSON.
 */
public interface Reward {
    /**
     * Gets the type identifier string for this reward (e.g., "ITEM", "LEVEL").
     * Used primarily during JSON deserialization.
     *
     * @return The reward type.
     */
    String getType();
}