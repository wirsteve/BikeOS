# BikeOS

**A replacement home screen ("OS shell") for the Peloton Bike and Bike+.** You can use any fitness subscription or app, and your bike's resistance, cadence and power are tracked the whole time.

- **Use any app while you ride:** YouTube, YouTube TV, Twitch, Netflix, Spotify, MyFitnessPal, Zwift, Strava, Apple Music, Prime Video, Disney+, Max, Plex and others. If an app isn't installed, BikeOS opens its website in a built-in browser instead.
- **Live stats float over every app:** time, power and zone, cadence, resistance, output (kJ), speed, distance, calories and heart rate.
- **Ride tracking that just works:** a ride starts recording after a few seconds of pedaling, in any app, and saves itself when you stop. You get history, charts, best efforts, streaks and **TCX export** for Strava, Garmin Connect, TrainingPeaks and intervals.icu.
- **Peloton-style resistance features:**
  - Live resistance readout from the bike (0-100).
  - Structured classes with **cadence and resistance target ranges**, countdowns, cues and an on-target score.
  - **Power Zone** training (7 zones from your FTP) and a **20-minute FTP test** that updates your zones automatically.
  - Resistance coaching: BikeOS learns how your bike's resistance maps to power, then tells you "▲ turn up to 42". On a **Bike+** it can drive the resistance motor itself (experimental, see below).
- **Bring other subscriptions:** BikeOS shows up over Bluetooth as a **smart trainer (FTMS)** and a **power meter (Cycling Power)**:
  - **Zwift, Rouvy, MyWhoosh, Kinomap, TrainerRoad:** pair "BikeOS Bike". ERG mode (target watts) and SIM mode (hills) become resistance prompts, or automatic resistance on a Bike+.
  - **Apple Fitness+ / Apple Watch:** pair "BikeOS Bike" as a power meter on the watch (watchOS 10+). Indoor Cycle workouts then record real power and cadence. Fitness+ itself only exists on Apple devices, so you AirPlay or mirror the class onto the bike screen with an AirPlay receiver app. The Fitness+ tile walks you through it.
- **Heart-rate straps:** connect any standard Bluetooth HR monitor.

> BikeOS is independent software. It is not affiliated with or endorsed by Peloton. It relies on undocumented interfaces that a Peloton software update can change at any time.

---

## What "a new OS" means here (and what it doesn't)

The Peloton tablet runs a locked-down build of Android, with Peloton's app acting as the home screen. BikeOS **replaces that home screen and the experience on top of it**. It does not reflash the tablet.

| Layer | Stock Peloton | With BikeOS |
|---|---|---|
| Bootloader, kernel, Android | Peloton | Unchanged (Peloton) |
| Sensor service (`com.onepeloton.affernetservice`) | Peloton | Unchanged. BikeOS reads data from it |
| Home screen / launcher | Peloton app | **BikeOS** |
| Classes | Peloton membership | Any app, BikeOS workouts, Zwift, Apple Fitness+ via AirPlay… |
| Ride tracking | Peloton app | **BikeOS** (plus TCX export) |

A true custom ROM would mean unlocking a signed bootloader and could permanently brick the bike, and you'd lose the sensor service that talks to the flywheel electronics. The launcher approach keeps the bike safe and fully reversible. Uninstalling BikeOS gives you the stock bike back.

### How the sensor data is read (reverse-engineering notes)

The bike's sensor board connects to the tablet over a serial link that only system apps can open. Peloton's own system service reads it and exposes the data over Android Binder. BikeOS speaks that service's protocols directly. The protocol details below were documented by the open-source community (the [Grupetto](https://github.com/selalipop/grupetto) and [PeloMon](https://github.com/ihaque/pelomon) projects):

| Bike | Interface | How BikeOS uses it |
|---|---|---|
| Bike (current OS) | `com.onepeloton.affernetservice.IV1Interface` | `registerCallback` (tx 1). `IV1Callback.onSensorDataChange` (tx 1) pushes a `BikeData` parcel |
| Bike+ (`PLTN-TTR01`) | `com.onepeloton.affernetservice.IBikeInterface` | Polls tx 14 four times a second for a `BikeData` parcel |
| Bike (older OS) | `com.peloton.service.SensorData` Messenger | Repeating requests 1/2/3 (rpm, power, resistance) |

