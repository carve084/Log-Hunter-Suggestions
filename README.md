# Log Hunter Suggestions 📗

Ever stared at your Collection Log and had no idea what to grind next? I built **Log Hunter Suggestions** to help you find the "low-hanging fruit." It looks at your current stats, quest completions, and existing unlocks to answer one simple question: **"What's the quickest thing I can finish right now?"**

## How it Works

To keep things simple for this initial version, I'm using **optimistic, high-efficiency estimates** for the math.

* **The Rates:** The "Time to Finish" calculations assume you're playing at near-peak efficiency (the kind of rates you see in high-level Wiki guides).
* **Reality Check:** Unless you're a tick-perfect gaming god, your actual time will likely be a bit slower. Think of these as "Best Case Scenarios"—if the plugin says an item is fast to get even at sweaty rates, it's definitely a good place to start!
* **Future Updates:** I'm planning to add "Average Gamer" estimates in a future update, but for now, I'm sticking with the most optimistic outlook.

## Main Features

* **Quick Slots:** Finds missing items with the best drop rates based on how fast a boss can be cleared.
* **Easy Levels:** Highlights training methods that will get you to your next skill milestone the fastest.
* **Smart Requirements:** I've made sure the plugin automatically hides activities you can't actually do yet because of missing quests or low levels.
* **Ironman Friendly:** A simple toggle in the settings swaps the math to use Ironman-specific drop rates and methods.

## How to get started

RuneLite doesn't know what's in your Collection Log until you show it to the plugin. To get your first suggestions:

1. **Open your Collection Log** in-game.
2. **Click the Tabs and Pages:** Follow the indicator in the side panel and click through the 5 main categories (Bosses, Raids, Minigames, Other, and Clues).
3. **Get Suggestions:** Once the plugin sees your log, the list will populate instantly.

## Settings

* **Enforce Recommendations:** Turn this off if you want to see activities that you *can* technically do, but shouldn't yet because your stats are too low for it to be efficient.
* **Suggestion Count:** Choose how many activities you want to see in your list.
* **Debug Mode:** For the curious—see the raw math or manually toggle item IDs to see how the "Time to Reward" changes.

## FAQ

**Is this going to tell me to go 3-tick Teaks?**
Only if it's the fastest way for you to level up! Just remember: the time estimates assume you're doing the method efficiently.

**Does this track my data?**
Nope. Everything stays on your computer in your RuneLite folder. I just want to help you fill that log.

---

*Created by **carve084**. If you find a bug or a drop rate that feels off, please open an issue on GitHub!*
