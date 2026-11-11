# DriveLog changelog

## 1.0.8 (2026-11-11)
- **Pick a saved vehicle for a new VIN:** when the adapter reports a VIN DriveLog hasn't seen, the popup now lists all your saved vehicles. Tap one to link the VIN to it (it switches to that vehicle), or choose "Add new vehicle". A vehicle that already has a VIN asks before replacing it. If you tap "Not now", tap the "Vehicle:" line on Live, or Settings > Vehicle > "Choose vehicle for connected VIN", to pick later.
- **Recording waits for the choice:** with a new VIN, automatic recording holds for up to 30 s so the drive starts with the right gear ratios; the notification says "New vehicle: open DriveLog to choose it". If nobody answers it records anyway, tagged with the VIN, and picking a vehicle later still assigns the drive to it.

## 1.0.7 (2026-11-09)
- **Quieter background notification:** Settings > Automatic > Background notification: Normal (quiet, with a status-bar icon) or Minimal (no status-bar icon, collapsed, hidden on the lock screen). "Notification settings…" opens Android's page for it, where you can also turn the notification off completely; logging keeps running either way (Android lists it under running apps).

## 1.0.6 (2026-11-09)
- **Vehicle profiles and VIN:** on connect DriveLog asks the vehicle for its VIN (many older vehicles don't report one; that's fine). Each vehicle has its own profile (name, VIN, gear ratios, axle ratio, tire size, learned gearing). When the VIN matches a saved vehicle DriveLog switches to it by itself, also in the background with the app closed; each drive is tagged with the vehicle. A new VIN asks whether to add a new vehicle (or link the VIN to the one you have). Your existing settings become your first vehicle, linked to the first VIN it sees.
- Settings > Vehicle: pick, name, add, delete vehicles. Make, model, year and engine come from the free US NHTSA VIN lookup (needs internet once; the make and year are also worked out offline).
- Drives tab: vehicle shown on each drive when you have more than one, a vehicle filter, a warning when comparing drives from different vehicles, and the vehicle name in CSV file names. "Learn gears from this drive" uses that drive's own gear ratios and updates its own vehicle.

## 1.0.5 (2026-11-09)
- **Sensor explainers:** hold any sensor tile on the Live tab (or a sensor's name in Settings) for half a second to read what it is, why it matters, typical values, and what to watch for, plus its current reading and this session's min/max. Covers every sensor DriveLog can show, and the calculated gear. A short tap still charts the sensor.

## 1.0.4 (2026-11-09)
- **Background logging:** DriveLog can now connect, record and save drives with the app closed and the screen off. The logging engine moved out of the web page into a native Android foreground service (an ongoing "DriveLog" notification shows while it runs, with Mark and Stop buttons). Settings > Automatic > "Keep logging in the background" turns it off (then logging only happens while the app is open, like before).
- It starts by itself when you open the app (or after the phone restarts or the app updates) if an adapter is chosen and Connect automatically is on. While waiting for the adapter it wakes the phone about once a minute to try; once connected it holds the phone awake so readings don't drop. Pressing Stop in the notification (or Disconnect in the app) pauses it until you open the app or tap Connect.
- Android asks once for permission to show notifications (logging works without it, but the notification is how you know it's running).
- The page is now a window onto the engine: close and reopen it while a drive is recording and it picks up the live chart, events and status. Trouble codes are read through the engine between polls.
- The old in-page engine is kept only for the browser preview (demo truck).
- Self-test for the engine (runs on a PC against the simulated truck): `tests/EngineSelfTest.java`.

## 1.0.3 (2026-11-09)
- **Auto start/stop:** Settings > Automatic. Starts recording when the engine is running (RPM 400+ for 2 s) and saves the drive when RPM has read zero for the engine-off delay (default 20 s). If you stop a recording by hand it stays stopped until the engine has been turned off. A dropped adapter link still saves the drive.
- **Auto-connect:** while the app is open and an adapter is chosen, it keeps looking for the adapter (quietly, slower after each miss) and connects when the truck powers it. Pressing Disconnect pauses it until you tap Connect.
- **Event markers:** tap a mark (Live events list or a saved drive) to label it (Hesitation, Surge, Stumble, Rough idle, Hard shift, Noise, False alarm, or your own words). Labels are saved with the drive and added to the CSV event column. Marks show as a blue flag on charts. In a saved drive, tapping any event shows every reading at that moment and can zoom the charts to it.
- **Fuel trim graphs:** each saved drive with trim data gets a Fuel trims card: STFT/LTFT on one fixed scale with a +/-10% band, a strip showing idle / cruise / accelerating / decelerating, and a table of average short, long and total trim per state.
- **Trip comparison:** Drives > Compare… picks two drives: side-by-side stats, fuel trims by driving state with the difference, and an overlay of any shared sensor on one scale. Drives can now be named. Drives recorded in different units compare correctly.
- Charts with a shared scale use round-number axis labels. Demo truck trims run a little higher at idle so the new trim table has something to show.

## 1.0.2 (2026-11-08)
Event detection fixed using the first real drive (2005 Colorado, 23 min).
- Downshifts were missed when a throttle jump fired just before them (shared 4 s cooldown). Each event type now has its own cooldown. On the real drive this finds all 6 kickdowns (two 4->2, four 4->3) instead of 1.
- Gear calculation allows the torque converter to be unlocked (up to 45% above the gear's ratio), so 3rd gear under heavy load is no longer called "RPM flare, possible slip".
- Downshift events show the rpm span, e.g. "Downshift 4->2 (1627->4238 rpm, converter unlocked)".
- Throttle jumps are measured against the throttle 1-3 s earlier and must end at 40% or more, so getting back on the gas after a quick lift no longer counts (13 false events -> 0 on the real drive).

## 1.0.1 (2026-11-06)
First version.
- Bluetooth (classic) connection to ELM327 adapters; protocol setting (J1850 VPW default for the 2005 Colorado, Auto, CAN...).
- Live tiles, 2-minute chart (tap tiles to pick lines), calculated gear with torque-converter hint.
- Recording to app storage, CSV export (share or save to Downloads/DriveLog).
- Events: downshift, RPM flare (possible slip), throttle jump; Mark button; Cruise on/off note.
- Drives: summary stats, chart with event markers, gear histogram, Learn gears from a drive.
- Trouble codes: read stored + pending, clear (with warning).
- Sensor picker (Fast / Normal / Off), units (US / metric), event thresholds.
- Demo truck (simulated 2005 Colorado with a cruise-control downshift on hills).
- Debug log with copyable report; Android crash saver.
- In-app updater (checks version.txt in the GitHub release).
