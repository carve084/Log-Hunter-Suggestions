package com.carve084.loghunter;
import net.runelite.api.Client;
import java.util.Set;

/**
 * An interface representing a condition that a player must meet to undertake an Activity.
 * This allows for polymorphic handling of different requirement types, such as skills,
 * quests, or combat levels, deserialized from JSON.
 */
public interface Requirement {
    /**
     * Gets the type identifier string for this requirement (e.g., "SKILL", "QUEST").
     * Used primarily during JSON deserialization.
     *
     * @return The requirement type.
     */
    String getType();

    /**
     * Checks if the player currently meets this specific requirement.
     *
     * @param client The RuneLite Client object for accessing player data.
     * @param mockedIncomplete A set of quest names to be treated as incomplete, for debugging.
     * @return {@code true} if the requirement is met, {@code false} otherwise.
     */
    boolean isMet(Client client, Set<String> mockedIncomplete);
}