# Log Hunter Suggestions 📗

Ever stared at your Collection Log and had no idea what to grind next? I built **Log Hunter Suggestions** to cure decision paralysis and help you find the low-hanging fruit. It looks at your current stats, quest completions, and existing unlocks to answer one simple question: **"What's the quickest thing I can finish right now?"**

Instead of spending 30 minutes cross-referencing Wiki guides, let the plugin highlight the fastest wins!

## Main Features

* **Smart Requirements (Quality of Life):** The plugin automatically hides activities you can't actually do yet because of missing quests or low levels. You only see grinds you can start right now.
* **Ironman Friendly:** A toggle in the settings swaps the math to use Ironman-specific drop rates and methods.
* **Quick Slots:** Finds missing items with the best drop rates based on how fast a boss can be cleared.
* **Easy Levels:** Highlights training methods that will get you to your next skill milestone the fastest.
* **Instant Wiki Access:** Top suggestions feature a "Wiki" button linking directly to OSRS Wiki strategy guides.

## Installation

You can install this directly through RuneLite:
1. Open your RuneLite configuration panel.
2. Click the **Plugin Hub** icon (the plug).
3. Search for **Log Hunter Suggestions** and click Install.

## How to get started

RuneLite doesn't know what's in your Collection Log until you show it to the plugin. To get your first suggestions:

1. **Open your Collection Log** in-game.
2. **Click the Tabs and Pages:** Follow the indicator in the side panel and click through the 5 main categories (Bosses, Raids, Minigames, Other, and Clues).
3. **Get Suggestions:** Once the plugin sees your log, the list will populate instantly.

## How the Math Works

To rank your tasks, the "Time to Finish" calculations use optimistic, best case estimates.

* **The Engine:** These rates assume near-peak efficiency (the kind of rates you see in high-level Wiki guides).
* **The Reality:** Unless you're a tick-perfect gaming god, your actual time will likely be a bit slower. Think of these as a baseline—if the plugin says an item is fast to get even at peak rates, it's definitely a good place to start!

---

## For Developers: Under the Hood

If you are interested in the code, here is a quick overview of the architecture. I built this to be as lightweight and non-intrusive on the client as possible.

* **Tech Stack:** Java 11, RuneLite API, Lombok, Gson.
* **Data Pipeline:** Drop rates and requirements are managed in Google Sheets, then processed via a Python script (`convert.py`) into Polymorphic JSON.
* **Event-Driven Performance:** Rather than manually scanning UI widgets, the plugin listens for `ChatMessage` server broadcasts to instantly update the collection log state.
* **Memory & Caching:** The math engine uses `transient` enum caches and a name-to-ID `itemNameCache` for high-performance lookups.
* **Storage:** Data is account-isolated and stored locally at `~/.runelite/log-hunter/<account_hash>/log_data.json`.

### Local Development

1. Clone the repository.
2. Open the project in IntelliJ.
3. Allow Gradle to sync the dependencies.
4. Run the `RuneLite` run configuration to test changes in the development client.

## Credits & Community

* **Data Source:** A massive thank you to **Mukkor** for creating and maintaining the [Log Advisor spreadsheet](https://docs.google.com/spreadsheets/d/1leQSz5gJdO1IqjVuSVpIvpQIA0P6ah18DMR8otnojuE) that powers the math behind these suggestions. (Note: The plugin's data pipeline will be updated periodically to sync with new releases from this sheet).
* **Community:** Come hang out in the [Log Hunters Discord](https://discord.gg/loghunters)! It is a fantastic community of completionists, and I am an active member there.

## Issues & Feedback

Created by **carve084**.

If you find a bug or a drop rate that feels off, please open an issue on GitHub!