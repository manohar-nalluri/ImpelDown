# Impel Down — a floating touch lock for Android

A single bubble that floats over every app. Tap it and every touch on the screen is absorbed —
like YouTube's lock-screen button, but system-wide. It ships with a sleep timer that locks the
phone and a "black dim" mode for listening with the screen dark and touches still locked.

**By the numbers:** one foreground service, **zero third-party libraries**, no AndroidX, no
Compose, no network permission, no wakelocks, no polling. Release APK ≈ **58 KB**,
idle PSS ≈ **6 MB**, and `batterystats` records *no work at all* across a 4-minute idle window.

---

## 1. Build

Requirements: JDK 17+ and an Android SDK with **platform 35** and **build-tools 35.0.1**.
Nothing else is downloaded — the app module has no dependencies at all.

```bash
./gradlew assembleRelease        # -> app/build/outputs/apk/release/app-release.apk
./gradlew assembleDebug          # -> app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` must point at your SDK (`sdk.dir=/path/to/Android/sdk`); Android Studio
writes it for you.

### Signing

Release builds are signed with a **real certificate**, not the Android Debug one, and carry all
three signature schemes (v1 JAR + v2 + v3). That combination is what makes sideloading reliable:
Play Protect and several OEM installers reject debug-signed, v2-only APKs with a bare
*"App not installed"*.

`keystore.properties` (git-ignored) points at the keystore:

```properties
storeFile=keystore/release.jks
storePassword=impeldown
keyAlias=impeldown
keyPassword=impeldown
```

The bundled `keystore/release.jks` is a **self-signed development certificate** so that
`assembleRelease` works out of the box. Replace it before publishing anywhere:

```bash
keytool -genkeypair -v -keystore keystore/release.jks -storetype PKCS12 \
        -storepass <password> -keypass <password> -alias <alias> \
        -keyalg RSA -keysize 2048 -sigalg SHA256withRSA -validity 10000 \
        -dname "CN=Your Name, O=Your Org, C=US"
```

If `keystore.properties` is missing, the build still succeeds and silently falls back to the debug
key — installable, but flagged as untrusted by Play Protect on many phones.

R8 minification and resource shrinking are on for release (see the size table in §7).

---

## 2. Install and grant permissions

The app needs **no root** and **no network**. Four grants, all revocable:

| Permission | Why | How |
|---|---|---|
| **Display over other apps** (`SYSTEM_ALERT_WINDOW`) | draws the bubble / lock / dim windows | required |
| **Notifications** (Android 13+) | shows the ongoing notification and its *Unlock* action | recommended |
| **Device admin** | `lockNow()` for the sleep timer | recommended (or use accessibility) |
| **Accessibility service** | optional alternative lock action (Android 9+) | optional |

