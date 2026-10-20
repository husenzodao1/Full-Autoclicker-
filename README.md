# Full Auto Clicker

A native Kotlin Android app for the Samsung Galaxy A15 and A16. You place up to **10 steps** on the screen: tap points (with any press length from 1 ms to 5 s) and drawn swipes. From then on, **each physical Volume Up press runs all the steps once, in order**.

- **APK:** [`dist/FullAutoClicker-1.2.0.apk`](dist/FullAutoClicker-1.2.0.apk). It is one APK for both phones (minSdk 28, targetSdk 36, about 220 KB, most of it the two fonts).
- **Languages:** English, Русский, Тоҷикӣ. The app follows the phone's language. On Android 13+ the **Language** button at the top of the app, or the language chips in the circle's **Settings**, switch it directly. This is useful for Tajik, which Samsung doesn't offer as a system language.
- **Design:** one dark look for the app and the floating controls. Text is set in [Onest](https://github.com/simpals/onest) and titles, buttons and numbers in [Exo 2](https://github.com/googlefonts/Exo-2.0); both are SIL Open Font License fonts, bundled in the APK (licenses in `app/src/main/assets/licenses/`), so nothing is downloaded.
- **Test report:** [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md). It shows what was tested and how, and what has **not** been tested yet.

## How it works

| Step | What you do | What happens |
|---|---|---|
| 1 | Tap the floating circle | A small icon menu opens: **START · STOP · SETTINGS · HIDE** |
| 2 | **START** | A crosshair marker and a small panel appear. Drag the marker anywhere: you can grab any part of the ring, so your finger doesn't cover the center. The ◀▲▼▶ buttons move it 1 px at a time. Nothing is tapped while you choose. Volume Up is already blocked (no volume panel); Volume Down works normally. |
| 2b | **+ POINT** / **+ DRAWING** / **UNDO** | **+ POINT** adds another numbered tap marker. **+ DRAWING** covers the screen so you can draw a path with one finger; it is replayed as a swipe. **UNDO** removes the last step. At most 10 steps. |
| 3 | **READY** | The markers and drawings disappear, but their positions are saved. The circle turns green, which means **ARMED**. |
| 4 | Press **Volume Up** | All steps run once, in order, with your pauses (example: tap →40 ms→ tap → swipe 0.5 s → swipe 0.2 s → tap). The volume does not change and the volume panel does not appear. This works in any app that is in front (games, messengers, the home screen). |
| — | Press **Volume Down** | Changes the volume as usual. It never taps. |
| — | **STOP** | Stops tapping right away and drops any tap still waiting. Both keys work normally again. |
| — | **SETTINGS** | Opens every setting right on top of the current app: press length, pause and drawing time for new steps; press length / drawing time and pause of each saved step, with the total time of one press; and the language. Each value has − and + buttons (hold to repeat faster). Changes apply at once, even while ARMED. Tap outside the panel or × to close it. |
| — | **HIDE** | Removes the floating circle. The current mode stays the same. To get the circle back, open the app and tap **Show floating button**. The app also has a **STOP** button. |

The circle's color shows the mode: grey = IDLE, amber = SELECTING, green = ARMED, blue-grey with ‖ = PAUSED. PAUSED means the screen rotated or changed size after READY (for example, you set the target in portrait and then opened a landscape game). While paused, Volume Up does nothing: no tap, no volume panel. A short message tells you to rotate back or press START and set the target again inside the game.

### Timing (circle → SETTINGS, or app screen → "3 · Steps")
- **Defaults for new steps:** press length of new points (1–5000 ms, 20 by default), pause between steps (10–10000 ms, 50 by default), drawing duration (20–10000 ms, 300 by default).
- **Exact values:** in the app screen you type the number of milliseconds. In the floating Settings the − / + buttons change by 1 ms below 100 ms, by 10 ms up to 1 s and by 100 ms above; holding a button repeats, and after about a second it moves 10× faster.
- **Per step:** each tap has its own press length and pause, and each drawing has its own duration (start to end of the swipe, at constant speed) and pause. Changes apply at once, even while ARMED. Positions only change with START.
- The pause is at least 10 ms, because with 0 ms Android merges two steps into one multi-finger touch. One run must stay under 59 s (Android's gesture limit is 60 s).
- The whole run is one Android gesture with exact start times, so Android itself keeps the timing.

**Volume Up rule:** from START until STOP, Volume Up never changes the volume and never shows the volume panel. It taps only while ARMED. After STOP, both volume keys are normal again. Volume Down is never intercepted.

### Rules the code enforces (`ClickEngine`)
- From START until STOP every Volume Up press is swallowed. A tap comes only from the **first key-down of a new press** while ARMED. Holding the key gives one tap. Auto-repeat events are swallowed and never tap.
- Each press is identified by its key-down time, so if Android delivers the same press twice, it still taps only once.
- At most **one tap is in flight and one waits** behind it. Any more presses during that window are dropped and counted. The gesture queue can never grow without limit.
- The key-up of a press that was consumed is consumed too, so Android never sees half a key press.
- The armed state is never saved to storage. After a crash, reboot, app update, service restart, STOP, screen-off, or turning off accessibility, the app starts in **IDLE**. The last target is saved only to place the marker next time you press START.

### How the volume behavior works
The accessibility key filter (`FLAG_REQUEST_FILTER_KEY_EVENTS`) is **switched on at START and off at STOP**. While IDLE, Android doesn't send key events to the app at all, so volume works exactly as normal. Between START and STOP, Volume Up is consumed before it reaches Android's volume handling, so it doesn't change the volume and the volume panel never appears. The app never mutes or changes any audio stream.

### Latency
Each tap is sent directly from the key callback on the main thread. Nothing is queued or scheduled, and the gesture is built once per target and then reused. The app measures latency and shows it on its main screen:
- **Key → tap sent:** from the hardware key event's timestamp to the moment `dispatchGesture` is called.
- **Key → run finished:** until Android reports the whole run is done. This includes all press lengths, pauses and swipe durations.
- **Test pad:** aim at the pad and press Volume Up. The app then measures **key → touch delivered to an app window** and shows where the touch landed compared to the target.

## Install on the phone

1. Copy `dist/FullAutoClicker-1.2.0.apk` to the phone and open it. Allow "Install unknown apps" for the file manager or browser you used.
   - If **Auto Blocker** is on (One UI 6+), it blocks apps from unknown sources. Turn it off first: Settings → Security and privacy → Auto Blocker. You can turn it back on after installing.
   - Play Protect may warn that the app is unknown or uses accessibility. If it does, choose *More details → Install anyway*.
2. Open **Full Auto Clicker** → **Open Accessibility settings** → *Installed apps* → **Full Auto Clicker** → On → Allow.
   - The first time, Samsung shows **"Доступ для приложения запрещен"** (*App was denied access*), and the app is listed as *"Контролируется настройками с ограниченным доступом"*. This is Android 13+'s protection for apps installed from a file. Fix it like this:
     1. Tap **Закрыть** (*Close*).
     2. Open **Настройки → Приложения → Full Auto Clicker** (or the app's **App info** button).
     3. Tap **⋮** (top right), then **Разрешить настройки с ограниченным доступом** (*Allow restricted settings*), and confirm with your PIN or fingerprint.
     4. Go back to **Специальные возможности → Установленные приложения → Full Auto Clicker** and turn it on.
   - The ⋮ menu item appears only after you have tried once and seen that message.
   - Installing with `adb install` from a computer avoids this restriction completely.
3. Recommended: Settings → Battery → Background usage limits → **Never sleeping apps** → add Full Auto Clicker.
4. The floating circle appears. Try it on the **Test pad** in the app first.

To install from a computer: `adb install -r dist/FullAutoClicker-1.2.0.apk`

## Permissions

| Item | Why |
|---|---|
| Accessibility service (`BIND_ACCESSIBILITY_SERVICE`, granted by you in Settings) | Needed for `dispatchGesture` (to tap), for filtering hardware keys (Volume Up), and for showing accessibility overlays (the floating controls). |
| *(nothing else)* | The APK has **no `<uses-permission>` entries**: no internet, no "display over other apps" (the overlays are accessibility overlays), no notifications, no storage, and no root. It has no ads and no analytics. |

The accessibility service does not ask for window content (`canRetrieveWindowContent="false"`) and does not ask for any accessibility events.

## Build from source

### Option A: Android Studio or Gradle (normal internet)
```bash
./gradlew assembleRelease          # app/build/outputs/apk/release/app-release.apk
./gradlew testDebugUnitTest        # JVM unit tests for the core logic
```
This uses AGP 8.13.0, Kotlin 2.2.20, Gradle 8.14.3 and JDK 17+.

For release signing, create `keystore.properties` in the project root. Never commit it:
```
storeFile=/path/to/release.jks
storePassword=...
keyAlias=...
keyPassword=...
```
If that file is missing, release builds are signed with the debug key so they can still be installed.

> Google Maven was blocked in the environment where this was built, so the Gradle/AGP build was verified on GitHub Actions instead (`.github/workflows/android.yml` runs unit tests, lint and `assembleRelease`). The delivered APK was built with Option B from the same sources.

### Option B: SDK command-line tools only (no Android Gradle Plugin)
This is how the delivered APK was built: aapt2 → javac → kotlinc → R8 → zipalign → apksigner.
```bash
export ANDROID_HOME=/path/to/android-sdk     # needs platforms/android-36 and build-tools 36.x
export KOTLIN_HOME=/path/to/kotlinc          # Kotlin 2.2.x compiler
scripts/build-apk.sh                         # -> build/offline/FullAutoClicker-1.2.0.apk
# To sign with your own key: KEYSTORE=... KEYSTORE_PASS=... KEY_ALIAS=... KEY_PASS=... scripts/build-apk.sh

JUNIT_JARS=junit-4.13.2.jar:hamcrest-core-1.3.jar scripts/run-unit-tests.sh
```

## Debug logging
Logging is off by default. To turn it on: `adb shell setprop log.tag.AutoClicker DEBUG`, then `adb logcat -s AutoClicker`. Each Volume Up event, its decision (CONSUME/PASS), each tap result with its latency, and each test-pad touch are logged.

## Limitations

These come from Android itself. The app does not work around them.

- **Your finger and an injected tap cancel each other.** If you touch the screen when you press Volume Up (for example, holding a game joystick), Android cancels your touch to inject the tap. A real touch that starts while a run is playing cancels the rest of the run instead, and the app counts it as "cancelled". No non-root accessibility app can inject a tap alongside a held finger.
- **Screen off or locked:** Android doesn't send volume keys to accessibility services when the screen is off. The app also disarms itself when the screen turns off.
- **Key combinations are handled before the app sees them.** Samsung and Android key shortcuts can still fire: Volume Up + Volume Down held (accessibility shortcut), Side key + Volume Up (Samsung accessibility shortcut), and Volume keys during an incoming call (silence ringer).
- **Other apps that remap volume keys** (Sound Assistant / Good Lock, Button Mapper, other accessibility key filters) can take the key before this app does.
- **A run in progress:** STOP prevents new runs and drops the waiting one. Android has no API to cancel a gesture already sent, so a run that has started finishes, unless a finger touches the screen (tapping STOP on the circle does that and cancels the rest of the run).
- **Overlaps:** if the open menu, the Settings panel or the floating circle is on top of a target when you press Volume Up, the app closes or moves it first, then taps about 60 ms later. Otherwise the tap would hit the app's own window.
- **Samsung battery management:** if One UI puts the app to sleep or turns accessibility off (this can happen after updates), the app is simply IDLE. Turn it on again in Settings. It never resumes tapping by itself.
- **Signing key:** the delivered APK is signed with a key that is not in this repo. If you build it yourself with a different key, uninstall the old app first. Android only installs an update over an APK signed with the same key.
- **Games and anti-cheat:** injected touches reach apps as normal touchscreen events, the same way they reach any app. But a game may detect accessibility automation, or forbid it in its terms; for an online game such as Free Fire that can mean an account ban. Free Fire and WhatsApp could not be installed in the test environment, so they were not tested.
