# Test report: Full Auto Clicker 1.0.0

Each result below is **PASSED**, **FAILED**, or **NOT TESTED**. A test is marked PASSED only if it was actually run, and the evidence column says what was observed.

> **This app has not yet been tested on a physical Samsung Galaxy A15 or A16, or on any physical phone.**
> No device was available in the build environment. Physical volume-key behavior, real latency, and One UI-specific behavior are therefore **NOT TESTED**. See [Not tested](#not-tested). Runtime behavior was verified on an Android 14 emulator configured with the same screen geometry as the A15/A16, with volume keys injected into the kernel input layer.

## Delivered APK

| | |
|---|---|
| File | `dist/FullAutoClicker-1.0.0.apk` |
| Package / version | `com.fullautoclicker` 1.0.0 (versionCode 1) |
| SDK | minSdk 28 (Android 9), targetSdk 36 (Android 16), compileSdk 36 |
| Size / signature | 45,837 bytes. APK Signature Scheme v3, verified with `apksigner verify`; `zipalign -c` OK |
| SHA-256 | `9f2f55b5ca1f95ad37265a71522a7da69dc89b7f024caf39df6ff1c4c6b0d900`. This is the exact file installed for E10f and E22–E28 |
| Signing certificate | `CN=Full Auto Clicker`, SHA-256 `fdbdd299…66e8518`. This is a local key and is not in the repo (see README → Limitations) |
| Permissions | **none** (`<uses-permission>` count = 0). The only protected component is the accessibility service (`BIND_ACCESSIBILITY_SERVICE`) |
| Accessibility capabilities | `capabilities=40` = filter key events + perform gestures. No window-content access and no event types |

The same APK is meant for both phones. It doesn't depend on the chipset (A15: MediaTek; A16: Exynos/MediaTek) and doesn't have ABI-specific code (no native libraries).

## Test environment

| Item | Value |
|---|---|
| Build host | Linux container, JDK 21, Kotlin 2.2.20, Android SDK platform 36 and build-tools 36.0.0 |
| Network limits | `dl.google.com` and Google Maven are blocked here. The SDK was taken from the public `cimg/android` Docker image, so the Android Gradle Plugin couldn't run locally. The Gradle/AGP build was verified on GitHub Actions instead (see B2) |
| Emulator | Android 14 (API 34) `google_apis` x86_64, **1080 × 2340 @ 450 dpi** (the Galaxy A15/A16 panel geometry), emulator 37.2.12 |
| Emulator speed | **Software CPU emulation (no KVM).** It was about 10–50× slower than a phone. Latency numbers from the emulator are **not** representative |
| Key injection | Volume Up/Down were written as kernel `EV_KEY` events (codes 115/114) into the virtual keyboard's evdev node `/dev/input/event1`. They go through Android's real pipeline: InputReader → PhoneWindowManager → accessibility key filter → InputDispatcher. This is the same path a physical key takes. Only the source device differs (a keyboard, not Samsung's `gpio-keys`). `adb shell input keyevent` was **not** used, because it injects after the filter stage |
| Evidence sources | App debug log (`setprop log.tag.AutoClicker DEBUG`); `dumpsys input` (events that reached the dispatcher); `dumpsys window` (overlay frames); `cmd media_session volume --get`; screenshots; the app's test pad (where the injected touch landed) |

Emulator setup that the app does not need, done only to keep the slow emulator alive:
- `system_server_watchdog_timeout_ms` raised
- unused Google apps disabled
- adb-install verification off
- animations off

Three builds of the same code were tested:
- **Build 1** (first install): E1–E9.
- **Build 2** (fixes D1–D5): E10–E21.
- **Final APK** (adds D7, D8): E10f and E22–E28. E22 is the in-place update from build 2 to the final APK.

The [defects table](#defects-found-and-fixed-during-testing) lists what changed between builds.

## Results

### Build and static checks
| # | Test | Result | Evidence |
|---|---|---|---|
| B1 | APK builds with `scripts/build-apk.sh` (aapt2 → kotlinc `-Werror` → R8 → zipalign → apksigner) | PASSED | 0 warnings, 0 errors; APK produced |
| B2 | Standard Gradle build: `./gradlew testDebugUnitTest lintDebug assembleRelease` (AGP 8.13.0, Gradle 8.14.3) | PASSED | GitHub Actions run [39409016308](https://github.com/husenzodao1/Full-Autoclicker-/actions/runs/39409016308), job "Gradle (AGP) build, unit tests, lint": success. The SDK-tools job also passed. CI result for the final commit: see "CI on final commit" below |
| B3 | Signature, alignment, and no permissions | PASSED | `apksigner verify` v3 OK; `zipalign -c -p 4` OK; `aapt2 dump` shows no `uses-permission`; `dumpsys package` shows no requested or runtime permissions |
| U1 | JVM unit tests of the core state machine (24 tests) | PASSED | `OK (24 tests)`. Covers: one press = one tap; long press; duplicate delivery; burst queue limit; STOP drops the queued tap; key-up consistency; rotation pause; rejected or throwing dispatch; lost-callback watchdog; cancel and re-select; latency math |

### Runtime on the emulator
| # | Test | Result | Evidence |
|---|---|---|---|
| E1 | Install | PASSED | `adb install`: Success |
| E2 | App UI with service OFF | PASSED | Screenshot: red "OFF" status, Settings/App info buttons, controls disabled |
| E3 | Turn on service → connects → floating bubble appears | PASSED | Log `Service connected`; window `FullAutoClicker:bubble` (type 2032 = accessibility overlay), 135 × 135 px |
| E4 | **IDLE: Volume Up/Down are handled by Android, and the app never sees them** | PASSED | No app key log; the keys appear in the InputDispatcher queue; volume changed |
| E5 | Tap bubble → menu with START / STOP / HIDE | PASSED | `FullAutoClicker:menu` window; screenshot |
| E6 | START → one marker + panel. No clicking while selecting; Volume Up still changes volume | PASSED | Marker centered at (540, 1170). Volume Up during selection: no app log, volume changed |
| E7 | Drag the marker precisely (any part of the ring) | PASSED (after fix D1) | Marker grabbed 150 px off-center and dragged by (0, +176). Center landed exactly at (540, 1346) |
| E8 | 1-px nudge buttons | PASSED | Window frame moved by exactly +1, +1 |
| E9 | **READY → marker disappears, coordinates kept, ARMED** | PASSED | Marker and panel windows removed; log `Key filter on`, `Armed at 540,1346`; bubble turns green |
| E10 | **One Volume Up press = exactly one tap at the target, with no volume change** | PASSED | Music volume 7 → 7. DOWN and UP both `CONSUME`. **0** VOLUME_UP events reached the dispatcher. Test pad: 1 injected touch at **(540, 1346), offset 0, 0** |
| E10f | Same as E10, on the **final APK** | PASSED | Volume 7 → 7; pad touch at (540, 1346); `Tap 1 COMPLETED` |
| E11 | **Volume Down while ARMED changes the volume and never taps** | PASSED | Volume Down reached the dispatcher; music went down; no tap, no app log |
| E12 | Long press (3 s hold) | PASSED | 1 tap; volume unchanged; nothing reached the dispatcher |
| E13 | Driver-level autorepeat (5 extra key-downs with the same down time and `repeatCount=0`) | PASSED | All 6 downs `CONSUME`, **1 tap**. Shows why presses are identified by down time |
| E14 | 10 separate presses about 1.2 s apart | PASSED | 10 downs and 10 ups consumed; 10 taps completed; 10 pad touches, all at (540, 1346); 0 cancelled; volume unchanged |
| E15 | Burst of 10 presses at a 100 ms period (faster than the emulator could finish a tap) | PASSED (bounded queue) | 10 consumed; 3 taps sent; 7 dropped and counted; no runaway taps after the burst; volume unchanged. One tap stayed in flight more than 1.5 s, was written off by the watchdog, and its late callback was ignored |
| E16 | App switching: launcher in front while ARMED | PASSED | Volume Up consumed; tap completed in the launcher; still ARMED back in the app |
| E17 | **Rotation:** landscape → PAUSED, so Volume Up acts as a normal volume key and nothing taps. Portrait → taps again | PASSED | Bubble shows the ‖ PAUSED icon; down/up `PASS`, no tap. After rotating back, `CONSUME` and the tap completed |
| E18 | **STOP** (bubble menu) | PASSED | `Key filter off`, `Stop: Stopped`. Next Volume Up never reached the app and was handled by Android (the volume panel appeared) |
| E19 | HIDE while ARMED; in-app STOP; "Show floating button" | PASSED | Bubble removed (0 overlay windows) and still tapping. In-app STOP disarmed. Bubble restored at its saved position |
| E20 | **Crash** (`kill -9`) while ARMED | PASSED | System restarted the service; log `Key filter off` → `Service connected`. Next Volume Up handled by Android; app saw nothing |
| E21 | Process killed by the system (an ANR caused by emulator slowness) | PASSED (incidental) | Service restarted in IDLE |
| E22 | **App update installed while ARMED** | PASSED | `install -r` Success → service reconnected in IDLE. Next Volume Up handled by Android |
| E23 | **Accessibility service turned off while ARMED** | PASSED | `Service shut down: Accessibility service turned off`; 0 overlay windows; Volume Up handled by Android; app shows "Turn on accessibility access first" |
| E24 | **Screen off while ARMED** | PASSED | `Stop: Screen turned off`, `Key filter off` |
| E25 | **Reboot while ARMED** | _see below_ | |
| E26 | Menu open on top of the target, then Volume Up | PASSED | Menu closed first; the tap reached the test pad at (540, 1346), not the menu |
| E27 | Bubble dropped onto the target | PASSED | Bubble moved itself to (574, 1279), next to the target; the next tap hit the target |
| E28 | Latency measurement shown in the app | PASSED (feature works) | Shows key → tap sent, key → tap finished, and key → touch delivered to an app (last/avg/p95/max) |

### Latency
| Measurement | Emulator (software CPU) | Physical A15/A16 |
|---|---|---|
| Key event → gesture handed to Android | 111–621 ms (typically 160–250 ms) | NOT TESTED |
| Key event → touch delivered to an app window | 498–1093 ms in single-press tests | NOT TESTED |

The emulator was slow everywhere: system processes timed out and the input dispatcher lagged seconds behind. Its numbers say nothing about a phone. **The 40–50 ms target is NOT VERIFIED.** The app keeps its own share of this time small: no work is queued or scheduled, the gesture is built in advance, and the tap is sent from inside the key callback. Use the app's **Test pad** on the phone to measure real numbers.

## Not tested

| Item | Result | Why / how to check |
|---|---|---|
| Galaxy **A15**, any test | NOT TESTED | No physical device available |
| Galaxy **A16**, any test | NOT TESTED | No physical device available |
| Physical volume buttons (Samsung `gpio-keys` / PMIC keys) | NOT TESTED | The emulator used kernel events from a virtual keyboard through the same Android pipeline. Samsung-specific key handling (One UI policy, Sound Assistant, side-key combos) wasn't exercised |
| Real-device latency (40–50 ms target) | NOT TESTED | Use the in-app test pad |
| One UI "Restricted setting" flow, Auto Blocker, Play Protect prompts when sideloading | NOT TESTED | The emulator was set up over adb |
| Samsung battery management (Never sleeping apps) | NOT TESTED | |
| Android 15 / 16 (One UI 7 / 8) runtime | NOT TESTED | Built for and linted against API 36; runtime tested only on API 34 |
| Tapping while a finger is held on the screen | NOT TESTED | Android cancels one of the two by design (see README limitations) |
| Activity scroll position kept across rotation (fix D7) | NOT TESTED | Fix made after the rotation test; cosmetic |

### Suggested checklist on each phone
1. Install, allow restricted settings, and turn on the service.
2. In the app, check that both volume keys change the volume while IDLE.
3. START → aim at the **Test pad** → READY.
4. Press Volume Up once. Expect: pad shows "Injected taps received: 1", offset 0, 0; no volume panel; volume level unchanged.
5. Hold Volume Up for 3 s. Expect exactly one more tap.
6. Press Volume Down. Expect the volume to change, with no tap.
7. Press quickly 10 times. Expect 10 taps and 0 dropped.
8. Note the latency lines.
9. Rotate, then check that STOP, screen-off, and turning off the service all stop tapping.

## Defects found and fixed during testing
| # | Defect (how it was found) | Fix | Re-verified |
|---|---|---|---|
| D1 | The final finger position (`ACTION_UP`) was ignored, so the marker ended about 35 px short of where the finger stopped (E7) | Marker and bubble apply the up-event position | Yes: lands exactly (E7, E26) |
| D2 | STOP button looked enabled while disabled (screenshot) | Dimmed when disabled | Yes |
| D3 | Status text changing height moved the test pad, so the target fell outside it (screenshot) | Fixed-height status line; pad placed above the stats | Yes |
| D4 | On a slow system the test pad labeled injected taps as "finger" touches (E15) | Matches by dispatch time, with a 5 s window | Yes (`injected=N finger=0`) |
| D5 | Build script made a new signing key every build, so updates failed with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Key is created once and reused | Yes: in-place update (E22) |
| D6 | After a crash, Android could rebind with the key-filter flag still set (code review) | Flag forced off on every connect | Yes: `Key filter off` logged at each connect (E20, E22) |
| D7 | Scroll position reset on rotation (E17 screenshot) | ScrollView given an ID | No (cosmetic) |
| D8 | Diagnostics showed no "lost" count, and the debug log showed the latest tap's latency instead of each tap's own (E15) | Lost count shown; per-tap log | Yes ("0 lost" visible) |

## Android behaviors seen during testing (not app defects)
- With no media playing, Android's AudioService shows the volume panel on the **first** Volume Up press without changing the level. So when the app passes the key through, "the key reached Android's dispatcher" is the reliable evidence, not the level.
- When ARMED, the key is consumed before the dispatcher, so no volume panel appears at all.
- Injected accessibility gestures do not appear in `dumpsys input`'s recent-event list. Touch delivery was checked with the in-app test pad and gesture callbacks instead.
