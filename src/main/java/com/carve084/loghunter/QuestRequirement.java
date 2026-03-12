package com.carve084.loghunter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import java.util.Set;

/**
 * A requirement based on the completion of a specific in-game quest.
 */
@Slf4j
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class QuestRequirement implements Requirement {
    private String type = "QUEST";
    private String questName;

    /**
     * A transient cache for the parsed Quest enum.
     * This prevents expensive String-to-Enum parsing on every calculation,
     * which is a significant performance optimization.
     */
    private transient Quest cachedQuest = null;
    private transient boolean invalidQuest = false;

    /**
     * Checks if the player has completed the required quest.
     * This implementation includes a caching layer to avoid repeated, expensive
     * Enum parsing of the quest name string. It also respects the debug set of
     * mocked incomplete quests.
     *
     * @param client The RuneLite Client object to check quest state.
     * @param mockedIncomplete A set of quest names (uppercase with underscores) to treat as INCOMPLETE.
     * @return {@code true} if the quest is completed, {@code false} otherwise.
     */
    @Override
    public boolean isMet(Client client, Set<String> mockedIncomplete) {
        if (questName == null || questName.isEmpty()) return false;

        // If the quest is in our debug mock list, pretend we haven't done it!
        if (mockedIncomplete != null && mockedIncomplete.contains(questName.toUpperCase())) {
            return false;
        }

        // Cache the parsed Enum so we only do the expensive String operation once
        if (cachedQuest == null && !invalidQuest) {
            try {
                cachedQuest = Quest.valueOf(questName.toUpperCase());
            } catch (IllegalArgumentException e) {
                log.warn("Data Entry Error: Unknown quest '{}' found in requirements.", questName);
                invalidQuest = true;
            }
        }

        return cachedQuest != null && cachedQuest.getState(client) == QuestState.FINISHED;
    }
}