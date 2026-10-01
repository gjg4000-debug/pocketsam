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
- **Airflow:** duct diameter (inches) and FPM give CFM = FPM × π × (diameter ÷ 12)² ÷ 4.
- **Nutrient pump:** max GPH × speed % × stroke % × run time = gal/day, where run time per day = 24 hrs × ON seconds ÷ (ON seconds + OFF minutes × 60). Also shows days until the nutrient tank is empty.
- **SAVE** stores the site's numbers and keeps a dated copy for **HISTORY** (one per date; saving again the same day updates it).
- **EXPORT BACKUP / IMPORT** share sites between phones (same file format as the Android app). Import merges readings by date: same site and date = the imported reading replaces yours; other dates are kept.
- **Complete** checkbox: check it and tap CONFIRM (or SAVE), and the site shows green with ✓ in the site list for the rest of the month. Every site goes back to black on the 1st. Complete marks travel with backup files.
- **Site lists:** IMPORT also takes a plain text or CSV list of sites (`County, Site` per line, or a `County:` heading line followed by site names). It only adds sites that are missing. Site lists stay on the phone; nothing is uploaded.
- **EXPORT CSV** gives one row per reading for Excel or Google Sheets.

## Sharing with a Google Sheet (REFRESH)

Everyone's SAVE / CONFIRM goes to one Google Sheet, and REFRESH pulls in everyone's latest.
Setup is done once by the Sheet owner: make a Google Sheet, open Extensions -> Apps Script,
paste in `sync/Code.gs`, change the password on the first line, then Deploy -> New deployment ->
Web app (Execute as: Me, Who has access: Anyone). Each phone taps REFRESH once and enters that
link and the password. The link and password are never stored on GitHub.

## Files

- `index.html` – the whole app
- `manifest.webmanifest`, `sw.js`, `icon-*.png` – home-screen install and offline support
- `sync/Code.gs` – the Google Sheet script used by REFRESH
- `android/MainActivity.kt` – older Android Studio version (no longer updated)
