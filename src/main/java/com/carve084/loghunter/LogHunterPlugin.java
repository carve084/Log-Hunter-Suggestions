package com.carve084.loghunter;

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

/**
 * The main entry point for the Log Hunter Suggestions plugin.
 * This class is responsible for initializing the UI panel, loading data,
 * listening to game events, orchestrating suggestion calculations, and
 * handling all interactions with the RuneLite client and the user.
 */
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

	/**
	 * A wrapper class for persisting all plugin data to a single JSON file.
	 * This includes the player's collection log, skipped activities, and scan progress.
	 */
	@lombok.Data
	private static class PluginData {
		private Map<Integer, Integer> logData = new HashMap<>();
		private Set<String> skippedActivities = new HashSet<>();
		private Set<String> mockedIncompleteQuests = new HashSet<>();
		private Set<String> knownPages = new HashSet<>();
		private Set<String> scannedPages = new HashSet<>();
		private Set<String> scannedTabs = new HashSet<>();
	}

    private PluginData pluginData = new PluginData();
    private List<Activity> activities = new ArrayList<>();

	/**
	 * Resolves the file path for the account-specific data storage.
	 * This method creates a sub-directory within the plugin directory named after the
	 * unique account hash to ensures that Collection Log progress and skips are
	 * isolated between different characters.
	 *
	 * @return The {@link File} pointing to the character's log_data.json, or {@code null}
	 * if the account hash is not currently available (e.g., when not logged in).
	 */
	private File getCharacterDataFile()
	{
		long accountHash = client.getAccountHash();
		if (accountHash == -1)
		{
			return null;
		}

		File characterDir = new File(PLUGIN_DIR, String.valueOf(accountHash));
		if (!characterDir.exists())
		{
			if (!characterDir.mkdirs()) {
				log.warn("Failed to create log-hunter directory for account hash.");
			}
		}

		return new File(characterDir, DATA_FILE);
	}

	/**
	 * Called when the plugin is started.
	 * Initializes the custom Gson parser, sets up the UI panel and navigation button,
	 * loads activity and user data, and queues the initial suggestion calculation.
	 */
	@Override
	protected void startUp()
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
		queueCalculateSuggestions();

		log.info("Log Hunter started!");
	}

    /**
     * Called when the plugin is stopped.
     * Persists the current plugin data and removes the navigation button from the toolbar.
     */
    @Override
    protected void shutDown()
    {
        saveData();
        clientToolbar.removeNavigation(navButton);
        log.info("Log Hunter stopped!");
    }

    /**
     * Configures a custom Gson instance with type adapters for handling the polymorphic
     * {@link Reward} and {@link Requirement} classes in the JSON data.
     */
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

    /**
     * Saves the current {@link PluginData} to a JSON file on a background thread
     * to prevent any impact on game performance.
     */
	private void saveData()
	{
		final File file = getCharacterDataFile();
		if (file == null)
		{
			log.debug("Skipping save: No account hash available.");
			return;
		}

		executor.execute(() -> {
			try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8))
			{
				gson.toJson(pluginData, writer);
				log.debug("Saved plugin data for account hash: {}", client.getAccountHash());
			}
			catch (IOException e)
			{
				log.error("Error saving plugin data", e);
			}
		});
	}

	/**
	 * Loads the {@link PluginData} from its JSON file. If the file doesn't exist or is
	 * corrupted, it initializes with a fresh data object. Also triggers the initial
	 * evaluation of the collection log scan requirement.
	 */
	private void loadData()
	{
		executor.execute(() -> {
			File file = getCharacterDataFile();

			if (file == null || !file.exists())
			{
				log.info("No saved data for this account. Please open the 📗 Collection Log to begin scanning.");
				clientThread.invokeLater(() -> {
					pluginData = new PluginData();
					evaluateScanRequirement();
				});
				return;
			}

			try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))
			{
				PluginData loadedData = gson.fromJson(reader, PluginData.class);
				if (loadedData != null)
				{
					clientThread.invokeLater(() -> {
						pluginData = loadedData;
						log.info("Successfully loaded save data for account hash: {}", client.getAccountHash());
						evaluateScanRequirement();
					});
					return;
				}
			}
			catch (Exception e)
			{
				log.warn("Error loading account data. Starting fresh for this character.");
			}

			// Fallback if data was null or threw an exception
			clientThread.invokeLater(() -> {
				pluginData = new PluginData();
				evaluateScanRequirement();
			});
		});
	}

    /**
     * Queues a new suggestion calculation to be run on the client thread.
     * This method acts as a debouncer, ensuring that multiple rapid-fire events
     * (e.g., during login) only result in a single calculation.
     */
    private void queueCalculateSuggestions()
    {
        if (calculationPending) return;
        calculationPending = true;

        clientThread.invokeLater(() -> {
            calculationPending = false;
            calculateSuggestions();
        });
    }

	/**
	 * Determines if the user needs to scan their collection log.
	 * A scan is required if they haven't visited all 5 top-level tabs, or if
	 * the set of known pages contains any pages not present in the set of scanned pages.
	 */
	private void evaluateScanRequirement()
	{
		boolean missingScans = false;

		// 1. Block calculations until all 5 top-level tabs are visited
		if (pluginData.getScannedTabs().size() < 5) {
			missingScans = true;
		}
		// 2. Block if we have zero known pages (brand new open)
		else if (pluginData.getKnownPages().isEmpty()) {
			missingScans = true;
		}
		// 3. Block if any known page hasn't been clicked yet
		else {
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
     * Recursively traverses a widget and its children to find all visible text components.
     * Used to scrape the names of the pages in the collection log.
     *
     * @param root The root widget to start the traversal from.
     * @param results A list to populate with any text-containing widgets found.
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

    /**
     * Fired when the game's state changes.
     * Used to trigger a recalculation when the player logs in.
     * @param event The state change event.
     */
	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();

		if (state == GameState.LOGGED_IN)
		{
			loadData();
			queueCalculateSuggestions();
		}
		else if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING)
		{
			// Clear memory to ensure the next character starts with a clean slate
			pluginData = new PluginData();
			requiresScan = false;
			queueCalculateSuggestions(); // Refreshes UI to "Logged Out" state
		}
	}

    /**
     * Fired when a player's skill level or experience changes.
     * Caches the new level and queues a recalculation to update suggestions
     * that may be affected by the new stat.
     * @param event The stat change event.
     */
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

    /**
     * Fired when a widget is closed.
     * Specifically used to detect the closing of the quest completion scroll,
     * which indicates a potential change in met requirements.
     * @param event The widget closed event.
     */
    @Subscribe
    public void onWidgetClosed(WidgetClosed event)
    {
        // Removed WidgetInfo. Trigger recalculation if the player closed the "Quest Completed" pop-up
        if (event.getGroupId() == InterfaceID.QUESTSCROLL)
        {
            queueCalculateSuggestions();
        }
    }

    /**
     * Fired when a plugin configuration value is changed.
     * Triggers a data reload if ironman rates are toggled, or a recalculation
     * for any other relevant config change.
     * @param event The config change event.
     */
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

    /**
     * Fired on every game tick. This method contains the logic for scraping
     * the collection log for item completion data and page scan progress.
     * If any data changes are detected, it saves the new data and queues a
     * recalculation. It is also responsible for dynamically injecting the
     * yellow '*' next to unscanned page names.
     * @param event The game tick event.
     */
	@Subscribe
	public void onGameTick(GameTick event)
	{
		// SHORT-CIRCUIT: If the Bosses tab widget isn't loaded, the log isn't open. Abort immediately!
		Widget bossesTab = client.getWidget(InterfaceID.COLLECTION, 4);
		if (bossesTab == null || bossesTab.isHidden()) {
			return;
		}

		Widget itemsContainer = client.getWidget(InterfaceID.Collection.ITEMS_CONTENTS);
		boolean dataChanged = false;

		// --- NEW: 0. Scrape and Decorate Top-Level Tabs ---
		Widget[] tabWidgets = {
			client.getWidget(InterfaceID.COLLECTION, 4), // Bosses
			client.getWidget(InterfaceID.COLLECTION, 5), // Raids
			client.getWidget(InterfaceID.COLLECTION, 6), // Clues
			client.getWidget(InterfaceID.COLLECTION, 7), // Minigames
			client.getWidget(InterfaceID.COLLECTION, 8)  // Other
		};

		for (Widget tabWidget : tabWidgets) {
			if (tabWidget != null && !tabWidget.isHidden()) {

				Widget[] children = tabWidget.getDynamicChildren();
				// Ensure the children array exists and has at least 4 elements (index 0 to 3)
				if (children != null && children.length > 3) {
					Widget textWidget = children[3];

					String rawText = textWidget.getText();
					if (rawText == null || rawText.isEmpty()) continue;

					String cleanText = net.runelite.client.util.Text.removeTags(rawText).replace("*", "").trim();

					// Selected tab is 0xFFA82F, unselected is 0xFF981F
					boolean isActive = (textWidget.getTextColor() == 0xFFA82F);

					if (isActive && !pluginData.getScannedTabs().contains(cleanText)) {
						pluginData.getScannedTabs().add(cleanText);
						dataChanged = true;
					}

					// Inject or remove the * flag for the top-level tabs
					boolean isScanned = pluginData.getScannedTabs().contains(cleanText);
					boolean hasStar = rawText.contains("*");

					if (!isScanned && !hasStar) {
						textWidget.setText(rawText + " <col=ffff00>*</col>");
					} else if (isScanned && hasStar) {
						textWidget.setText(rawText.replace(" <col=ffff00>*</col>", "").replace("*", "").trim());
					}
				}
			}
		}

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

    /**
     * Loads the list of all trackable activities from the appropriate JSON file.
     * The file used (main vs. ironman) depends on the plugin configuration.
     */
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

	/**
	 * The core logic engine of the plugin. This method iterates through all loaded activities,
	 * calculates the time to the next reward for each one, sorts the results, and passes
	 * the ranked list to the UI panel for display. It also handles the special UI states
	 * for being logged out or requiring a collection log scan.
	 */
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
				false, // requiresScan
				false  // isFullyCompleted (Irrelevant here)
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
				true,  // requiresScan
				false  // isFullyCompleted (Irrelevant here)
			);
			return;
		}

		// 3. We are logged in and scanned! Calculate global completion state.
		Set<Integer> trackedItemIds = new HashSet<>();
		for (Activity activity : activities) {
			for (Reward reward : activity.getRewards()) {
				if (reward instanceof ItemReward) {
					trackedItemIds.add(((ItemReward) reward).getItemId());
				}
			}
		}

		int obtainedCount = 0;
		for (Integer itemId : trackedItemIds) {
			if (pluginData.getLogData().getOrDefault(itemId, 0) == 1) {
				obtainedCount++;
			}
		}

		boolean isFullyCompleted = (!trackedItemIds.isEmpty() && obtainedCount >= trackedItemIds.size());

		// 4. Do the math for valid activities.
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

		// 5. Pass results to panel.
		panel.updateSuggestions(
			rankedSuggestions,
			pluginData.getSkippedActivities(),
			pluginData.getMockedIncompleteQuests(),
			config.debugMode(),
			config.suggestionCount(),
			true,  // isLoggedIn
			false, // requiresScan
			isFullyCompleted // NEW: Passes the evaluated state to the UI
		);
	}

    /**
     * Adds an activity to the set of skipped activities and triggers a save and recalculation.
     * @param activityName The name of the activity to skip.
     */
    private void skipActivity(String activityName) {
        pluginData.getSkippedActivities().add(activityName);
        saveData();
        queueCalculateSuggestions();
    }

    /**
     * Removes an activity from the set of skipped activities and triggers a save and recalculation.
     * @param activityName The name of the activity to unskip.
     */
    private void unskipActivity(String activityName) {
        pluginData.getSkippedActivities().remove(activityName);
        saveData();
        queueCalculateSuggestions();
    }

    /**
     * Toggles the completion status of a specific item ID for debugging purposes.
     * @param itemId The item ID to toggle.
     */
    private void toggleItemStatus(int itemId)
    {
        int currentStatus = pluginData.getLogData().getOrDefault(itemId, 0);
        int newStatus = (currentStatus == 0) ? 1 : 0;

        pluginData.getLogData().put(itemId, newStatus);
        log.info("DEBUG: Toggled item ID {} to status {}", itemId, newStatus);

        saveData();
        queueCalculateSuggestions();
    }

    /**
     * Toggles a quest's status to be mocked as "incomplete" for debugging purposes.
     * @param questName The name of the quest to toggle.
     */
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

    /**
     * Prints a list of activities to the in-game chat for which the player meets the
     * hard requirements but not the recommended ones.
     */
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

    /**
     * Formats a duration in hours into a h:mm:ss string.
     * @param hours The duration in fractional hours.
     * @return A formatted time string.
     */
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

    /**
     * Provides a detailed report on a specific activity for debugging purposes,
     * displaying it in a JOptionPane dialog.
     * @param searchString The name of the activity to inspect.
     */
    private void inspectActivity(String searchString)
    {
        if (client.getGameState() != GameState.LOGGED_IN) {
			javax.swing.SwingUtilities.invokeLater(() ->
				javax.swing.JOptionPane.showMessageDialog(
					panel,
					"Please log in to the game to inspect activities.",
					"Not Logged In",
					javax.swing.JOptionPane.WARNING_MESSAGE
				)
			);
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

        javax.swing.SwingUtilities.invokeLater(() ->
			javax.swing.JOptionPane.showMessageDialog(
				panel,
				finalReport,
				title,
				javax.swing.JOptionPane.INFORMATION_MESSAGE
			)
		);
    }

    /**
     * Provides the plugin's configuration object to the Guice dependency injector.
     * @param configManager The RuneLite ConfigManager.
     * @return The configuration object for this plugin.
     */
    @Provides
    LogHunterConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(LogHunterConfig.class);
    }

    /**
     * A simple data class to hold a calculated activity suggestion, pairing the
     * {@link Activity} with its {@link Activity.CalculationResult} and the
     * formatted name of its fastest reward.
     */
    @lombok.Getter
    @lombok.AllArgsConstructor
    public static class ActivitySuggestion {
        private final Activity activity;
        private final Activity.CalculationResult result;
        private final String fastestRewardName;
    }
}