package com.carve084.loghunter;
import net.runelite.api.Client;
import java.util.Set;

public interface Requirement {
    String getType();
    boolean isMet(Client client, Set<String> mockedIncomplete);
}