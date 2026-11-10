# DriveLog

Ad-free Android app that logs live data from a car or truck through a Bluetooth **ELM327-compatible OBD2 adapter** (for example Veepeak OBDCheck BLE+ or OBDLink LX/MX+), and exports it as CSV for Excel or Google Sheets.

Made for a 2005 Chevy Colorado (J1850 VPW), but works with any 1996+ OBD2 vehicle.

## Install
On your phone, open **https://github.com/AgeMaguro/DriveLog/releases/download/drivelog-latest/DriveLog.apk**, download it and open it. After that, the app updates itself (Settings > Updates).

## What it does
- **Live** tab: tiles for every sensor the vehicle reports (RPM, speed, throttle, pedal, load, temps, fuel trims, O2 sensors, MAF, timing, battery voltage...), a 2-minute chart, and a calculated **gear** (RPM / speed).
- **Vehicles** (Settings > Vehicle): each vehicle keeps its own gear ratios, axle ratio and tire size. DriveLog reads the VIN when it connects, switches to the matching vehicle, and tags each drive with it.
- **Hold** a sensor tile (or a sensor's name in Settings) to learn what it is, why it matters and what normal looks like. A tap charts it.
- **Record** saves a drive. **Mark** drops a flag when you feel something (tap it afterwards to label it: Hesitation, Surge, Stumble...). **Cruise** lets you note when cruise control is on (OBD2 can't see it).
- **Automatic** (Settings): connects by itself when it finds your adapter, starts recording when the engine is running, and saves the drive when RPM has read zero for 20 s (changeable). Stopping by hand pauses auto-record until the engine has been turned off.
- **Background logging** (Settings > Keep logging in the background, on by default): a foreground service with a notification keeps the connection and recording going with the app closed and the screen off, and starts again after a phone restart.
- Automatic **events**: downshifts (e.g. "Downshift 4->2 +2600 rpm"), RPM flares where no gear matches (possible transmission slip), and sudden throttle jumps.
- **Drives** tab: summary, chart with event markers, a gear histogram with **Learn gears from this drive**, and **Share CSV** / **Save to Downloads** (`Downloads/DriveLog/`). Give a drive a name, tap any event to see all readings at that moment or jump the charts to it, and see **fuel trims** (short and long term) averaged for idle / cruise / accelerating / decelerating.
- **Compare…** (Drives tab): pick two drives for a side-by-side summary, fuel trims by driving state with the difference, and an overlay chart of any shared sensor on one scale (line them up at the start or at first movement).
- **Codes** tab: read stored and pending trouble codes, clear codes.
- **Demo truck** (Settings > Adapter) simulates a drive with a cruise-control downshift, so everything can be tried without an adapter.

## How it's built
- `app/src/main/assets/index.html` is the whole screen UI (HTML + JavaScript). On Android it only displays what the engine pushes and sends it commands; the old in-page ELM327 engine and demo truck remain for the browser preview.
- The logging engine is plain Java (`Engine`, `Elm`, `Pids`, `Config`, `Json`, `FileStore`, `DemoTransport`): Bluetooth/ELM327 conversation, polling, gear and event detection, auto-connect, auto-record, writing drive files. It has no Android imports, so `tests/EngineSelfTest.java` runs it on a PC against a simulated truck.
- Android parts: `LogService` (foreground service, notification, wake lock), `BtTransport` (Bluetooth serial), `EngineHost` (one engine per app process, links it to the page and service), `BootReceiver`, and `MainActivity` (WebView plus a small bridge for engine commands, file storage, CSV export/share, keep-screen-on, the updater).
- Every push to `main` builds the APK with GitHub Actions (`.github/workflows/apk.yml`) into the release `drivelog-latest` (APK + `version.txt`). The previous APK is archived in `past-versions/`.
- `debug.keystore` (password `android`) signs every build so updates install over the old version. It is public on purpose; replace it with a secret key before any Play Store release.

Handoff notes for this project live in the private Claude-HQ repo (`Handoffs/2026-11-06_DriveLog-app.md`).
