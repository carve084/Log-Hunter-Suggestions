package com.carve084.loghunter;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.border.TitledBorder;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;

/**
 * The main UI panel for the Log Hunter plugin.
 * This class is responsible for building and managing all Swing components,
 * and for rendering the suggestion list based on the data provided by the
 * main plugin class. It handles various UI states, such as logged out,
 * needing a collection log scan, or displaying suggestions.
 */
public class LogHunterPanel extends PluginPanel
{
    // Main containers
    private final JScrollPane scrollPane;
    private final JPanel mainContainer = new JPanel();

    // Top Result UI
    private final JPanel topResultPanel = new JPanel();
    private final JLabel activityNameLabel = new JLabel();
    private final JLabel timeLabel = new JLabel();
    private final JLabel fastestRewardLabel = new JLabel();
    private final JLabel slotsLabel = new JLabel();
    private final JLabel percentLabel = new JLabel();
    private final JLabel difficultyLabel = new JLabel();
    private final JButton topSkipButton = new JButton("Skip Activity");

    private JLabel slotsTitleLabel;
    private JLabel percentTitleLabel;

    // Runner-up UI
    private final JPanel runnerUpPanel = new JPanel();
    private final JLabel otherSuggestionsTitle = new JLabel("Other Suggestions:");

    // Skipped & Debug UI
    private final JLabel completionLabel = new JLabel("All tracked activities completed!");
    private final JLabel loginMessageLabel = new JLabel("<html><div style='text-align: center; width: 160px;'>Log in to the game to get an activity suggestion.</div></html>");
    private final JLabel scanWarningLabel = new JLabel("<html><div style='text-align: center; width: 160px;'>Please open your Collection Log and click through the pages marked with a * to synchronize your items.</div></html>");

    private final JPanel skippedActivitiesPanel = new JPanel();
    private final JPanel debugPanel = new JPanel();
    private final JTextArea debugLogArea = new JTextArea();
    private final JTextField itemIdField = new JTextField();
    private final JTextField inspectField = new JTextField();

    // Interaction Consumers
    private final Consumer<String> onSkipActivity;
    private final Consumer<String> onUnskipActivity;

    // UI State Tracker for Memory Leak Prevention
    private String currentTopActivityName = null;

    // GridBag Constraints for the main container
    private final GridBagConstraints mainGc;

    /**
     * Constructs the UI panel and all of its sub-components.
     *
     * @param onToggleItem A callback executed when the debug "Toggle Item" button is clicked.
     * @param onInspectActivity A callback executed when the debug "Inspect" button is clicked.
     * @param onSkipActivity A callback executed when any "Skip" button is clicked.
     * @param onUnskipActivity A callback executed when an "Unskip" button is clicked.
     * @param onToggleQuest A callback executed when the debug "Mock Quest" button is clicked.
     * @param onPrintMissingRecs A callback executed when the debug "Print Missing Recs" button is clicked.
     */
    public LogHunterPanel(
            Consumer<Integer> onToggleItem,
            Consumer<String> onInspectActivity,
            Consumer<String> onSkipActivity,
            Consumer<String> onUnskipActivity,
            Consumer<String> onToggleQuest,
            Runnable onPrintMissingRecs)
    {
        this.onSkipActivity = onSkipActivity;
        this.onUnskipActivity = onUnskipActivity;

        setLayout(new BorderLayout());

        // Use GridBagLayout for the scrolling container to force width constraints
        mainContainer.setLayout(new GridBagLayout());
        mainContainer.setBorder(new EmptyBorder(10, 5, 10, 5));

        mainGc = new GridBagConstraints();
        mainGc.fill = GridBagConstraints.HORIZONTAL;
        mainGc.weightx = 1.0;
        mainGc.gridx = 0;
        mainGc.gridy = 0;
        mainGc.insets = new Insets(0, 0, 10, 0);

        scrollPane = new JScrollPane(mainContainer);
        scrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(0, 0));
        scrollPane.getVerticalScrollBar().setBorder(new EmptyBorder(0, 0, 0, 0));