`BikeData` gives rpm, power in centiwatts and resistance. The parcel decoder is in `core/src/main/kotlin/com/bikeos/core/peloton/PelotonBikeData.kt`, with unit tests. Speed is derived from power using PeloMon's regression, the same way the Peloton app does it. BikeOS also filters out the one-sample resistance spikes the ADC produces.

---

## Install

### 1. Get the APK
Every push builds the APKs on GitHub Actions. Open the repo's **Actions** tab, click the latest **Build BikeOS** run, and download the **BikeOS-apk** artifact (`BikeOS-release.apk`).

Or build it yourself (JDK 17 and the Android SDK are required):
```bash
./gradlew :app:assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

### 2. Sideload it onto the bike
Peloton tablets don't have Google Play. You need ADB access. The community guide is the r/pelotoncycle wiki ("howto/root"). It explains how to enable developer options/ADB on your bike's OS version. Note: Peloton has put sideloading behind an active membership on recent OS versions. Once BikeOS is installed it doesn't need one.

```bash
adb install -r BikeOS-release.apk
```

### 3. One-time setup (over ADB, because Peloton hides Android Settings)
```bash
# Let the stats overlay draw on top of other apps
adb shell appops set com.bikeos SYSTEM_ALERT_WINDOW allow

# Make BikeOS the home screen
adb shell cmd package set-home-activity com.bikeos/.ui.HomeActivity

