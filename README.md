# Full Auto Clicker

A native Kotlin Android app for the Samsung Galaxy A15 and A16. You pick a spot on the screen, and from then on **each physical Volume Up press makes exactly one tap there**.

- **APK:** [`dist/FullAutoClicker-1.0.0.apk`](dist/FullAutoClicker-1.0.0.apk). It is one APK for both phones (minSdk 28, targetSdk 36, about 45 KB).
- **Test report:** [`docs/TEST_REPORT.md`](docs/TEST_REPORT.md). It shows what was tested and how, and what has **not** been tested yet.

## How it works

| Step | What you do | What happens |
|---|---|---|
| 1 | Tap the floating circle | A small menu opens with **START · STOP · HIDE** |
| 2 | **START** | A crosshair marker and a small panel appear. Drag the marker anywhere: you can grab any part of the ring, so your finger doesn't cover the center. The ◀▲▼▶ buttons move it 1 px at a time. Nothing is tapped while you choose, and both volume keys work normally. |
| 3 | **READY** | The marker disappears, but its center point is saved. The circle turns green, which means **ARMED**. |
| 4 | Press **Volume Up** | One tap at the saved point. The volume does not change and the volume panel does not appear. |
| — | Press **Volume Down** | Changes the volume as usual. It never taps. |
| — | **STOP** | Stops tapping right away and drops any tap still waiting. Both keys work normally again. |
| — | **HIDE** | Removes the floating circle. The current mode stays the same. To get the circle back, open the app and tap **Show floating button**. The app also has a **STOP** button. |

The circle's color shows the mode: grey = IDLE, amber = SELECTING, green = ARMED, blue-grey with ‖ = PAUSED. PAUSED means the screen rotated or changed size after READY. While paused, Volume Up works as a normal volume key until you rotate back or set a new target.

### Rules the code enforces (`ClickEngine`)
- A tap comes only from the **first key-down of a new press**. Holding the key gives one tap. Auto-repeat events are swallowed and never tap.
- Each press is identified by its key-down time, so if Android delivers the same press twice, it still taps only once.
- At most **one tap is in flight and one waits** behind it. Any more presses during that window are dropped and counted. The gesture queue can never grow without limit.
- The key-up of a press that was consumed is consumed too, so Android never sees half a key press.
- The armed state is never saved to storage. After a crash, reboot, app update, service restart, STOP, screen-off, or turning off accessibility, the app starts in **IDLE**. The last target is saved only to place the marker next time you press START.

### How the volume behavior works
The accessibility key filter (`FLAG_REQUEST_FILTER_KEY_EVENTS`) is **switched on only while ARMED**, and switched off on STOP. In every other state, Android doesn't send key events to the app at all, so volume works exactly as normal. While ARMED, Volume Up is consumed before it reaches the app in front, so it doesn't change the volume. The app never mutes or changes any audio stream.

### Latency
Each tap is sent directly from the key callback on the main thread. Nothing is queued or scheduled, and the gesture is built once per target and then reused. The app measures latency and shows it on its main screen:
- **Key → tap sent:** from the hardware key event's timestamp to the moment `dispatchGesture` is called.
- **Key → tap finished:** until Android reports the gesture is done. This includes the tap length you chose (10/20/40 ms; 20 ms by default).
- **Test pad:** aim at the pad and press Volume Up. The app then measures **key → touch delivered to an app window** and shows where the touch landed compared to the target.

## Install on the phone

1. Copy `dist/FullAutoClicker-1.0.0.apk` to the phone and open it. Allow "Install unknown apps" for the file manager or browser you used.
   - If **Auto Blocker** is on (One UI 6+), it blocks apps from unknown sources. Turn it off first: Settings → Security and privacy → Auto Blocker. You can turn it back on after installing.
   - Play Protect may warn that the app is unknown or uses accessibility. If it does, choose *More details → Install anyway*.
2. Open **Full Auto Clicker** → **Open Accessibility settings** → *Installed apps* → **Full Auto Clicker** → On → Allow.
   - If the switch is greyed out or you see **"Restricted setting"** (Android 13+ blocks this for sideloaded apps): go to Settings → Apps → Full Auto Clicker → ⋮ (top right) → **Allow restricted settings**. Then turn the switch on.
3. Recommended: Settings → Battery → Background usage limits → **Never sleeping apps** → add Full Auto Clicker.
4. The floating circle appears. Try it on the **Test pad** in the app first.

To install from a computer: `adb install -r dist/FullAutoClicker-1.0.0.apk`

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
scripts/build-apk.sh                         # -> build/offline/FullAutoClicker-1.0.0.apk
# To sign with your own key: KEYSTORE=... KEYSTORE_PASS=... KEY_ALIAS=... KEY_PASS=... scripts/build-apk.sh

JUNIT_JARS=junit-4.13.2.jar:hamcrest-core-1.3.jar scripts/run-unit-tests.sh
```

## Debug logging
Logging is off by default. To turn it on: `adb shell setprop log.tag.AutoClicker DEBUG`, then `adb logcat -s AutoClicker`. Each Volume Up event, its decision (CONSUME/PASS), each tap result with its latency, and each test-pad touch are logged.

## Limitations

These come from Android itself. The app does not work around them.

- **Your finger and an injected tap cancel each other.** If you touch the screen when you press Volume Up (for example, holding a game joystick), Android cancels your touch to inject the tap. A real touch that starts during the 10–40 ms tap cancels the tap instead, and the app counts it as "cancelled". No non-root accessibility app can inject a tap alongside a held finger.
- **Screen off or locked:** Android doesn't send volume keys to accessibility services when the screen is off. The app also disarms itself when the screen turns off.
- **Key combinations are handled before the app sees them.** Samsung and Android key shortcuts can still fire: Volume Up + Volume Down held (accessibility shortcut), Side key + Volume Up (Samsung accessibility shortcut), and Volume keys during an incoming call (silence ringer).
- **Other apps that remap volume keys** (Sound Assistant / Good Lock, Button Mapper, other accessibility key filters) can take the key before this app does.
- **Tap length:** the in-flight tap finishes on its own (10–40 ms) after STOP. Android has no API to cancel a gesture already sent, but nothing new is sent and the waiting tap is dropped.
- **Overlaps:** if the open menu or the floating circle is on top of the target when you press Volume Up, the app closes or moves it first, then taps about 60 ms later. Otherwise the tap would hit the app's own window.
- **Samsung battery management:** if One UI puts the app to sleep or turns accessibility off (this can happen after updates), the app is simply IDLE. Turn it on again in Settings. It never resumes tapping by itself.
- **Signing key:** the delivered APK is signed with a key that is not in this repo. If you build it yourself with a different key, uninstall the old app first. Android only installs an update over an APK signed with the same key.
