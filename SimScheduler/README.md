# SIM Scheduler — Android App

Auto-enable and disable individual SIM cards on a time schedule using Android Accessibility Service.

---

## Project Structure

```
SimScheduler/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/simscheduler/
│       │   ├── data/
│       │   │   └── ScheduleRepository.kt      ← Save/load schedules
│       │   ├── service/
│       │   │   ├── SimAccessibilityService.kt  ← Core: finds & clicks SIM toggle
│       │   │   └── SchedulerService.kt         ← Background service
│       │   ├── receiver/
│       │   │   ├── AlarmReceiver.kt            ← Wakes up at scheduled time
│       │   │   └── BootReceiver.kt             ← Restores schedules after reboot
│       │   ├── util/
│       │   │   └── AlarmScheduler.kt           ← Sets AlarmManager alarms
│       │   └── ui/
│       │       └── MainActivity.kt             ← Main UI
│       └── res/
│           ├── layout/activity_main.xml
│           ├── values/{colors, strings, themes}.xml
│           ├── drawable/{sim_badge, sim_badge_2}.xml
│           └── xml/accessibility_service_config.xml
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## How to Build

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 34
- Kotlin 1.9+

### Steps
1. Open Android Studio → **File → Open** → select the `SimScheduler` folder
2. Wait for Gradle sync to complete
3. Connect your phone via USB (enable Developer Options + USB Debugging)
4. Click **Run ▶**

---

## First-Time Setup on Phone

### Step 1 — Grant Alarm Permission (Android 12+)
App will show a button: **"Grant Alarm Permission"**
→ Tap it → Allow exact alarms for SIM Scheduler

### Step 2 — Enable Accessibility Service
App will show: **"Enable Accessibility Service"**
→ Tap it
→ Phone opens: Settings → Accessibility → Installed Apps → **SIM Scheduler**
→ Toggle it **ON**
→ Tap **Allow** on the confirmation dialog
→ Go back to the app

✅ You'll see "Accessibility Service: Active"

---

## Using the App

1. Both your SIM cards are shown (LycaMobile / Jio)
2. Toggle the **Schedule** switch on a SIM
3. Set **Turn OFF at** time (e.g. 10:00 PM)
4. Set **Turn ON at** time (e.g. 6:00 AM)
5. Tap **Save Schedules**
6. Close the app — it works in the background

---

## How It Works at Runtime

```
10:00 PM arrives
     ↓
AlarmManager fires → Wakes device
     ↓
AlarmReceiver runs → Acquires WakeLock
     ↓
SimAccessibilityService opens Android Settings
     ↓
Scans screen for "Jio" text
     ↓
Finds toggle next to it → Clicks it
     ↓
Jio SIM turns OFF ✅
     ↓
Settings closes automatically
     ↓
Device goes back to sleep
```

---

## Troubleshooting

| Problem | Fix |
|---|---|
| Toggle not found | The Settings UI varies by Android version. Check logcat for "SimAccessibility" tag |
| Alarm didn't fire | On Motorola/Xiaomi, disable battery optimization for the app |
| Works manually but not at night | Enable "Allow background activity" in App Battery settings |
| After reboot schedules lost | BootReceiver restores them — allow the app to run at startup |

### Disable Battery Optimization (Important!)
Settings → Apps → SIM Scheduler → Battery → **Unrestricted**

---

## Permissions Explained

| Permission | Why |
|---|---|
| `RECEIVE_BOOT_COMPLETED` | Restore schedules after phone restart |
| `WAKE_LOCK` | Wake screen to interact with Settings |
| `SCHEDULE_EXACT_ALARM` | Fire alarms at precise times |
| `SYSTEM_ALERT_WINDOW` | Keep app running in background |
| `READ_PHONE_STATE` | Read SIM card names and numbers |
| Accessibility Service | Click the SIM toggle in Settings |

---

## Tested On
- Android 11, 12, 13, 14
- Motorola (stock-like Android) ✅
- Stock Android / Pixel ✅
- May need minor adjustments on Samsung One UI / Xiaomi MIUI