# Allow Bluetooth scanning for heart-rate straps (or tap "Allow Bluetooth" in BikeOS)
adb shell pm grant com.bikeos android.permission.ACCESS_FINE_LOCATION
```
If `set-home-activity` isn't available on your OS version, press the Home button and choose **BikeOS → Always**.

To go back to stock at any time: `adb uninstall com.bikeos`.

### 4. Install the apps you want
Use **Aurora Store** (an anonymous Google Play client) or sideload APKs with `adb install`. Apps that need Google sign-in (YouTube, YouTube TV) may also need microG. Until an app is installed, its tile opens the web version in the BikeOS browser. The browser supports protected video and has a desktop-site toggle, which YouTube TV and Spotify Web need.

---

## Using it

- **Just pedal.** After 3 seconds a free ride starts recording. Open YouTube or Netflix from the home screen and the stats overlay stays on top. Stop pedaling for 3 minutes (configurable) and the ride ends and saves itself.
- **Workouts:** HIIT, Tabata, climbs, endurance, low impact, Power Zone Endurance/Max and the FTP test. Start one, then tap **Watch something while riding**. Targets, countdowns and resistance prompts follow you into any app.
- **Overlay:** drag it by the handle. ⤡ switches to compact mode, ⏸ pauses, ■ ends (tap twice), and 🏠 goes back to BikeOS.
- **History:** charts, best 1/5/20 min power, **Share TCX** (to Strava/Drive/email) or **Save file** (to `/sdcard/Android/data/com.bikeos/files/exports`, then `adb pull`).
- **MyFitnessPal:** the home screen shows calories burned today, with a button that opens MyFitnessPal so you can log them.

### Zwift and other training apps
1. In BikeOS Settings → Bluetooth, make sure **Broadcast** is on.
2. In Zwift's pairing screen, choose **BikeOS Bike** for Power Source, Cadence and Controllable.
3. In SIM mode, hills become resistance prompts. Tune them under "Hill feel" (flat-road resistance and resistance per 1% grade). ERG targets become "turn to NN" prompts based on your bike's learned resistance curve.

Zwift and similar apps can run on the bike itself or on another device, such as a phone, Apple TV or laptop.

### Apple Fitness+ and Apple Watch
1. On the Apple Watch: **Settings → Bluetooth** and pair **BikeOS Bike**. It appears as a cycling power sensor and also provides cadence.
2. Start an Indoor Cycle workout on the watch, either from Fitness+ on your iPhone/iPad or on its own. Your watch records real power and cadence.
3. To show the class on the bike screen, install an AirPlay receiver (the tile links to **AirScreen**) and AirPlay or mirror from your iPhone/iPad. How well protected video mirrors depends on the receiver app.

### Bike+ auto-resistance (experimental, off by default)
The Bike+ resistance motor is set through `IBikeInterface`, but the transaction number isn't public and differs between firmware builds. Rather than guess, and risk sending the wrong command to a motor controller, BikeOS asks you for it:

1. Pull Peloton's service APK: `adb shell pm path com.onepeloton.affernetservice`, then `adb pull <path>`.
2. Open it in [JADX](https://github.com/skylot/jadx) and find `IBikeInterface$Stub`. Note the `TRANSACTION_` constant for the set-resistance method. It takes a single `int` from 0 to 100.
3. Enter that number in **Settings → Bike+ auto-resistance → Transaction code**, then enable it.

BikeOS sends at most one change per second, and it stops trying after 3 failed calls. On the regular Bike, the resistance knob is mechanical, so BikeOS shows turn-up / turn-down prompts instead.

### Custom workouts
Create a `.json` file and push it to `/sdcard/Android/data/com.bikeos/files/workouts/`:
```json
{
  "id": "my-hills",
  "title": "My Hill Repeats",
  "description": "4 x 3 min climbs",
  "category": "CLIMB",
  "difficulty": 7,
  "segments": [
    {"name": "Warm up", "durationSec": 300, "type": "WARMUP", "cadence": {"min": 80, "max": 90}, "resistance": {"min": 25, "max": 35}},
    {"name": "Climb",   "durationSec": 180, "type": "CLIMB",  "cadence": {"min": 65, "max": 75}, "resistance": {"min": 50, "max": 60}, "cue": "Stay seated"},
    {"name": "Recover", "durationSec": 120, "type": "RECOVERY", "powerZone": 1}
  ]
}
```
A file can hold a single workout or a JSON array of workouts. A segment can set `cadence` and `resistance` ranges, a `powerZone` (1-7), or neither (free riding). The categories are `INTERVALS`, `ENDURANCE`, `CLIMB`, `POWER_ZONE`, `LOW_IMPACT`, `TEST` and `CUSTOM`.

---

## Project layout

```
core/   Pure Kotlin, unit-tested, no Android dependency
  peloton/  BikeData parcel decoder + service constants
  metrics/  ride integration, power zones, calories, speed model, learned resistance->power model
  workout/  workout model, built-in library, runner, target compliance
  ble/      FTMS / Cycling Power / Heart Rate encoders and control-point parser
  export/   ride record + TCX writer
  stats/    weekly totals and streaks
app/    Android app (Kotlin + Jetpack Compose), minSdk 25 (Android 7.1)
  sensor/   Peloton Bike, Bike+, legacy and simulator sources + SensorHub
  ride/     RideController (4 Hz ride engine) + RideService (foreground service, overlay host)
  ble/      BikePeripheral (GATT server) + HeartRateClient
  overlay/  floating HUD shown over other apps
  apps/     app catalog, launching, web fallbacks
  ui/       launcher home, workouts, live ride, history, settings, browser
```

Run the core tests with `./gradlew :core:test`.

To try it without a bike, choose **Settings → Bike sensor → Simulator** on any Android tablet or emulator.

## Known limitations
- Netflix and some other DRM services don't play in a WebView. Install their apps instead.
- Apple Fitness+ has no Android app. It works through AirPlay mirroring plus the Apple Watch power-meter pairing described above.
- Bike+ auto-resistance needs the transaction code for your firmware (see above).
- Peloton OTA updates can change the sensor service. If the "Bike connected" status goes red after an update, try another source under Settings → Bike sensor and open an issue.
- BLE broadcasting requires the tablet's Bluetooth chip to support advertising. BikeOS tells you if it doesn't.
