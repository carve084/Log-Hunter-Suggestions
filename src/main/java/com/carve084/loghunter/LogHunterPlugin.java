package com.carve084.loghunter;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonObject;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.inject.Provides;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.IOException;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@PluginDescriptor(
        name = "Log Hunter Suggestions"
)
public class LogHunterPlugin extends Plugin
{
    private static final File PLUGIN_DIR = new File(RuneLite.RUNELITE_DIR, "log-hunter");
    private static final String DATA_FILE = "log_data.json";

    private static final int COLLECTION_LOG_NULL_ITEM_ID = 6512;
    private static final Set<String> IGNORED_PAGES = Set.of("Bosses", "Raids", "Clues", "Minigames", "Other");

    @Inject
    private Client client;

    @Inject
    private ClientThread clientThread;

    @Inject
    private LogHunterConfig config;

    @Inject
    private ClientToolbar clientToolbar;

    @Inject
    private Gson gson;

    @Inject
    private ItemManager itemManager;

    @Inject
    private ScheduledExecutorService executor;

    private LogHunterPanel panel;
    private NavigationButton navButton;
    private Gson customGson;

    // Transient plugin state to track levels and prevent spam calculation
    private final Map<Skill, Integer> cachedLevels = new HashMap<>();
    private boolean calculationPending = false;
    private boolean requiresScan = false;

    @lombok.Data
    private static class PluginData {
        private Map<Integer, Integer> logData = new HashMap<>();
        private Set<String> skippedActivities = new HashSet<>();
        private Set<String> mockedIncompleteQuests = new HashSet<>();
        private Set<String> knownPages = new HashSet<>();
        private Set<String> scannedPages = new HashSet<>();
    }

    private PluginData pluginData = new PluginData();
    private List<Activity> activities = new ArrayList<>();

    @Override
    protected void startUp() throws Exception
    {
        buildCustomGson();

        panel = new LogHunterPanel(
                itemId -> clientThread.invokeLater(() -> toggleItemStatus(itemId)),
                searchName -> clientThread.invokeLater(() -> inspectActivity(searchName)),
                activityName -> clientThread.invokeLater(() -> skipActivity(activityName)),
                activityName -> clientThread.invokeLater(() -> unskipActivity(activityName)),
                questName -> clientThread.invokeLater(() -> toggleMockQuest(questName)),
                () -> clientThread.invokeLater(this::printMissingRecommendations)
        );

        BufferedImage icon = ImageUtil.loadImageResource(getClass(), "/icon.png");

        navButton = NavigationButton.builder()
                .tooltip("Log Hunter")
                .icon(icon)
                .priority(5)
                .panel(panel)
                .build();

        clientToolbar.addNavigation(navButton);

        loadActivities();
        loadData();

        queueCalculateSuggestions();

        log.info("Log Hunter started!");
    }

    @Override
    protected void shutDown() throws Exception
    {
        saveData();
        clientToolbar.removeNavigation(navButton);
        log.info("Log Hunter stopped!");
    }

    private void buildCustomGson()
    {
        JsonDeserializer<Reward> rewardDeserializer = (json, typeOfT, context) -> {
            JsonObject jsonObject = json.getAsJsonObject();
            String type = jsonObject.get("type").getAsString();

            if ("ITEM".equals(type)) {
                return context.deserialize(jsonObject, ItemReward.class);
            }
            return null;
        };

        JsonDeserializer<Requirement> reqDeserializer = (json, typeOfT, context) -> {
            JsonObject jsonObject = json.getAsJsonObject();
            String type = jsonObject.get("type").getAsString();

            if ("SKILL".equals(type)) {
                return context.deserialize(jsonObject, SkillRequirement.class);
            } else if ("QUEST".equals(type)) {
                return context.deserialize(jsonObject, QuestRequirement.class);
            } else if ("COMBAT".equals(type)) {
                return context.deserialize(jsonObject, CombatRequirement.class);
            }
            return null;
        };

        customGson = gson.newBuilder()
                .registerTypeAdapter(Reward.class, rewardDeserializer)
                .registerTypeAdapter(Requirement.class, reqDeserializer)
                .create();
    }

