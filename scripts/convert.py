import os
import csv
import json
import requests
import io

"""
Data Conversion Script for the Log Hunter Plugin.

This script fetches activity data, which is maintained in a public Google Sheet,
and converts it into two JSON files (`activities_main.json` and `activities_iron.json`)
that are bundled with the RuneLite plugin.

It reads two separate CSVs from the Google Sheet:
1.  'RATES_URL': Contains kills-per-hour, experience rates, and requirements.
2.  'MAP_URL': Maps activities to specific item rewards and their drop mechanics.

The script processes this data, combines it into a structured format, and
exports the final lists of activities, one for main accounts and one for ironman accounts.
"""

# --- CONFIGURATION ---
RATES_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vTerCEPRUUTwIsHH9MjRvQmSecIRYnqhaXH2udmKATlP9OzRmRil5MZHgbzVF32QvlJMLLhmVbY5wT0/pub?gid=18799852&single=true&output=csv"
MAP_URL = "https://docs.google.com/spreadsheets/d/e/2PACX-1vTerCEPRUUTwIsHH9MjRvQmSecIRYnqhaXH2udmKATlP9OzRmRil5MZHgbzVF32QvlJMLLhmVbY5wT0/pub?gid=0&single=true&output=csv"

# 1. Get the directory where this script (convert.py) is located
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))

# 2. Go up one level (to project root) and then into src/main/resources
RESOURCE_DIR = os.path.join(SCRIPT_DIR, '..', 'src', 'main', 'resources')

# 3. Create absolute paths for the output files
OUTPUT_MAIN = os.path.join(RESOURCE_DIR, 'activities_main.json')
OUTPUT_IRON = os.path.join(RESOURCE_DIR, 'activities_iron.json')


# Fetches CSV data from a given URL and returns a CSV DictReader object.
def fetch_csv_data(url):
    print(f"Downloading data from {url[:40]}...")
    # CHANGED: Added timeout parameter to prevent infinite hanging
    response = requests.get(url, timeout=10)
    response.raise_for_status()
    return csv.DictReader(io.StringIO(response.text))


# Cleans a string value, removing commas, and converts it to a float. Returns 0.0 on failure.
def clean_number(value):
    if not value: return 0.0
    try:
        return float(value.replace(',', ''))
    except ValueError:
        return 0.0


# Checks if a string value represents a boolean 'true'.
def is_true(val):
    if not val: return False
    return str(val).strip().upper() in ['TRUE', '1', 'YES', 'Y']


# Parses a comma-separated requirement string (e.g., "SKILL:ATTACK:90,QUEST:DT2") into a list of requirement objects.
def parse_requirements(req_str):
    reqs =[]
    if not req_str: return reqs
    parts = req_str.split(',')
    for part in parts:
        segments = part.strip().split(':')
        if len(segments) == 3 and segments[0].strip().upper() == 'SKILL':
            reqs.append(
                {"type": "SKILL", "skill": segments[1].strip().upper(), "level": int(clean_number(segments[2]))})
        elif len(segments) == 2 and segments[0].strip().upper() == 'QUEST':
            reqs.append({"type": "QUEST", "questName": segments[1].strip().upper()})
        # --- NEW: COMBAT LEVEL PARSING ---
        elif len(segments) == 2 and segments[0].strip().upper() == 'COMBAT':
            reqs.append({"type": "COMBAT", "level": int(clean_number(segments[1]))})
    return reqs


# Parses a comma-separated experience rate string (e.g., "ATTACK:15000,STRENGTH:15000") into a dictionary.
def parse_exp(exp_str):
    exp_rates = {}
    if not exp_str: return exp_rates
    parts = exp_str.split(',')
    for part in parts:
        segments = part.strip().split(':')
        if len(segments) == 2:
            exp_rates[segments[0].strip().upper()] = clean_number(segments[1])
    return exp_rates


# Main processing function that reads, combines, and structures the activity data.
# Modified section in process_data (READ RATES)
def process_data(rates_reader, map_reader, kph_col, exp_col):
    activities = {}

    # 1. READ RATES
    for row in rates_reader:
        name = row['Activity name'].strip()
        kph = clean_number(row.get(kph_col, '0'))
        # New: Extract the Wiki link from the spreadsheet
        wiki_link = row.get('Wiki Link', '').strip()

        if name and kph > 0:
            activities[name] = {
                "name": name,
                "wikiLink": wiki_link if wiki_link else None, # New field
                "killsPerHour": kph,
                "requirements": parse_requirements(row.get('Requirements', '')),
                "recommended": parse_requirements(row.get('Recommended', '')),
                "experienceRates": parse_exp(row.get(exp_col, '')),
                "rewards":[],
                "difficulty": "Unknown",
                "totalItemRewards": 0
            }

    # 2. READ MAP
    for row in map_reader:
        activity_name = row['Activity name'].strip()
        if activity_name not in activities: continue

        item_id_str = row.get('Item ID')
        attempts = clean_number(row.get('Drop rate (attempts)'))
        difficulty = row.get('Difficulty', 'Unknown').strip()

        if difficulty: activities[activity_name]['difficulty'] = difficulty
        if not item_id_str or attempts == 0: continue

        try:
            item_id = int(clean_number(item_id_str))

            activities[activity_name]['rewards'].append({
                "type": "ITEM",
                "itemId": item_id,
                "attempts": attempts,
                "exact": is_true(row.get('Exact')),
                "independent": is_true(row.get('Independent')),
                "requiresPrevious": is_true(row.get('Requires previous'))
            })
        except ValueError:
            continue

    # 3. Final processing
    for name, data in activities.items():
        unique_items = set(r['itemId'] for r in data['rewards'] if r['type'] == 'ITEM')
        data['totalItemRewards'] = len(unique_items)

    return [a for a in activities.values() if a['totalItemRewards'] > 0]


# Main execution block.
def main():
    try:
        rates_data = list(fetch_csv_data(RATES_URL))
        map_data = list(fetch_csv_data(MAP_URL))

        print("Processing data for Mains...")
        main_list = process_data(iter(rates_data), iter(map_data), 'Completions/hr (main)', 'Exp/hr (main)')
        with open(OUTPUT_MAIN, 'w', encoding='utf-8') as f:
            json.dump(main_list, f, indent=2)

        print("\nProcessing data for Ironman...")
        iron_list = process_data(iter(rates_data), iter(map_data), 'Completions/hr (iron)', 'Exp/hr (iron)')
        with open(OUTPUT_IRON, 'w', encoding='utf-8') as f:
            json.dump(iron_list, f, indent=2)

    except Exception as e:
        print(f"An error occurred: {e}")


if __name__ == "__main__":
    main()