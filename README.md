# DriveLog

Ad-free Android app that logs live data from a car or truck through a Bluetooth **ELM327-compatible OBD2 adapter** (for example Veepeak OBDCheck BLE+ or OBDLink LX/MX+), and exports it as CSV for Excel or Google Sheets.

Made for a 2005 Chevy Colorado (J1850 VPW), but works with any 1996+ OBD2 vehicle.

## Install
On your phone, open **https://github.com/AgeMaguro/DriveLog/releases/download/drivelog-latest/DriveLog.apk**, download it and open it. After that, the app updates itself (Settings > Updates).

## What it does
- **Live** tab: tiles for every sensor the vehicle reports (RPM, speed, throttle, pedal, load, temps, fuel trims, O2 sensors, MAF, timing, battery voltage...), a 2-minute chart, and a calculated **gear** (RPM / speed).
- **Record** saves a drive. **Mark** drops a flag when you feel something. **Cruise** lets you note when cruise control is on (OBD2 can't see it).
- Automatic **events**: downshifts (e.g. "Downshift 4->2 +2600 rpm"), RPM flares where no gear matches (possible transmission slip), and sudden throttle jumps.
- **Drives** tab: summary, chart with event markers, a gear histogram with **Learn gears from this drive**, and **Share CSV** / **Save to Downloads** (`Downloads/DriveLog/`).
- **Codes** tab: read stored and pending trouble codes, clear codes.
- **Demo truck** (Settings > Adapter) simulates a drive with a cruise-control downshift, so everything can be tried without an adapter.

## How it's built
- `app/src/main/assets/index.html` is the whole app (HTML + JavaScript), including the ELM327 protocol and the demo truck.
- `MainActivity.java` is a WebView plus a small bridge: classic Bluetooth serial (SPP), file storage for drives, CSV export/share, keep-screen-on, and the updater.
- Every push to `main` builds the APK with GitHub Actions (`.github/workflows/apk.yml`) into the release `drivelog-latest` (APK + `version.txt`). The previous APK is archived in `past-versions/`.
- `debug.keystore` (password `android`) signs every build so updates install over the old version. It is public on purpose; replace it with a secret key before any Play Store release.

Handoff notes for this project live in the private Claude-HQ repo (`Handoffs/2026-11-06_DriveLog-app.md`).