    private void saveData()
    {
        executor.execute(() -> {
            if (!PLUGIN_DIR.exists())
            {
                PLUGIN_DIR.mkdirs();
            }

            File file = new File(PLUGIN_DIR, DATA_FILE);
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))
            {
                gson.toJson(pluginData, writer);
                log.debug("Saved plugin data to file.");
            }
            catch (IOException e)
            {
                log.error("Error saving plugin data", e);
            }
        });
    }

    private void loadData()
    {
        File file = new File(PLUGIN_DIR, DATA_FILE);
        if (!file.exists())
        {
            log.info("No saved log data found. Please open the collection log to begin scanning.");
            // ADDED: Force evaluation even on a fresh install!
            evaluateScanRequirement();
            return;
        }

        try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
        {
            PluginData loadedData = gson.fromJson(reader, PluginData.class);
            if (loadedData != null)
            {
                if (loadedData.getLogData() != null) pluginData.setLogData(loadedData.getLogData());
                if (loadedData.getSkippedActivities() != null) pluginData.setSkippedActivities(loadedData.getSkippedActivities());
                if (loadedData.getMockedIncompleteQuests() != null) pluginData.setMockedIncompleteQuests(loadedData.getMockedIncompleteQuests());
                if (loadedData.getKnownPages() != null) pluginData.setKnownPages(loadedData.getKnownPages());
                if (loadedData.getScannedPages() != null) pluginData.setScannedPages(loadedData.getScannedPages());
                log.info("Successfully loaded save data.");
            }
        }
        catch (Exception e)
        {
            log.warn("Old save data format detected or corrupted file. Starting fresh.");
            pluginData = new PluginData();
        }

        evaluateScanRequirement();
    }

    private void queueCalculateSuggestions()
    {
        if (calculationPending) return;
        calculationPending = true;

        clientThread.invokeLater(() -> {
            calculationPending = false;
            calculateSuggestions();
        });
    }

    private void evaluateScanRequirement()
    {
        boolean missingScans = false;

        // If the known pages list is completely empty, it means the user has never
        // opened the collection log since installing the plugin. A scan is strictly required!
        if (pluginData.getKnownPages().isEmpty()) {
            missingScans = true;
        } else {
            for (String known : pluginData.getKnownPages()) {
                if (!pluginData.getScannedPages().contains(known)) {
                    missingScans = true;
                    break;
                }
            }
        }

        if (this.requiresScan != missingScans) {
            this.requiresScan = missingScans;
            queueCalculateSuggestions();
        }
    }

    /**
     * Recursively traverses all nested layers of a widget to find any text components.
     * By starting at the Collection Log's list container (621.9), this isolates just the page names.
     */
    private void getActiveTextWidgets(Widget root, List<Widget> results) {
        if (root == null || root.isHidden()) return;

        if (root.getText() != null && !root.getText().isEmpty()) {
            results.add(root);
        }

        Widget[] dynamicChildren = root.getDynamicChildren();
        if (dynamicChildren != null) {
            for (Widget child : dynamicChildren) getActiveTextWidgets(child, results);
        }

        Widget[] staticChildren = root.getStaticChildren();
        if (staticChildren != null) {
            for (Widget child : staticChildren) getActiveTextWidgets(child, results);
        }

        Widget[] nestedChildren = root.getNestedChildren();
        if (nestedChildren != null) {
            for (Widget child : nestedChildren) getActiveTextWidgets(child, results);
        }
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGGED_IN || event.getGameState() == GameState.LOGIN_SCREEN)
        {
            queueCalculateSuggestions();
        }
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        int currentLevel = event.getLevel();
        Integer previousLevel = cachedLevels.get(event.getSkill());

        if (previousLevel == null || previousLevel != currentLevel)
        {
            cachedLevels.put(event.getSkill(), currentLevel);
            queueCalculateSuggestions();
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        // Removed WidgetInfo. Trigger recalculation if the player closed the "Quest Completed" pop-up
        if (event.getGroupId() == InterfaceID.QUESTSCROLL)
        {
            queueCalculateSuggestions();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (event.getGroup().equals("loghunter"))
        {
            if (event.getKey().equals("useIronmanRates"))
            {
                loadActivities();
                queueCalculateSuggestions();
            }
            else if (event.getKey().equals("debugMode") ||
                    event.getKey().equals("suggestCollectionLog") ||
                    event.getKey().equals("suggestLevels") ||
                    event.getKey().equals("suggestionCount") ||
                    event.getKey().equals("enforceRecommendations"))
            {
                queueCalculateSuggestions();
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
        boolean dataChanged = false;

        // 1. Scraping Categories FIRST to ensure knownPages is fully populated
        Widget listContainer = client.getWidget(InterfaceID.COLLECTION, 9);
        List<Widget> textWidgets = new ArrayList<>();

        if (listContainer != null && !listContainer.isHidden()) {
            getActiveTextWidgets(listContainer, textWidgets);

            for (Widget child : textWidgets) {
                String text = child.getText();
                if (text == null || text.isEmpty()) continue;

                String cleanText = net.runelite.client.util.Text.removeTags(text).replace("*", "").trim();
                if (IGNORED_PAGES.contains(cleanText)) continue;

                if (!pluginData.getKnownPages().contains(cleanText)) {
                    pluginData.getKnownPages().add(cleanText);
                    dataChanged = true;
                }
            }
        }

        // 2. Scraping currently opened page & Items
        if (itemsContainer != null && !itemsContainer.isHidden()) {

            // Extract current page name dynamically from the MAIN container (621.17)
            Widget mainContainer = client.getWidget(InterfaceID.COLLECTION, 17);
            if (mainContainer != null && !mainContainer.isHidden()) {
                List<Widget> mainTexts = new ArrayList<>();
                getActiveTextWidgets(mainContainer, mainTexts);

                for (Widget w : mainTexts) {
                    String cleanText = net.runelite.client.util.Text.removeTags(w.getText()).trim();
                    // If this text exactly matches a known page, it's our header!
                    if (pluginData.getKnownPages().contains(cleanText)) {
                        if (!pluginData.getScannedPages().contains(cleanText)) {
                            pluginData.getScannedPages().add(cleanText);
                            dataChanged = true;
                        }
                        break;
                    }
                }
            }

            Widget[] children = itemsContainer.getDynamicChildren();
            if (children != null) {
                for (Widget child : children) {
                    int itemId = child.getItemId();
                    if (itemId == -1 || itemId == COLLECTION_LOG_NULL_ITEM_ID) continue;

                    boolean isCompleted = (child.getOpacity() == 0);
                    int completedStatus = isCompleted ? 1 : 0;

                    if (!pluginData.getLogData().containsKey(itemId) || pluginData.getLogData().get(itemId) != completedStatus) {
                        pluginData.getLogData().put(itemId, completedStatus);
                        dataChanged = true;
                    }
                }
            }
        }

        // 3. Inject or remove the * flag dynamically (Done last so it reacts instantly)
        for (Widget child : textWidgets) {
            String text = child.getText();
            if (text == null || text.isEmpty()) continue;

            String cleanText = net.runelite.client.util.Text.removeTags(text).replace("*", "").trim();
            if (IGNORED_PAGES.contains(cleanText)) continue;

            boolean isScanned = pluginData.getScannedPages().contains(cleanText);
            boolean hasStar = text.contains("*");

            if (!isScanned && !hasStar) {
                child.setText(text + " <col=ffff00>*</col>");
            } else if (isScanned && hasStar) {
                child.setText(text.replace(" <col=ffff00>*</col>", "").replace("*", "").trim());
            }
        }

        if (dataChanged) {
            log.info("Log data changed. Recalculating and saving...");
            evaluateScanRequirement();
            saveData();
            queueCalculateSuggestions();
        }
    }

    private void loadActivities()
    {
        String jsonFileName = config.useIronmanRates() ? "/activities_iron.json" : "/activities_main.json";
        log.info("Loading activity data from: {}", jsonFileName);

        try
        {
            InputStream inputStream = getClass().getResourceAsStream(jsonFileName);

            if (inputStream == null)
            {
                log.error("Could not find activities.json!");
                return;
            }

            InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<Activity>>(){}.getType();

            activities = customGson.fromJson(reader, listType);

            log.info("Loaded {} activities from JSON.", activities.size());
        }
        catch (Exception e)
        {
            log.error("Error loading activities JSON", e);
        }
    }

    private void calculateSuggestions()
    {
        // 1. Check if logged in. If not, bypass math and tell UI to show login screen.
        if (client.getGameState() != GameState.LOGGED_IN)
        {
            panel.updateSuggestions(
                    new ArrayList<>(),
                    pluginData.getSkippedActivities(),
                    pluginData.getMockedIncompleteQuests(),
                    config.debugMode(),
                    config.suggestionCount(),
                    false, // isLoggedIn
                    false  // requiresScan
            );
            return;
        }

        // 2. Check if a scan is required. If so, bypass heavy math and alert UI.
        if (this.requiresScan)
        {
            panel.updateSuggestions(
                    new ArrayList<>(),
                    pluginData.getSkippedActivities(),
                    pluginData.getMockedIncompleteQuests(),
                    config.debugMode(),
                    config.suggestionCount(),
                    true,  // isLoggedIn
                    true   // requiresScan
            );
            return;
        }

        // 3. We are logged in and scanned! Do the math.
        List<ActivitySuggestion> rankedSuggestions = new ArrayList<>();

        for (Activity activity : activities)
        {
            if (pluginData.getSkippedActivities().contains(activity.getName())) {
                continue;
            }

            Activity.CalculationResult result = activity.calculate(client, pluginData.getLogData(), config, pluginData.getMockedIncompleteQuests());

            if (result.getHours() != Double.MAX_VALUE)
            {
                String rewardName = "Unknown";
                Reward fastest = result.getFastestReward();

                if (fastest instanceof ItemReward) {
                    ItemReward item = (ItemReward) fastest;
                    try {
                        rewardName = itemManager.getItemComposition(item.getItemId()).getName();
                    } catch (Exception ignored) {}
                }
                else if (fastest instanceof LevelReward) {
                    LevelReward level = (LevelReward) fastest;
                    rewardName = "Level " + level.getTargetLevel() + " " + level.getSkill().getName();
                }

                rankedSuggestions.add(new ActivitySuggestion(activity, result, rewardName));
            }
        }

        rankedSuggestions.sort(java.util.Comparator.comparingDouble(s -> s.getResult().getHours()));

        // 4. Pass results to panel.
        panel.updateSuggestions(
                rankedSuggestions,
                pluginData.getSkippedActivities(),
                pluginData.getMockedIncompleteQuests(),
                config.debugMode(),
                config.suggestionCount(),
                true, // isLoggedIn
                false // requiresScan
        );
    }

    private void skipActivity(String activityName) {
        pluginData.getSkippedActivities().add(activityName);
        saveData();
        queueCalculateSuggestions();
    }

    private void unskipActivity(String activityName) {
        pluginData.getSkippedActivities().remove(activityName);
        saveData();
        queueCalculateSuggestions();
    }

    private void toggleItemStatus(int itemId)
    {
        int currentStatus = pluginData.getLogData().getOrDefault(itemId, 0);
        int newStatus = (currentStatus == 0) ? 1 : 0;

        pluginData.getLogData().put(itemId, newStatus);
        log.info("DEBUG: Toggled item ID {} to status {}", itemId, newStatus);

        saveData();
        queueCalculateSuggestions();
    }

    private void toggleMockQuest(String questName) {
        String formatted = questName.toUpperCase().replace(" ", "_");

        if (pluginData.getMockedIncompleteQuests().contains(formatted)) {
            pluginData.getMockedIncompleteQuests().remove(formatted);
            log.info("DEBUG: Un-mocked quest {}", formatted);
        } else {
            pluginData.getMockedIncompleteQuests().add(formatted);
            log.info("DEBUG: Mocked quest {} as INCOMPLETE", formatted);
        }

        saveData();
        queueCalculateSuggestions();
    }

    private void printMissingRecommendations() {
        if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN) {
            log.warn("Cannot check stats while logged out.");
            return;
        }

        client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "<col=ff0000>--- Missing Recommended Stats ---</col>", null);
        boolean foundAny = false;

        for (Activity activity : activities) {
            boolean hardMet = true;
            if (activity.getRequirements() != null) {
                for (Requirement req : activity.getRequirements()) {
                    if (!req.isMet(client, pluginData.getMockedIncompleteQuests())) {
                        hardMet = false;
                        break;
                    }
                }
            }

            if (!hardMet) continue;

            boolean missedRecs = false;
            if (activity.getRecommended() != null) {
                for (Requirement req : activity.getRecommended()) {
                    if (!req.isMet(client, pluginData.getMockedIncompleteQuests())) {
                        missedRecs = true;
                        break;
                    }
                }
            }

            if (missedRecs) {
                client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "Train stats for: " + activity.getName(), null);
                foundAny = true;
            }
        }

        if (!foundAny) {
            client.addChatMessage(net.runelite.api.ChatMessageType.GAMEMESSAGE, "", "None! You meet all recommended stats for available activities.", null);
        }
    }

    private String formatTime(double hours)
    {
        if (hours == Double.MAX_VALUE) return "COMPLETED";
        long totalSeconds = (long) (hours * 3600);
        long displayHours = totalSeconds / 3600;
        long remainingSeconds = totalSeconds % 3600;
        long displayMinutes = remainingSeconds / 60;
        long displaySeconds = remainingSeconds % 60;
        return String.format("%d:%02d:%02d", displayHours, displayMinutes, displaySeconds);
    }

    private void inspectActivity(String searchString)
    {
        if (client.getGameState() != GameState.LOGGED_IN) {
            javax.swing.SwingUtilities.invokeLater(() -> {
                javax.swing.JOptionPane.showMessageDialog(
                        panel,
                        "Please log in to the game to inspect activities.",
                        "Not Logged In",
                        javax.swing.JOptionPane.WARNING_MESSAGE
                );
            });
            return;
        }

        String lowerSearch = searchString.toLowerCase();
        Activity targetActivity = null;

        for (Activity activity : activities)
        {
            if (activity.getName().toLowerCase().contains(lowerSearch))
            {
                targetActivity = activity;
                break;
            }
        }

        if (targetActivity == null)
        {
            log.warn("Debug Inspect: No activity found containing '{}'", searchString);
            return;
        }

        StringBuilder report = new StringBuilder();
        report.append("=== ACTIVITY: ").append(targetActivity.getName()).append(" ===\n");
        report.append("Kills Per Hour: ").append(targetActivity.getKillsPerHour()).append("\n");
        report.append("Log Slots: ").append(targetActivity.getTotalItemRewards()).append("\n\n");

        Activity.CalculationResult currentResult = targetActivity.calculate(client, pluginData.getLogData(), config, pluginData.getMockedIncompleteQuests());
        report.append("Est. Time: ").append(formatTime(currentResult.getHours())).append("\n");
        report.append("Log Slots Left: ").append(currentResult.getItemRewardsLeft()).append("\n\n");

        report.append("--- REQUIREMENTS ---\n");
        if (targetActivity.getRequirements() != null && !targetActivity.getRequirements().isEmpty()) {
            for (Requirement req : targetActivity.getRequirements()) {
                if (req instanceof SkillRequirement) {
                    SkillRequirement sr = (SkillRequirement) req;
                    report.append("- Level ").append(sr.getLevel()).append(" ").append(sr.getSkill()).append("\n");
                } else if (req instanceof QuestRequirement) {
                    QuestRequirement qr = (QuestRequirement) req;
                    report.append("- Quest: ").append(qr.getQuestName()).append("\n");
                } else if (req instanceof CombatRequirement) {
                    CombatRequirement cr = (CombatRequirement) req;
                    report.append("- Combat Level ").append(cr.getLevel()).append("\n");
                }
            }
        } else {
            report.append("None\n");
        }
        report.append("\n");

        report.append("--- RECOMMENDED ---\n");
        if (targetActivity.getRecommended() != null && !targetActivity.getRecommended().isEmpty()) {
            for (Requirement req : targetActivity.getRecommended()) {
                if (req instanceof SkillRequirement) {
                    SkillRequirement sr = (SkillRequirement) req;
                    report.append("- Level ").append(sr.getLevel()).append(" ").append(sr.getSkill()).append("\n");
                } else if (req instanceof QuestRequirement) {
                    QuestRequirement qr = (QuestRequirement) req;
                    report.append("- Quest: ").append(qr.getQuestName()).append("\n");
                }
            }
        } else {
            report.append("None\n");
        }
        report.append("\n");

        report.append("--- EXP/HR ---\n");
        if (targetActivity.getExperienceRates() != null && !targetActivity.getExperienceRates().isEmpty()) {
            for (Map.Entry<String, Double> entry : targetActivity.getExperienceRates().entrySet()) {
                report.append("- ").append(entry.getKey()).append(": ").append(entry.getValue().intValue()).append("\n");
            }
        } else {
            report.append("None\n");
        }
        report.append("\n");

        report.append("--- REWARD TABLE ---\n");
        for (Reward reward : targetActivity.getRewards())
        {
            if (!(reward instanceof ItemReward)) {
                continue;
            }
            ItemReward itemReward = (ItemReward) reward;

            int id = itemReward.getItemId();
            boolean isOwned = pluginData.getLogData().getOrDefault(id, 0) > 0;

            String itemName = "Unknown";
            try {
                itemName = itemManager.getItemComposition(id).getName();
            } catch (Exception e) { /* Ignore */ }

            report.append(isOwned ? "[X] " : "[ ] ");
            report.append(itemName).append(" (ID: ").append(id).append(")\n");

            long denominator = Math.round(itemReward.getAttempts());
            report.append("    Rate: 1/").append(denominator);

            if (itemReward.isExact()) report.append(" | Exact");
            if (itemReward.isIndependent()) report.append(" | Independent");
            if (itemReward.isRequiresPrevious()) report.append(" | Req. Prev");

            report.append("\n");
        }

        final String finalReport = report.toString();
        final String title = "Inspector: " + targetActivity.getName();

        log.info("\n" + finalReport);

        javax.swing.SwingUtilities.invokeLater(() -> {
            javax.swing.JOptionPane.showMessageDialog(
                    panel,
                    finalReport,
                    title,
                    javax.swing.JOptionPane.INFORMATION_MESSAGE
            );
        });
    }

    @Provides
    LogHunterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LogHunterConfig.class);
    }

    @lombok.Getter
    @lombok.AllArgsConstructor
    public static class ActivitySuggestion {
        private final Activity activity;
        private final Activity.CalculationResult result;
        private final String fastestRewardName;
    }
}