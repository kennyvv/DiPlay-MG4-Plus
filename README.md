# DiPlay-MG4 Plus

Wireless CarPlay for the MG4 head unit, with **SWI68 support** and experimental CarPlay
features on the **instrument cluster**.

DiPlay-MG4 Plus builds on two projects, and almost everything in it is their work:

- [**fatihdonmezdev/DiPlay-MG4**](https://github.com/fatihdonmezdev/DiPlay-MG4) by
  [@fatihdonmezdev](https://github.com/fatihdonmezdev): DiPlay for the MG4, tested on SWI69.
- [**andrew-snape/DiPlay-MG4**](https://github.com/andrew-snape/DiPlay-MG4) by
  [@andrew-snape](https://github.com/andrew-snape): the SWI165 fork (Wi-Fi audio and calls, overlay
  video, steering wheel, dashboard directions, location, charging, dark mode, the two-pane Settings
  and more). This repository continues from its `mg4.96` build.

Current build: **0.2.9-mg4.96-swi68.10**. It installs as `com.shihab.diplay.hudtest`, the same
package as andrew-snape's builds, so it installs over them; it can sit alongside an upstream
`com.shihab.diplay` install. Run one at a time.

## What Plus adds

### SWI68 support (always on)

SWI68 firmware (checked against `SWI68-29958-1300R59`) ships the same eh32 system apps as
SWI165: launcher, media service, nFore Bluetooth, steering-wheel broadcasts, cluster map service
and the stock AllGo CarPlay app. Plus detects SWI68 from `ro.build.mt2712.version` and gives it the
SWI165 profile, so SWI68 cars get Wi-Fi audio, the overlay video renderer, square corners, wheel
keys and the rest. Before, SWI68 ran as unknown firmware in Bluetooth Music mode, whose A2DP check
opened Bluetooth settings ("connect to Bluetooth for audio") every time CarPlay started. The
interface check is in [docs/MG4_SWI68.md](docs/MG4_SWI68.md).

Also always on: in Bluetooth Music mode, a phone that is still on hands-free is no longer sent to
Bluetooth settings (iOS drops A2DP to the car during wireless CarPlay on purpose).

### Settings > Experimental

Each one is off by default, on SWI68 and SWI165 only. The cluster ones use the 260 px square in
the middle of the instrument cluster, which the head unit draws on Android display 1.

| Feature | What it does | Car test |
|---|---|---|
| **Map on the dashboard** | CarPlay's own instrument-cluster screen (Map or Next turn) in the cluster square on the Navigation page. **Map element size** (Large, Medium, Small) asks for a larger stream and scales it into the square, so Apple Maps draws smaller | Works (2026-11-08). Overlay renderer, Medium size by default |
| **Lane guidance on the dashboard** | CarPlay's lane arrows on the cluster's navigation card: with the car's own lane icons, or drawn by MG4CPlay at every angle the iPhone sends. Needs Directions on the dashboard | Not yet tried |
| **Song on the dashboard** | The CarPlay song (title, artist, album) on the cluster's Music page. The car's own song card only takes CarPlay from the stock AllGo apps, so Plus draws its own | Works (2026-11-08) |
| **Album art on the song card** | The cover, from the iPhone's iAP2 artwork transfers | Works (2026-11-08) |
| **Steering-wheel joystick for CarPlay** | Hold the centre of the right-hand joystick while CarPlay is on screen: up and down step CarPlay's highlight, left and right nudge, the centre selects. Hold again, or leave CarPlay, to get volume and tracks back. Works through an accessibility service, like MG4Control's advanced shortcuts | Works (2026-11-08) |

See [CHANGELOG.md](CHANGELOG.md) (the `swi68` entries) for the details of each.

## Inherited from andrew-snape/DiPlay-MG4

The sections below describe the SWI165 fork this repository builds on, as its author wrote them.
On SWI68 the same applies.

## What works on SWI165

| Feature | Status |
|---|---|
| Wireless CarPlay over the car hotspot | Working, about 4 s from launch to first frame |
| Picture (overlay video renderer) | Working, fixes the washed-out look |
| Battery and range sent to the iPhone | Working (Apple Maps EV features) |
| Music over Wi-Fi (CarPlay audio) | Working. Broken in mg4.67 and mg4.68 (no CarPlay audio at all); fixed in mg4.69 and confirmed in the car in mg4.71 |
| Phone calls over Wi-Fi | Working in mg4.83: the call played through the car's phone audio path. Some static occurs when Wi-Fi drops call packets (30–60% loss in one car test); the cause is the Wi-Fi link, not the routing |
| Call microphone | Working. Software Opus encoder on Android 9 (mg4.69), about 5 ms per frame (mg4.83) |
| Wheel voice button opens Siri | Works (mg4.92, 2026-11-03), but only on release. mg4.93: holding the button opens Siri straight away; a short press still opens it on release |
| Steering-wheel next and previous | Working (mg4.66, re-confirmed mg4.80) |
| Hands-free released during CarPlay | Working (mg4.80); released again on each reconnect (mg4.81) |
| Square corners | Confirmed in the car (mg4.70), default since mg4.75 |
| Siri and Maps voice prompts | Siri audio clean in mg4.71 test; Maps voice prompts not yet tested |
| Instrument-cluster directions | Test build (mg4.80), not yet confirmed in the car |
| Bluetooth Music mode (fallback) | Fix in mg4.66, awaiting a car test |
| Car GPS and wheel speed to the iPhone | Test build (mg4.90): location on by default on MG4, with the car's own speed for tunnels (no adb). Not yet tested in the car |
| Charging shown in Apple Maps | Test build (mg4.90): uses the car's charging status (AC or DC session), not just the plug. Not yet tested in the car |
| In-app pairing | Test build (mg4.96): Pair another searches and pairs the iPhone without leaving the app. Not yet tried in the car |
| USB CarPlay | Removed in mg4.96. The car's own wired CarPlay holds the iPhone, so MG4CPlay cannot claim it. Use the car's built-in CarPlay for a cable |
| Wi-Fi high performance | Test build (mg4.91): keeps the car's Wi-Fi out of power saving during CarPlay (from xcertplay 1.4.1). Being tested against the 160 s freezes |
| Video while parked | Built in (upstream feature, gated on the MG4's gear P). Not yet tested in the car |
| Dark mode | Follows the car's own day/night, which switches with the headlights. Confirmed in the car (2026-11-03): headlights on turned the car, Android and CarPlay dark. Dark mode at sunset is an option (mg4.92: off by default) |

The full test record is in [docs/MG4_SWI165.md](docs/MG4_SWI165.md).

## What this fork changes

| Area | Change |
|---|---|
| Firmware profile | Reads `ro.build.mt2712.version` at start-up and applies SWI69 or SWI165 settings. Unknown firmware keeps upstream behaviour. |
| Video | SWI165 draws CarPlay on the hardware video overlay (`SurfaceView`), like the stock AllGo CarPlay app. |
| Audio | SWI165 plays CarPlay music and calls over Wi-Fi through DiPlay. Once CarPlay is up, the car's Bluetooth music link to the iPhone is disconnected; hands-free stays connected and pairing is kept. Call audio plays like the stock CarPlay app: voice audio tagged as car source 53, with the car in call mode (mg4.83). |
| Calls over CarPlay only | Optional, on by default on MG4 (mg4.82). Keeps hands-free released for the whole CarPlay session, so calls can only come through CarPlay. A short press of the wheel phone key answers or ends a CarPlay call (mg4.78). |
| Steering wheel | SWI165 sends wheel keys as a SystemUI broadcast, not Android media keys. DiPlay listens for it in Wi-Fi mode and sends next and previous to CarPlay. Volume and mute stay with the car. |
| Instrument cluster | Test only (mg4.80): sends CarPlay's next turn, distance and street to the cluster's navigation area through the car's `MapService`. |
| Bluetooth Music mode | Kept as a fallback. Switches the car to Bluetooth Music with the media service's `play` command, as read from the car's own MediaService. |
| Location | On by default on MG4 (mg4.90). Sends the car's GPS with its own wheel speed and gear (`$PASCD`), read directly from the car, so Apple Maps keeps moving in tunnels. Only gear P is confirmed; others are sent as D. |
| Wi-Fi | Holds Android's high-performance Wi-Fi lock during wireless CarPlay (mg4.91, from xcertplay 1.4.1). Location sends each NMEA sentence as its own parameter, only the types the iPhone asked for, at its intervals (mg4.91, from xcertplay 1.4.1). |
| Charging | Apple Maps' "charging" comes from the car's charging status (1 AC, 10 DC) (mg4.90). Plugged in but stopped or finished is not charging. |
| Dark mode | Follows the car's day/night (`screen_day_night_mode`), which Android's night mode tracks and which switches with the headlights (found by the mg4.90 probe, 2026-11-03). Optional sunset mode (mg4.90). |
| Build fixes | Replaces upstream calls to Bluetooth functions that were never published in the EVHardware submodule, so the tree compiles. The build script finds the Android SDK and writes the APK to `dist/`. |
| Diagnostics | Reports record the firmware profile, video renderer, audio mode, car audio source, microphone source and every wheel key. |

## Settings

On MG4, **Settings** is a two-pane screen like iOS Settings: a list of categories on the left and the chosen category on the right. It has Connection, Display, Sound, Car, Apps, Experimental (Plus), General, Help and About; every setting is two taps from Home. The screen is built but not yet tried in the car. Other head units keep the upstream settings page.

Some settings only reach a running CarPlay session after it reconnects. Those rows carry a **Reconnect** tag, and changing one while connected shows **Some changes apply after CarPlay reconnects. Reconnect now**. Dark mode at sunset, Car battery on iPhone and Video while parked take effect straight away.

| Setting | Where | Default on SWI165 |
|---|---|---|
| Connection type (car hotspot or Wi-Fi Direct), hotspot details, iPhone (choose or pair in the app), Wi-Fi Direct band | Connection | Car hotspot. Replaces the old Connection setup page. |
| Connect when MG4CPlay opens, Open after the car starts | Connection | As before. |
| Wi-Fi high performance | Connection | On (mg4.91). Turn off to compare freezes and skips. |
| CarPlay size, Right-hand drive, Full screen | Display | As before. |
| Resolution, Frame rate, Efficient video (HEVC) | Display | Native, 60 fps, on. HEVC is half the Wi-Fi data of H.264; turn off to compare colours. |
| Overlay video renderer, Square corners, Colour range (test) | Display > Advanced | On, on, from the video. |
| CarPlay audio over Wi-Fi, Calls over CarPlay only | Sound | On, on. Off uses the car's Bluetooth Music; calls-only disconnects the car's hands-free while CarPlay runs. A saved choice still wins. |
| Music buffer | Sound | 750 ms. Try 1000 ms if music skips on a weak Wi-Fi link. |
| Microphone (test) | Sound | Automatic. Plain microphone or call processing can be chosen to compare. |
| Send location to iPhone | Car > Driving | On (mg4.90). Asks for location permission once. Sends the car's wheel speed with it. |
| Video while parked | Car > Driving | Off. Lets iOS 27 video apps play on the car screen, only while the car reads gear P. DRM video such as Apple TV+ won't play. |
| Dark mode at sunset | Car > Appearance | Off (mg4.92). Off follows the car's day/night, which changes with the headlights. On uses sunset and sunrise at the car's position. |
| Car battery on iPhone, Directions on the dashboard (test) | Car | As before. |
| Diagnostics, updates, language, permissions | General, Help, About | Settings changes are listed in the diagnostic report. |

Hidden on MG4 because the firmware profile sets them: audio focus, advanced audio channel mapping, and the music audio channel.

## Building

EVHardware, which upstream pulls in as the `vendor/MG4ABRP` submodule, is vendored in
`evhardware/` here: the submodule's repository is no longer available. Skip the submodule step.

**Linux:** install a JDK (21 or later) and the Android command-line tools, then
`sdkmanager "platforms;android-37.0" "platforms;android-36" "build-tools;37.0.0" "ndk;28.2.13676358"`,
write `sdk.dir=…` to `local.properties`, copy the identity files as below, and run
`./scripts/build_mg4.sh` with `JAVA_HOME`, `DIPLAY_AUTH_ASSETS_DIR` and
`MG4_PLATFORM_KEYS_DIR` (the folder with `platform.pk8` and `platform.x509.pem`) set.

### On a Mac

**Keep the repo out of iCloud.** If it sits in `~/Documents` with iCloud Desktop &
Documents syncing on, iCloud makes `name 2.xml` copies of build files and the build
fails with "Duplicate resources". Either turn that syncing off, or clean before
each build:

```bash
./gradlew --stop && rm -rf build */build .gradle
```

One-time setup, from the repo folder:

```bash
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
```

The car build needs the CarPlay identity files, which are never committed. Copy
them out of any upstream release APK (`/tmp/diplay-release.apk` here) into
`.private/`, which git ignores:

```bash
mkdir -p .private/runtime-assets && unzip -o /tmp/diplay-release.apk 'assets/offline-mfi/*' -d /tmp/diplay-mfi && cp -R /tmp/diplay-mfi/assets/offline-mfi .private/runtime-assets/
```

Each build, in the same terminal window (Java from `brew install openjdk@21`, or use
Android Studio's at `/Applications/Android Studio.app/Contents/jbr/Contents/Home`):

```bash
export JAVA_HOME="$(brew --prefix openjdk@21)/libexec/openjdk.jdk/Contents/Home" && export DIPLAY_AUTH_ASSETS_DIR="$PWD/.private/runtime-assets"
```

```bash
./scripts/build_mg4.sh
```

The signed APK lands in `dist/MG4CPlay-v<version>.apk` (git-ignored). The first
build downloads Gradle, the NDK and Android SDK 37, so it takes a few minutes.

## Installing

**USB stick (no ADB):** copy the APK to a USB stick, open it in the car's file
manager and tap it to install. Each build has a higher version number, so it
installs over the last one.

**ADB:** the APK is platform-signed and installs as a system app, so the usual
`adb install` is refused by this head unit. Use:

```bash
adb push MG4CPlay-v<version>.apk /data/local/tmp/update.apk
```

```bash
adb shell "pm install -r -f -d -t /data/local/tmp/update.apk"
```

See [docs/MG4_PLATFORM_INSTALL.md](docs/MG4_PLATFORM_INSTALL.md) for why `-f`
matters.

## Diagnostics

In DiPlay: **Diagnostics > Save diagnostic report**, or **Download to iPhone**
during wireless CarPlay. Reports stay on the car unless you share them. On MG4 the
report has a `Day/night:` line, and the log has `Dark mode on (sunset)`,
`gear raw=` and `chargeStatus=` lines for the mg4.90 features. Wait a few seconds after saving before copying the file
off; some uploads have arrived empty when copied straight away.

## Updates

The in-app update check looks at **this repository's** releases (`kennyvv/DiPlay-MG4-Plus`), so an
installed Plus build is never replaced by an upstream release without SWI68 support. It checks
when the app opens and from the ↻ button on the home screen. It offers the highest
published release newer than the installed version, comparing only the numbers (so
`0.2.9-mg4.92` is newer than `0.2.9-mg4.91-hud-test`), and only an `MG4CPlay-…apk` with a
GitHub SHA-256 digest. After download it checks the digest, package name and signing key,
then installs, or opens Downloads if the head unit refuses. It needs the head unit online
and the repo public. The diagnostic report shows the last check (`Update check:`). See
[docs/OTA.md](docs/OTA.md).

## Caveats

This is **not an Apple-certified product**. The APK bundles an experimental
accessory identity recovered from public Carlinkit firmware, not an MFi identity
provisioned for this app. The bundled private key is extractable. Whether iOS
keeps accepting it is unresolved.

DRM-protected video such as Apple TV+ is not supported; this is not a licensed
FairPlay receiver.

## Documentation

- [SWI68 interface check and cluster square](docs/MG4_SWI68.md): what Plus relies on in the SWI68 firmware
- [SWI165 firmware profile and test record](docs/MG4_SWI165.md): start here
- [Wi-Fi audio plan and car test checklist](docs/MG4_SWI165_WIFI_AUDIO_PLAN.md)
- [Stock AllGo CarPlay app notes](docs/MG4_SWI165_ALLGO_NOTES.md): how the car's own CarPlay app handles calls and audio
- [Release test plan: calls, music, Bluetooth and Wi-Fi](docs/MG4_SWI165_TEST_PLAN.md)
- [Release notes](CHANGELOG.md)
- [Instructions for Claude](CLAUDE.md), and the [claude.ai Project instructions](docs/claude-project-instructions.md)
- [Platform-signed builds](docs/MG4_PLATFORM_BUILD.md)
- [Installing over ADB](docs/MG4_PLATFORM_INSTALL.md)
- [Upstream build, release and OTA notes](docs/RELEASING.md), [OTA](docs/OTA.md)

## Source and credits

DiPlay-MG4 Plus is a fork of [andrew-snape/DiPlay-MG4](https://github.com/andrew-snape/DiPlay-MG4)
by [@andrew-snape](https://github.com/andrew-snape), itself a fork of
[fatihdonmezdev/DiPlay-MG4](https://github.com/fatihdonmezdev/DiPlay-MG4)
by [@fatihdonmezdev](https://github.com/fatihdonmezdev). Both are the base of everything here;
thank you.

The whole line goes back to [xcertplay](https://github.com/shilapi/xcertplay), GPL-3.0. The home/settings UI
adapts [DiAuto](https://github.com/shihabal3amri/DiAuto), AGPL-3.0; that license is
included in `docs/licenses`. Preserve those notices when distributing modifications.

The Android 9 call microphone uses [Concentus](https://github.com/lostromb/concentus)
(BSD-3-Clause), a pure-Java Opus encoder.

SWI165 media and steering-wheel details were read from the car's own MediaService
APK and cross-checked against [MG4Control](https://github.com/SliDeeN/MG4Control),
[MG4Dash](https://github.com/Skittle6938/MG4Dash) and an SWI68 launcher project.

Plus also uses:

- [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) (upstream DiPlay): the lane
  guidance parser, the iAP2 artwork file-transfer receiver, the now-playing song parser and the
  instrument-cluster stream (from its BYD cluster map), ported and adapted.
- [SliDeeN/MG4Control](https://github.com/SliDeeN/MG4Control): where the cluster square sits on
  display 1, that a window over it shows on the cluster, the cluster-page signal, and the
  accessibility-service way of taking steering-wheel keys. Findings only; no code was copied.
- [Skittle6938/DiAuto-MG4](https://github.com/Skittle6938/DiAuto-MG4): the vendored copy of
  EVHardware (`evhardware/`), originally from
  [fatihdonmezdev/MG4ABRP](https://github.com/fatihdonmezdev/MG4ABRP); see `evhardware/NOTICE`.

SWI68 details were read from the `SWI68-29958-1300R59` OTA (system apps of the head unit).

CarPlay and its icon belong to Apple Inc.; no affiliation or endorsement is implied.
Accessory and signing keys are excluded from source archives; a car build selects
the identity explicitly through `DIPLAY_AUTH_ASSETS_DIR`. Publishing an APK makes its
bundled identity extractable.
