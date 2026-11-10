# DriveLog changelog

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