        add(scrollPane, BorderLayout.CENTER);

        // --- 1. TOP RESULT PANEL SETUP ---
        topResultPanel.setLayout(new GridBagLayout());
        topResultPanel.setBorder(new TitledBorder("Top Suggestion"));

        GridBagConstraints c = new GridBagConstraints();
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(2, 2, 2, 2);
        c.gridx = 0;
        c.gridy = 0;

        // Name
        activityNameLabel.setFont(FontManager.getRunescapeBoldFont());
        activityNameLabel.setHorizontalAlignment(JLabel.CENTER);
        activityNameLabel.setForeground(ColorScheme.GRAND_EXCHANGE_LIMIT);
        c.gridwidth = 2;
        topResultPanel.add(activityNameLabel, c);
        c.gridy++;
        c.gridwidth = 1;

        // Spacer
        c.gridwidth = 2;
        topResultPanel.add(Box.createRigidArea(new Dimension(0, 8)), c);
        c.gridy++;
        c.gridwidth = 1;

        // Stats
        addLabelRow(topResultPanel, c, "Est. Time:", timeLabel);
        addLabelRow(topResultPanel, c, "Target:", fastestRewardLabel);
        slotsTitleLabel = addLabelRow(topResultPanel, c, "Slots Left:", slotsLabel);
        percentTitleLabel = addLabelRow(topResultPanel, c, "Log %:", percentLabel);
        addLabelRow(topResultPanel, c, "Difficulty:", difficultyLabel);

        // Top Skip Button
        c.gridwidth = 2;
        c.insets = new Insets(10, 2, 2, 2);
        topSkipButton.setFocusable(false);
        topSkipButton.addActionListener(e -> {
            if (currentTopActivityName != null) {
                onSkipActivity.accept(currentTopActivityName);
            }
        });
        topResultPanel.add(topSkipButton, c);

        // --- 2. RUNNER UP PANEL ---
        runnerUpPanel.setLayout(new BoxLayout(runnerUpPanel, BoxLayout.Y_AXIS));
        runnerUpPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
        otherSuggestionsTitle.setForeground(ColorScheme.BRAND_ORANGE);
        otherSuggestionsTitle.setBorder(new EmptyBorder(0, 2, 5, 0));

        // --- 3. SKIPPED PANEL ---
        skippedActivitiesPanel.setLayout(new BoxLayout(skippedActivitiesPanel, BoxLayout.Y_AXIS));
        skippedActivitiesPanel.setBorder(new TitledBorder("Skipped Activities"));
        skippedActivitiesPanel.setVisible(false);

        // --- 4. DEBUG PANEL ---
        debugPanel.setLayout(new BorderLayout());
        debugPanel.setBorder(new TitledBorder("Debug Mode"));
        debugPanel.setVisible(false);

        debugLogArea.setEditable(false);
        debugLogArea.setOpaque(false);
        debugLogArea.setLineWrap(true);
        debugLogArea.setWrapStyleWord(true);
        debugLogArea.setBorder(new EmptyBorder(5, 5, 5, 5));
        debugPanel.add(debugLogArea, BorderLayout.CENTER);

