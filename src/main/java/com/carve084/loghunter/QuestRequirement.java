package com.carve084.loghunter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import java.util.Set;

@Slf4j
@Getter
@AllArgsConstructor
@NoArgsConstructor
public class QuestRequirement implements Requirement {
    private String type = "QUEST";
    private String questName;

    // transient means Gson ignores this variable when saving/loading JSON
    private transient Quest cachedQuest = null;
    private transient boolean invalidQuest = false;

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