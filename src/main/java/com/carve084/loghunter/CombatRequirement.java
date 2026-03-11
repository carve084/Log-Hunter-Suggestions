package com.carve084.loghunter;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.runelite.api.Client;
import java.util.Set;

@Getter
@AllArgsConstructor
@NoArgsConstructor
public class CombatRequirement implements Requirement {
    private String type = "COMBAT";
    private int level;

    @Override
    public boolean isMet(Client client, Set<String> mockedIncomplete) {
        // Prevent NullPointerExceptions if the plugin calculates while the player entity is loading
        if (client.getLocalPlayer() == null) return false;

        return client.getLocalPlayer().getCombatLevel() >= this.level;
    }
}