        JPanel controlsPanel = new JPanel();
        controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));

        JButton printRecsBtn = new JButton("Print Missing Recommendations");
        printRecsBtn.setFocusable(false);
        printRecsBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        printRecsBtn.addActionListener(e -> onPrintMissingRecs.run());
        controlsPanel.add(printRecsBtn);
        controlsPanel.add(Box.createRigidArea(new Dimension(0, 5)));

        Dimension fieldSize = new Dimension(50, 24);
        Dimension minFieldSize = new Dimension(10, 24);

        itemIdField.setPreferredSize(fieldSize);
        itemIdField.setMinimumSize(minFieldSize);

        inspectField.setPreferredSize(fieldSize);
        inspectField.setMinimumSize(minFieldSize);

        JPanel togglePanel = new JPanel(new BorderLayout(5, 0));
        togglePanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        togglePanel.add(new JLabel("Toggle ID:"), BorderLayout.WEST);
        togglePanel.add(itemIdField, BorderLayout.CENTER);
        JButton toggleButton = new JButton("Toggle");
        toggleButton.addActionListener(e -> {
            try {
                int id = Integer.parseInt(itemIdField.getText().trim());
                onToggleItem.accept(id);
                itemIdField.setText("");
            } catch (NumberFormatException ex) {}
        });
        togglePanel.add(toggleButton, BorderLayout.EAST);
        controlsPanel.add(togglePanel);

        JPanel inspectPanel = new JPanel(new BorderLayout(5, 0));
        inspectPanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        inspectPanel.add(new JLabel("Inspect:"), BorderLayout.WEST);
        inspectPanel.add(inspectField, BorderLayout.CENTER);
        JButton inspectButton = new JButton("Search");
        inspectButton.addActionListener(e -> {
            String search = inspectField.getText().trim();
            if (!search.isEmpty()) {
                onInspectActivity.accept(search);
                inspectField.setText("");
            }
        });
        inspectPanel.add(inspectButton, BorderLayout.EAST);
        controlsPanel.add(inspectPanel);

        JPanel questTogglePanel = new JPanel(new BorderLayout(5, 0));
        questTogglePanel.setBorder(new EmptyBorder(5, 0, 0, 0));
        questTogglePanel.add(new JLabel("Mock Quest:"), BorderLayout.WEST);

        JTextField questField = new JTextField();
        questField.setPreferredSize(fieldSize);
        questField.setMinimumSize(minFieldSize);
        questTogglePanel.add(questField, BorderLayout.CENTER);

        JButton questBtn = new JButton("Toggle");
        questBtn.addActionListener(e -> {
            String q = questField.getText().trim();
            if (!q.isEmpty()) {
                onToggleQuest.accept(q);
                questField.setText("");
            }
        });
        questTogglePanel.add(questBtn, BorderLayout.EAST);
        controlsPanel.add(questTogglePanel);

        debugPanel.add(controlsPanel, BorderLayout.SOUTH);

        // --- ASSEMBLE MAIN CONTAINER ---
        completionLabel.setHorizontalAlignment(JLabel.CENTER);
        completionLabel.setVisible(false);

        loginMessageLabel.setHorizontalAlignment(JLabel.CENTER);
        loginMessageLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        loginMessageLabel.setVisible(false);

        scanWarningLabel.setHorizontalAlignment(JLabel.CENTER);
        scanWarningLabel.setForeground(ColorScheme.BRAND_ORANGE);
        scanWarningLabel.setVisible(false);

        mainContainer.add(topResultPanel, mainGc);
        mainGc.gridy++;
        mainContainer.add(runnerUpPanel, mainGc);
        mainGc.gridy++;
        mainContainer.add(completionLabel, mainGc);
        mainGc.gridy++;
        mainContainer.add(loginMessageLabel, mainGc);
        mainGc.gridy++;
        mainContainer.add(scanWarningLabel, mainGc);
        mainGc.gridy++;
        mainContainer.add(skippedActivitiesPanel, mainGc);
        mainGc.gridy++;
        mainContainer.add(debugPanel, mainGc);

        // Filler
        mainGc.gridy++;
        mainGc.weighty = 1.0;
        mainContainer.add(Box.createGlue(), mainGc);
    }

    /**
     * A helper method to create a standard title/value row in a GridBagLayout panel.
     * @param panel The panel to add the row to.
     * @param c The GridBagConstraints to use.
     * @param title The text for the left-aligned title label.
     * @param valueLabel The JLabel to use for the right-aligned value.
     * @return The created title JLabel.
     */
    private JLabel addLabelRow(JPanel panel, GridBagConstraints c, String title, JLabel valueLabel) {
        JLabel titleLabel = new JLabel(title);
        titleLabel.setForeground(Color.LIGHT_GRAY);
        c.gridx = 0;
        c.weightx = 0;
        c.anchor = GridBagConstraints.WEST;
        panel.add(titleLabel, c);

        c.gridx = 1;
        c.weightx = 1;
        c.anchor = GridBagConstraints.EAST;
        valueLabel.setHorizontalAlignment(JLabel.RIGHT);
        panel.add(valueLabel, c);
        c.gridy++;

        return titleLabel;
    }

    /**
     * The main public method for updating the entire UI panel.
     * It takes the latest calculated data and rebuilds the panel components
     * to reflect the current state. This method is responsible for switching
     * between the "logged out," "scan required," and "show suggestions" views.
     * This method must be called on the Swing Event Dispatch Thread.
     *
     * @param suggestions The ranked list of activity suggestions.
     * @param skippedActivities A set of names of currently skipped activities.
     * @param mockedQuests A set of names for quests being mocked as incomplete.
     * @param isDebugMode A flag indicating if debug mode is enabled.
     * @param suggestionLimit The maximum number of runner-up suggestions to display.
     * @param isLoggedIn A flag indicating if the player is currently logged into the game.
     * @param requiresScan A flag indicating if the player needs to scan their collection log.
     */
    public void updateSuggestions(
            List<LogHunterPlugin.ActivitySuggestion> suggestions,
            Set<String> skippedActivities,
            Set<String> mockedQuests,
            boolean isDebugMode,
            int suggestionLimit,
            boolean isLoggedIn,
            boolean requiresScan) // NEW PARAMETER
    {
        SwingUtilities.invokeLater(() ->
        {
            debugPanel.setVisible(isDebugMode);

            // --- HANDLE NOT LOGGED IN STATE ---
            if (!isLoggedIn) {
                topResultPanel.setVisible(false);
                runnerUpPanel.setVisible(false);
                completionLabel.setVisible(false);
                skippedActivitiesPanel.setVisible(false);
                scanWarningLabel.setVisible(false);
                loginMessageLabel.setVisible(true);

                mainContainer.revalidate();
                mainContainer.repaint();
                return;
            }

            loginMessageLabel.setVisible(false);

            // --- HANDLE PENDING SCAN STATE ---
            if (requiresScan) {
                topResultPanel.setVisible(false);
                runnerUpPanel.setVisible(false);
                completionLabel.setVisible(false);
                skippedActivitiesPanel.setVisible(false);
                scanWarningLabel.setVisible(true);

                mainContainer.revalidate();
                mainContainer.repaint();
                return;
            }

            scanWarningLabel.setVisible(false);

            // --- REBUILD SKIPPED PANEL ---
            skippedActivitiesPanel.removeAll();
            if (skippedActivities != null && !skippedActivities.isEmpty()) {
                skippedActivitiesPanel.setVisible(true);
                for (String skippedName : skippedActivities) {
                    JPanel row = new JPanel(new BorderLayout(5, 0));
                    row.setBorder(new EmptyBorder(2, 2, 2, 2));

                    String truncatedName = truncate(skippedName, 23);
                    JLabel nameLabel = new JLabel(truncatedName);
                    nameLabel.setToolTipText(skippedName);
                    nameLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);

                    JButton unskipBtn = new JButton("Unskip");
                    unskipBtn.setMargin(new Insets(2, 4, 2, 4));
                    unskipBtn.addActionListener(e -> onUnskipActivity.accept(skippedName));

                    row.add(nameLabel, BorderLayout.CENTER);
                    row.add(unskipBtn, BorderLayout.EAST);
                    skippedActivitiesPanel.add(row);
                }
            } else {
                skippedActivitiesPanel.setVisible(false);
            }

            // --- HANDLE EMPTY STATE ---
            if (suggestions == null || suggestions.isEmpty())
            {
                topResultPanel.setVisible(false);
                runnerUpPanel.setVisible(false);
                completionLabel.setVisible(true);
                if (isDebugMode) debugLogArea.setText("No incomplete activities left.");
                mainContainer.revalidate();
                mainContainer.repaint();
                return;
            }

            completionLabel.setVisible(false);

            // --- POPULATE TOP RESULT (Index 0) ---
            topResultPanel.setVisible(true);
            LogHunterPlugin.ActivitySuggestion best = suggestions.get(0);
            updateTopResultUI(best);

            // --- POPULATE RUNNER-UPS (Index 1 to Limit) ---
            runnerUpPanel.removeAll();
            int limit = Math.min(suggestionLimit, suggestions.size());

            if (limit > 1) {
                runnerUpPanel.setVisible(true);
                runnerUpPanel.add(otherSuggestionsTitle);
            } else {
                runnerUpPanel.setVisible(false);
            }

            for (int i = 1; i < limit; i++)
            {
                LogHunterPlugin.ActivitySuggestion suggestion = suggestions.get(i);
                JPanel row = createRunnerUpRow(i + 1, suggestion);
                runnerUpPanel.add(row);
                runnerUpPanel.add(Box.createRigidArea(new Dimension(0, 5)));
            }

            // --- DEBUG TEXT ---
            if (isDebugMode)
            {
                StringBuilder sb = new StringBuilder("Debug Data:\n");
                sb.append("Total Activities Loaded: ").append(suggestions.size()).append("\n");

                if (mockedQuests != null && !mockedQuests.isEmpty()) {
                    sb.append("Mocked Incomplete Quests: ").append(String.join(", ", mockedQuests)).append("\n");
                }

                debugLogArea.setText(sb.toString());
            }

            mainContainer.revalidate();
            mainContainer.repaint();
        });
    }

    /**
     * Populates the top suggestion panel with the data from the best activity suggestion.
     * @param best The top-ranked activity suggestion.
     */
    private void updateTopResultUI(LogHunterPlugin.ActivitySuggestion best)
    {
        Activity activity = best.getActivity();
        Activity.CalculationResult result = best.getResult();

        activityNameLabel.setText("<html><div style='text-align: center; width: 150px;'>" + activity.getName() + "</div></html>");
        timeLabel.setText(formatTimeDetailed(result.getHours()));
        difficultyLabel.setText(activity.getDifficulty());

        currentTopActivityName = activity.getName();

        int slotsLeft = result.getItemRewardsLeft();
        int totalSlots = activity.getTotalItemRewards();

        if (totalSlots == 0) {
            slotsTitleLabel.setVisible(false);
            slotsLabel.setVisible(false);
            percentTitleLabel.setVisible(false);
            percentLabel.setVisible(false);
        } else {
            slotsTitleLabel.setVisible(true);
            slotsLabel.setVisible(true);
            percentTitleLabel.setVisible(true);
            percentLabel.setVisible(true);

            if (slotsLeft == 0) {
                slotsLabel.setText(totalSlots + " / " + totalSlots);
                percentLabel.setText("Completed!");
                percentLabel.setForeground(ColorScheme.PROGRESS_COMPLETE_COLOR);
            } else {
                slotsLabel.setText(slotsLeft + " / " + totalSlots);
                double percentComplete = ((double) (totalSlots - slotsLeft) / totalSlots) * 100.0;
                percentLabel.setText(String.format("%.1f%%", percentComplete));
                percentLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            }
        }

        fastestRewardLabel.setText(best.getFastestRewardName());
        Reward fastest = result.getFastestReward();
        if (fastest instanceof ItemReward) {
            fastestRewardLabel.setToolTipText("Item ID: " + ((ItemReward) fastest).getItemId());
        } else if (fastest instanceof LevelReward) {
            LevelReward lvl = (LevelReward) fastest;
            fastestRewardLabel.setToolTipText("Target: Level " + lvl.getTargetLevel() + " " + lvl.getSkill().getName());
        } else {
            fastestRewardLabel.setToolTipText(null);
        }
    }

    /**
     * Creates a single runner-up row component for the suggestion list.
     * @param rank The rank of the suggestion (e.g., 2, 3, 4).
     * @param suggestion The activity suggestion to display.
     * @return A JPanel representing the formatted row.
     */
    private JPanel createRunnerUpRow(int rank, LogHunterPlugin.ActivitySuggestion suggestion)
    {
        JPanel panel = new JPanel(new BorderLayout(5, 0));
        panel.setBorder(new MatteBorder(0, 0, 1, 0, ColorScheme.DARKER_GRAY_COLOR));
        panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

        String fullName = suggestion.getActivity().getName();
        String truncatedName = truncate(fullName, 23);

        JLabel nameLabel = new JLabel(rank + ". " + truncatedName);
        nameLabel.setToolTipText("Activity: " + fullName + " | Target: " + suggestion.getFastestRewardName());
        panel.add(nameLabel, BorderLayout.CENTER);

        JPanel rightPanel = new JPanel(new BorderLayout(5, 0));
        rightPanel.setOpaque(false);

        JLabel timeLbl = new JLabel(formatTime(suggestion.getResult().getHours()));
        timeLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        timeLbl.setFont(FontManager.getRunescapeSmallFont());

        JButton skipBtn = new JButton("X");
        skipBtn.setPreferredSize(new Dimension(20, 20));
        skipBtn.setMargin(new Insets(0, 0, 0, 0));
        skipBtn.setToolTipText("Skip this activity");
        skipBtn.setFocusable(false);
        skipBtn.addActionListener(e -> onSkipActivity.accept(suggestion.getActivity().getName()));

        rightPanel.add(timeLbl, BorderLayout.CENTER);
        rightPanel.add(skipBtn, BorderLayout.EAST);

        panel.add(rightPanel, BorderLayout.EAST);

        return panel;
    }

    /**
     * Truncates a string to a maximum length, adding an ellipsis if truncated.
     * @param text The string to truncate.
     * @param maxLength The maximum allowed length.
     * @return The potentially truncated string.
     */
    private String truncate(String text, int maxLength)
    {
        if (text == null || text.length() <= maxLength) return text;
        return text.substring(0, maxLength) + "…";
    }

    /**
     * Formats a given duration in hours into a detailed hh:mm:ss string.
     * @param hours The duration in fractional hours.
     * @return A formatted string in the format "h:mm:ss".
     */
    private String formatTimeDetailed(double hours)
    {
        if (hours == Double.MAX_VALUE) return "N/A";
        long totalSeconds = (long) (hours * 3600);
        long displayHours = totalSeconds / 3600;
        long remainingSeconds = totalSeconds % 3600;
        long displayMinutes = remainingSeconds / 60;
        long displaySeconds = remainingSeconds % 60;

        return String.format("%d:%02d:%02d", displayHours, displayMinutes, displaySeconds);
    }

    /**
     * Formats a given duration in hours into a simplified "Xh Ym" or "Ym" string.
     * @param hours The duration in fractional hours.
     * @return A formatted, simplified time string.
     */
    private String formatTime(double hours)
    {
        if (hours == Double.MAX_VALUE) return "N/A";
        long totalSeconds = (long) (hours * 3600);
        long displayHours = totalSeconds / 3600;
        long remainingSeconds = totalSeconds % 3600;
        long displayMinutes = remainingSeconds / 60;

        if (displayHours > 0) return String.format("%dh %dm", displayHours, displayMinutes);
        return String.format("%dm", displayMinutes);
    }
}