The settings screen walks through all four with live ✓/✗ status. From the command line:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell appops set com.impel.touchlock SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.impel.touchlock android.permission.POST_NOTIFICATIONS
adb shell dpm set-active-admin com.impel.touchlock/.LockDeviceAdminReceiver
adb shell am start -n com.impel.touchlock/.SettingsActivity   # starts the bubble too
```

> The `BubbleService` is deliberately **not exported**, so `adb shell am start-foreground-service`
> cannot start it — opening the app is the supported entry point.

### If the phone says "App not installed"

The APK is signed with a real certificate and carries v1 + v2 + v3 signatures, which removes the
usual cause (Play Protect rejecting an *Android Debug* signed, v2-only APK). If it still refuses:

1. **Uninstall first.** `Settings → Apps → Impel Down → Uninstall`. A half-finished earlier attempt
   with a different signature blocks reinstalls until it is removed.
2. **Let adb tell you why** — it prints the exact `INSTALL_FAILED_*` reason instead of a generic message:
   ```bash
   adb install -r ImpelDown-1.0.apk
   # e.g. INSTALL_FAILED_UPDATE_INCOMPATIBLE, INSTALL_FAILED_VERIFICATION_FAILURE,
   #      INSTALL_PARSE_FAILED_NO_CERTIFICATES, INSTALL_FAILED_INSUFFICIENT_STORAGE
   ```
3. **Play Protect**: Play Store → profile picture → Play Protect → ⚙ → temporarily turn off
   *Scan apps with Play Protect*, install, then switch it back on.
4. **"Install unknown apps"** must be allowed for the app you are installing *from* (Files, Chrome,
   or your file manager): `Settings → Apps → <that app> → Install unknown apps → Allow`.
5. **Transfer the file, don't forward it.** Chat apps sometimes rename `.apk` to `.apk.1` or truncate
   it. Copy via USB or a cloud drive and check the size is exactly 59,214 bytes.
6. **Android 8.0 or newer is required** (`minSdk 26`). On Android 7 or older the installer reports
   the same "App not installed" message.

### Download the APK straight to the phone (no cable, no file transfer)

Two sideloadable builds are published in `dist/` so the phone can fetch them directly:

| File | Package | Why it exists |
|---|---|---|
| `dist/ImpelDown.apk` | `com.impel.touchlock` | the standard build |
| `dist/ImpelDown-alt.apk` | `com.impel.impeldown` | fallback for ROMs that refuse the standard one |

Open this on the phone and tap download — the browser saves it to `Download/`, a location the
package installer can always read (unlike a file received inside WhatsApp, which lands under
`Android/media/com.whatsapp/...` and often fails with "App not installed"):

```
https://github.com/manohar-nalluri/ImpelDown/raw/main/dist/ImpelDown.apk
https://github.com/manohar-nalluri/ImpelDown/raw/main/dist/ImpelDown-alt.apk
```

The `-alt` build differs in exactly three ways, to rule out a Vivo/Oppo/ColorOS rejection caused
by a hidden package record or a flagged permission:

```kotlin
applicationId = "com.impel.impeldown"   // was com.impel.touchlock
versionName   = "1.0-alt"
```
```xml
<!-- RECEIVE_BOOT_COMPLETED removed (start-on-boot is unavailable in this build) -->
```
```xml
<string name="app_name">Impel Down Alt</string>
```

Both are signed with the same release certificate. Note that build outputs are otherwise
git-ignored — only `dist/*.apk` is tracked, so refresh those two files after a rebuild.

---

## 3. Using it

| Gesture | Result |
|---|---|
| **Tap** the bubble | lock / unlock request for touches (a tap while locked does nothing) |
| **Long-press** the bubble (~0.5 s) | opens the menu: sleep timer, custom timer, black dim, settings, quit |
| **Long-press** the bubble while locked (~1.5 s) | unlocks — the only deliberate unlock gesture |
| **Drag** the bubble | moves it; on release it snaps to the nearest screen edge and remembers the spot |
| **Notification** | Lock / Unlock, Black dim, Quit — the unlock escape hatch |

### 3.1 Floating bubble

A `TYPE_APPLICATION_OVERLAY` window owned by one foreground service, declared with the Android 14
`specialUse` type. Flags: `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS | FLAG_LAYOUT_IN_SCREEN`, and
deliberately **no** `FLAG_NOT_TOUCH_MODAL`, so the bubble receives its own touches and the app
underneath keeps both input focus and its own touch stream.

The icon is drawn on a `Canvas` (`BubbleView`) — no bitmap, no vector inflation, one flat view.
Idle behaviour: after ~2 s without interaction the window fades to a low opacity (default 12 %,
configurable down to 0 %) so it does not sit on top of a video. It comes back on the next touch of
the bubble, and — if the optional accessibility service is enabled — on **any** touch of the
screen, which is what makes the "invisible while watching" behaviour feel natural.

### 3.2 Touch lock

A second, full-screen, transparent `TYPE_APPLICATION_OVERLAY` window whose view returns `true`
from `onTouchEvent` for every event, so nothing is ever passed to the app underneath. It is
`FLAG_NOT_FOCUSABLE` (the app underneath keeps playing) and intentionally modal, so every touch
inside the display is delivered to it first, status/navigation areas included.

While locked the bubble turns into an amber padlock at reduced opacity, holds full brightness for
3 s and then fades — the deliberate unlock gesture is a 1.5 s hold, so accidental brushes cannot
unlock. Single taps are ignored while locked.

### 3.3 Sleep timer

Presets 5 / 10 / 15 / 30 / 60 min plus a custom value. The timer is implemented with **two
complementary triggers and no polling**:

1. one main-looper `Handler.postDelayed` — exact while the process is alive (the normal case,
   since the service runs continuously), costing nothing;
2. one `AlarmManager.setAndAllowWhileIdle` alarm — the backstop for process death and deep doze.

`setAndAllowWhileIdle` (not `setExactAndAllowWhileIdle`) is used on purpose so the app needs no
`SCHEDULE_EXACT_ALARM` permission; being inexact is exactly why it is only the backstop. Whichever
trigger fires first wins, and the path is idempotent, so the screen is locked once.

The phone is locked through **device admin `lockNow()`** by default, or through
`AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN` (Android 9+) if you pick that method in settings.
The ongoing notification renders the remaining time with the **system chronometer**
(`setUsesChronometer` + `setChronometerCountDown`) so the countdown updates without a single app
wake-up. A manual screen-off cancels a pending timer; the countdown label is only refreshed while
the menu is actually visible.

### 3.4 Black dim

A full-screen opaque black window plus `WindowManager.LayoutParams.screenBrightness` at the
minimum. Because `WRITE_SETTINGS` is **not** requested, the override applies only while the window
is visible; the user's brightness setting is never modified and comes back automatically.

`0f` is `BRIGHTNESS_OVERRIDE_OFF` — the documented "turn the screen off" value — so the app tries
it first and self-corrects: if the panel goes dark (either an `ACTION_SCREEN_OFF` within 2.5 s or a
900 ms `PowerManager.isInteractive()` probe), it flips the setting to `0.01f` and remembers that for
next time. The checkbox in settings controls this. Touch lock stays active in dim mode, and the
faint padlock remains visible as the unlock hint (see the screenshot in §8).

### 3.5 Settings

One plain XML activity (`android.widget`, framework `Theme.DeviceDefault`): permission onboarding
with live status, bubble size (36–68 dp), bubble opacity, idle opacity, start-on-boot, the sleep
timer presets and custom entry, lock-method choice, and the battery-optimisation tip for
Xiaomi/Oppo/Vivo/Samsung.

---

## 4. Project layout

```
app/src/main/
├── AndroidManifest.xml                 only the 5 permissions that are actually used
├── java/com/impel/touchlock/
│   ├── BubbleService.kt                the single foreground service; owns every window
│   ├── BubbleView.kt                    Canvas-drawn bubble + padlock, no bitmaps
│   ├── LockOverlay.kt                   full-screen touch absorber (+ TouchBlockerView)
│   ├── DimOverlay.kt                    black window + screenBrightness override
│   ├── AppMenu.kt                       long-press menu window (timer / dim / settings / quit)
│   ├── SleepTimerManager.kt             Handler + AlarmManager, no polling
│   ├── SleepTimerReceiver.kt            alarm backstop -> lockNow()
│   ├── ScreenStateReceiver.kt           dynamic, registered only while timer/dim is active
│   ├── LockDeviceAdminReceiver.kt       device-admin declaration
│   ├── Locker.kt                        one "lock the phone" call, two mechanisms
│   ├── LockAccessibilityService.kt      optional lock action + touch-to-wake hint
│   ├── BootReceiver.kt                  optional start on boot
│   ├── Notifier.kt                      one low-importance ongoing notification
│   ├── Prefs.kt                         all persistent state, one SharedPreferences file
│   ├── Ui.kt                            dp / insets / rounded-drawable helpers
│   └── SettingsActivity.kt              the only Activity
└── res/                                1 layout, 2 vector drawables, strings/themes/colors
```

Everything — bubble, lock, dim, menu, timer, notification — is owned by the single
`BubbleService`; there is no second service, no thread, no coroutine and no library.

Idle-time cost is zero by construction: no polling loops, no wakelocks, no live animations, and
the lock/dim/menu windows exist only while they are actually shown (they are removed from
`WindowManager`, not hidden).

---

## 5. Testing

Everything below was exercised on a headless **Android 15 (API 35)** emulator, arm64.

```bash
# 1. bubble appears, drag it, let it snap to an edge
adb shell am start -n com.impel.touchlock/.SettingsActivity
adb shell input keyevent KEYCODE_HOME
adb shell dumpsys window windows | grep -E "ty=APPLICATION_OVERLAY"
#   -> (921,936)(143x143) ... fl=NOT_FOCUSABLE LAYOUT_IN_SCREEN LAYOUT_NO_LIMITS

# 2. tap the bubble -> full-screen touch lock appears
adb shell input tap 992 1007
adb shell dumpsys window windows | grep -E "ty=APPLICATION_OVERLAY"
#   -> (0,0)(fillxfill) ... WATCH_OUTSIDE_TOUCH      (no NOT_TOUCH_MODAL, no NOT_TOUCHABLE)
adb shell input tap 175 1480          # tap an app icon underneath
adb shell dumpsys activity activities | grep topResumedActivity   # unchanged -> touches absorbed

# 3. hold 1.5 s -> unlock
adb shell input swipe 992 1007 992 1007 1600

# 4. hold 0.5 s -> menu; tap "Black dim" -> screen goes black, padlock hint stays
adb shell dumpsys window windows | grep sbrt
#   -> ... sbrt=0.0        (or sbrt=0.01 after the self-correction)

# 5. sleep timer: set 1 minute in settings, then
adb shell dumpsys power | grep mWakefulness
#   -> mWakefulness=Asleep        (device admin lockNow() fired)
```

Also verified: the bubble position survives `am force-stop` (restored from SharedPreferences),
`START_STICKY` restores the bubble after the process is killed, and restoring the lock state is
deliberately **not** done (so a crash can never leave the user with a locked screen).

---

## 6. Performance and battery

Measured with `dumpsys meminfo` / `dumpsys batterystats` on the API 35 emulator, bubble running:

| Metric | Value |
|---|---|
| Release APK | **57.8 KB** (59,214 bytes, signed v1+v2+v3) |
| Debug APK (unminified, for comparison) | 845 KB |
| Steady-state PSS — service only | **≈ 6 MB** |
| Steady-state PSS — with the settings screen warm | **≈ 24 MB** (Java heap ~1.8 MB, native heap ~9.4 MB) |
| App-held wakelocks | **0** |
| Work executed over a 4-minute idle window | **none** — `batterystats` reports `u0a207: (nothing executed)` |
| Wake-up alarms while idle | **0** (only the alarm you explicitly set for the sleep timer) |
| CPU time for a whole session of bubble + lock + dim + a fired timer | ≈ 1.3 s |

The only wakelock name that mentions the app in `dumpsys power` is
`NotificationManagerService:post:com.impel.touchlock`, held by **uid 1000 (the system)** while it
posts the ongoing notification — the app itself never acquires one:

```bash
adb shell dumpsys power | grep -i impel | grep ACQ | sed -E 's/.*- ACQ //' | sort -u
# -> NotificationManagerService:post:com.impel.touchlock (partial)
adb shell dumpsys power | grep -i impel | grep ACQ | awk '{print $4}' | sort -u
# -> 1000          (the system, not the app's uid 10207)
```

`adb shell dumpsys batterystats --charged | grep -A4 u0a207` is the command to re-check on your own
device; the numbers to watch are the `running` time and the wake-up count.

---

## 7. API-level notes (Android 8 → 15)

`minSdk 26`, `targetSdk 35`, `compileSdk 35`.

| Level | Behaviour / handling |
|---|---|
| **8.0 / 8.1 (26–27)** | Minimum supported. `TYPE_APPLICATION_OVERLAY`, notification channels, `startForegroundService` and adaptive icons are all baseline here, so no compatibility shims are needed. `ACTION_SCREEN_OFF` is already forbidden in the manifest — the receiver is registered at runtime. |
| **9 (28)** | `GLOBAL_ACTION_LOCK_SCREEN` becomes available, so the accessibility lock method works; below that the app silently falls back to device admin. |
| **10 (29)** | System dark mode starts applying; `values-night` themes kick in. Background activity starts from a service start being restricted — the menu's *Settings* item still works because an app holding `SYSTEM_ALERT_WINDOW` is exempt. |
| **11 (30)** | `WindowManager.currentWindowMetrics` / `WindowInsets.Type.systemBars()` are used for the real display size and the status/nav-bar insets. On 26–29 the app falls back to `Display.getRealSize()` and the framework's `status_bar_height` / `navigation_bar_height` dimens. |
| **12 (31)** | Modern **untrusted-touch** rules: touches that would pass *through* another app's overlay are blocked. The bubble only ever covers its own ~52 dp circle and consumes only its own touches, so nothing is lost. `PendingIntent`s are always created with `FLAG_IMMUTABLE`. |
| **13 (33)** | `POST_NOTIFICATIONS` is a runtime permission; without it the service still runs but the ongoing notification (and its *Unlock* button) is hidden. |
| **14 (34)** | Foreground services must declare a type: `specialUse` + `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`, and `FOREGROUND_SERVICE_SPECIAL_USE` is requested. `startForeground(id, n, type)` is used on 34+. |
| **15 (35)** | Current target. Full-screen overlay frames exclude the status/navigation bars (system windows stay above overlays) — see §8. The exact build the app was tested on. |

---

## 8. Known platform limits (handled, not hidden)

* **The power button can never be blocked** by an overlay; neither can the notification shade or
  the system's own gesture handles. While locked, the status bar and navigation bar still belong to
  the system — this is expected and documented, not a bug.
* **Secure windows** (banking apps, DRM video, some system dialogs) hide overlays. Every
  `WindowManager` call is wrapped so the app degrades instead of crashing: the bubble simply is not
  drawn there.
* **`screenBrightness = 0f` means "screen off"** on most panels, and the app self-corrects to
  1 % the first time it happens (§3.4). Untick the checkbox to skip the experiment entirely.
* **Inactivity restore needs the accessibility service**: an app cannot observe touches inside
  other apps without one. Without it, the faded bubble comes back when you touch *the bubble*; with
  the optional accessibility service enabled (it only listens for touch-start, never window
  content) it comes back on any touch.
* **Aggressive OEM battery killers** (Xiaomi, Oppo, Vivo, Samsung) can still stop the service.
  The settings screen links straight to the battery-optimisation list and asks for "No restrictions".
* **`setAndAllowWhileIdle` is inexact** — in deep doze the backstop alarm can be deferred by the
  platform (measured: ~30 s late on an idle emulator). The in-process Handler covers the normal
  case exactly; the alarm only exists for the killed-process case.
* Restoring the *locked* state after a process restart is deliberately not done, so a crash or a
  bad state can never leave the screen permanently locked.

---

## 9. Design constraints honoured

* Kotlin, native Android, `android.view` views built in code for every overlay, one plain XML
  Activity for settings.
* **No third-party libraries** — no Hilt, Room, Retrofit, Glide, no AndroidX, no Material, no
  Compose. `dependencies { }` in `app/build.gradle.kts` is genuinely empty.
* `minSdk 26`, `targetSdk 35`, R8 + resource shrinking on release, VectorDrawable/Canvas icons
  only, no bitmap assets anywhere.
* One foreground service, everything on the main looper, event-driven only: no polling, no
  wakelocks, no per-second background work, no animations while idle.
* Fragments, ad SDKs, analytics and the network permission are all absent by construction.
