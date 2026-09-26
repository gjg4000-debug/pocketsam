# Pocket SAM

A field logger for chemical feed and biofilter readings, organized by county/city and site.
Works offline. Each person's data stays on their own device.

**Open the app:** https://gjg4000-debug.github.io/pocketsam/

## Install it like an app

- **iPhone:** open the link in **Safari** → Share → **Add to Home Screen**. Always open it from that icon.
- **Android:** open the link in **Chrome** → ⋮ → **Add to Home screen** (or **Install app**).
- **Windows:** open the link in **Edge** → ⋯ → **Apps** → **Install this site as an app**.

## What it does

- Pick a county/city, then a site. Each site keeps its current settings.
- **CHEM FEED GPD** = (Pump1 mL/min × 60 × Pump1 hrs + Pump2 mL/min × 60 × Pump2 hrs) ÷ 3785.41. A blank timer counts as 24 hrs.
- **Nutrient pump:** max GPH × speed % × stroke % × run time = gal/day, where run time per day = 24 hrs × ON seconds ÷ (ON seconds + OFF minutes × 60). Also shows days until the nutrient tank is empty.
- **SAVE** stores the site's numbers and keeps a dated copy for **HISTORY** (one per date; saving again the same day updates it).
- **EXPORT BACKUP / IMPORT** share sites between phones (same file format as the Android app). Import adds sites; a site with the same county and name is replaced.
- **EXPORT CSV** gives one row per reading for Excel or Google Sheets.

## Files

- `index.html` – the whole app
- `manifest.webmanifest`, `sw.js`, `icon-*.png` – home-screen install and offline support
- `android/MainActivity.kt` – source for the Android (Android Studio) version
