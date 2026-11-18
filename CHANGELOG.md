# Unreleased (DiPlay-MG4 Plus)

mg4.96-swi68.12 (test build for SWI68 and SWI165), everything below. Not yet tried in the car: test plan sections J and K.

Calls closer to the car's own CarPlay (2026-11-18; Settings > Developer, MG4 only, each off by default, apply after reconnect; see [docs/MG4_STOCK_CARPLAY_CALLS.md](docs/MG4_STOCK_CARPLAY_CALLS.md); test plan J8, J9). Tried in the emulator with an iPhone: an outgoing and an incoming call worked, the ring played on the ring output, and the car's volume showed phone volume during the call:

- **Not added: calls as PCM like stock.** Tried in the emulator: offering the call only PCM 16/24 kHz made the iPhone set up no audio at all (as mg4.67), and with Opus kept for the microphone it still chose Opus 48 kHz for the call and the ring. Stock offers no Opus anywhere, which DiPlay can't copy without losing wireless audio, so there is no switch.
- **Call focus in the log.** DiPlay's log redactor drops every line containing "PHONE ", so call focus lines never reached the report; they now name the channel `CALL` (`Audio: focus requested channel=CALL gain=2`). The request itself always went out (usage 2, tag 53, `GAIN_TRANSIENT`).
- **Calls get their own audio focus (test).** A call holds the car's audio focus for the whole call, ranked ahead of music (before, an open music stream kept the shared request a music request during a call), asks again after 200 ms if refused, and music gets it back afterwards. Log: `Audio: focus requested channel=CALL gain=2`, `phone focus asked again after 200 ms`.
- **Ringtone on the car's ring output (test).** The iPhone's ring (`alert`) plays with ring usage and `MODE_RINGTONE`, as stock, instead of on the navigation channel at navigation volume. Log: `Audio: mode set=1`, `focus requested channel=RING`.
- **Car call path at the call's own rate.** With Car echo canceller for calls, 8, 16 and 24 kHz calls open the car's phone output and microphone at that rate, as stock; 48 kHz calls are halved to 24 kHz as before.

Settings changes from car tests (2026-11-14):

- **Out of Experimental.** Map on the dashboard (with Map element size), Song on the dashboard, Album art on the song card and the Steering-wheel joystick for CarPlay work in the car, so they move to Settings > Car (Dashboard and Steering wheel). Saved choices are kept.
- **Joystick direction.** New rows **Joystick up and down** and **Joystick left and right** (Normal or Flipped), read on every key press. Up and down are flipped by default, because pushing up moved CarPlay's highlight the wrong way in the car.
- **Use car's MFi chip (test)**, in Settings > Experimental. Ported from andrew-snape's 0.4.0 (`7d9f4649`, with the 3.0-chip digest fix `8ba93c63` and the tests from `25b4f8b4`). On MG4 the app also probes the car's MFi chip at start and logs `Car MFi chip:` lines (read only). With the switch on, iAP2 and AirPlay authentication are signed by the car's chip; if it can't be used, the bundled identity is used and the log says why. Not yet tried in the car (the emulator has no chip: the probe fails cleanly and CarPlay connects as before). Test plan K1, K2.

CarPlay screen and audio hand-over, phone battery and song position (tried in the emulator with an iPhone on 2026-11-15, not yet in the car; test plan L5, L6 and M):

- **Emulator results.** Leaving CarPlay: the iPhone confirms `screen=car`, stops the main video and keeps music and the dashboard map going; coming back shows CarPlay again in about 0.4 s. Starting the radio: the iPhone confirms `mainAudio=car` and pauses; pressing play in CarPlay afterwards takes the sound back and the radio stops. Battery: `80%`, not charging. The dashboard song card shows the bar and time (it appears once the iPhone first sends the position, a few seconds after connecting). Not tried: a short interruption (borrow).
- **A screen the iPhone tears down no longer ends CarPlay.** Before, DiPlay closed the whole session whenever a video stream's connection ended, which the iPhone does on purpose when the car takes the screen. Now only a screen that ends without the iPhone's TEARDOWN (within 1.5 s) ends the session. Log: `Video: screen type=110 torn down by the iPhone; session kept`.
- **Pause the dashboard map off its page (test).** Settings > Experimental, off by default. While the cluster is not on its Navigation page, DiPlay asks the iPhone to stop drawing the cluster screen (`stopUI`), and to draw it again with a keyframe (`showUI`) when you go back; a cluster stream that starts while you are on another page is paused at once. The stream stays connected, so the map is back within about a second. Emulator: the iPhone sent no cluster frames at all while paused (before: 3-18 fps, up to 370 kbit/s), and the map came back each time. Log: `Cluster map: stopUI reason=cluster page`, `Cluster map: paused`, `drawing again`.
- **Cover-colour gradient on the song card.** With a cover, the dashboard song card's background fades from a dark tone of the cover's average colour at the top (its hue, a little more saturated, kept dark so white text stays readable) to the cluster's black at the bottom. A grey cover gives a dark grey. Replaces the glow tried first, which did not look good. Seen in the emulator.
- **No CarPlay sound at connect (being investigated).** Sometimes music played on the iPhone at connect but nothing was heard until you paused and played again; seen in the emulator on swi68.12 builds with and without these changes. On DiPlay's side every such connect looks normal (focus granted, music track playing); the car routes its music channel to an app only when it grants that app focus, and a pause and play gives DiPlay a fresh grant. Not reproduced on demand yet (reinstall, force stop, Wi-Fi drop, launcher in front: all had sound). Two additions to catch and work around it:
  - **Car source in the log.** DiPlay now follows the car's own idea of its audio source (the SAIC framework's hidden `registerAudioSourceChange`) and logs each change: `Car source: <package> usage= carPlayMusic= focusLost=`.
  - **Recheck the car's audio source (test).** Settings > Experimental, off by default. When the car names another app as its source while CarPlay music plays and DiPlay was never told it lost focus, DiPlay asks for focus again after 2 s, as a pause and play does. Log: `Car source: ... plays instead of CarPlay music although DiPlay holds focus; focus asked again=`. With it off the same line ends `(recheck is off)`.
  - Fixed on the way: the sink never reported that music had started (only that it stopped), so `carPlayMusic` was always false.
- **Fix: CarPlay calls went silent after a burst of static (reported 2026-11-18).** Cause, found in the emulator with an iPhone: the Developer switch **Car echo canceller for calls (test)** sends the call to the head unit's direct phone output, and that output stopped taking audio after the first 14-32 packets (its HAL write jammed while the Nuance canceller started, then returned nonsense sizes); DiPlay's blocking write never returned, so the call was static, then silence. The 14 Nov swi68.12 build fails the same way in today's emulator, and the same build with the switch off works, so it is not caused by the screen and audio hand-over; the emulator's audio was changed on 16-17 Nov and its sound driver logged write timeouts before any call, so the emulator may be what broke the car path (it worked in the car before). Fix: the car phone output is written without blocking; after 0.5 s without progress DiPlay closes it and plays the rest of the call on the normal call track. Emulator: `Audio: car call path stalled (no audio taken for 505 ms); the call continues on the normal call track`, and the call was heard about 2 s in. Software echo suppression stays off for such a call (it was started for the car path).
  - Also: the screen and audio hand-over and the car-source recheck now do nothing while a call or Siri plays, so they can never hand a call's audio to the car (`AudioFocusDuringCallTest`). They were not the cause.
- **Coming back asks for CarPlay.** Giving the screen back only frees it; like the stock app, DiPlay then sends `requestUI` so the iPhone shows CarPlay again (`CarPlay modes: sent requestUI`).

- **Tell iPhone when CarPlay is hidden (test).** Settings > Experimental, off by default, applies straight away. When you leave CarPlay for the car's own screens, DiPlay sends CarPlay's `changeModes` to say the car has taken the screen, and gives it back when you return, as the car's built-in CarPlay does (`CarPlayScreenHandler`). The message format and numbers come from the SWI68 firmware (`libAirPlay.so`, `libCarPlay.so`). Log: `CarPlay modes: CarPlay hidden, the car takes the screen`, `CarPlay modes: sent screen take ... ok=`.
- **Hand CarPlay audio to the car's sources (test).** Settings > Experimental, off by default, Wi-Fi audio only. When the radio or Bluetooth music takes CarPlay's music focus, the car takes the main audio (the iPhone pauses instead of playing into nothing); a short interruption borrows it; focus back gives it back. When you press play in CarPlay after that, the iPhone takes the sound back and DiPlay asks the car for audio focus again, so the radio stops. Calls and Siri focus are left alone. Log: `CarPlay modes: focus lost, the car takes the sound`, `the iPhone took the sound back`.
- **What the iPhone holds, in the log.** Every `modesChanged` from the iPhone is logged as `CarPlay modes: iPhone reports screen= mainAudio= speech= phoneCall= turnByTurn=` with the raw message, switches on or off. The MG4 Launcher gets the same as `carplay_audio`, `carplay_screen`, `navigating`, `siri_active` and `on_call`.
- **iPhone battery for the MG4 Launcher.** DiPlay has asked the iPhone for its battery since the upstream identification but ignored the answer. It now reads it (iAP2 PowerUpdate; the iPhone sends the level as a plain percentage) and sends `phone_battery` (0-100, -1 unknown), `phone_charging` (`charging`, `charged`, `not_charging`) and `phone_charger` (a cable or the wireless pad). Log: `iPhone power: battery=..% charging= charger=`. The iPhone sends it over wireless CarPlay (emulator). The log line ends with `raw=` so a car test shows the exact bytes.
- **Song position.** The iPhone's track length and position (already requested, not read) are kept with the song. The dashboard song card shows a thin bar and "1:23 / 3:45" under the title, moved on every second while playing; the MG4 Launcher gets `duration_ms`, `position_ms` and `position_at` (`SystemClock.elapsedRealtime`).

MG4 Launcher bridge (ported from the swi68 branch; not yet tried in the car, test plan L):

- **Song, cover and buttons for the MG4 Launcher.** When `nl.mg4launcher` is installed, DiPlay sends the CarPlay song, artist, album, play state and cover to its home screen, and takes its play, pause, next, previous and "take audio focus" buttons. Broadcasts are limited to apps signed with the platform key (`nl.mg4launcher.permission.CARPLAY_BRIDGE`). Log tag `DiPlay-Launcher`.
- **Phone name for the launcher.** In Wi-Fi audio mode DiPlay releases the car's Bluetooth music (and hands-free when calls are CarPlay-only), so the car reports no connected phone and the launcher's status bar went blank. DiPlay now also sends `device_name`: the name the iPhone gives over AirPlay, else its Bluetooth name.
- **Audio focus after a loss.** The Wi-Fi audio sink asks for the car's audio focus again on the next play after losing it (for example after the radio played), and the launcher can ask for it directly.

New in Plus, all under **Settings > Developer** (new page), MG4 only, each off by default and logged on the `Developer tests:` line at session start. Tried in the emulator (the car's original firmware, with an iPhone over Wi-Fi), not yet in the car: test plan section J.

- **Developer page.** All test options and test tools moved there from Sound: Microphone (test), Siri microphone as PCM (test), Record microphone (test), plus the new items below. Echo suppression on calls stays in Sound.
- **Car echo canceller for calls (test).** The head unit cancels call echo in its audio HAL (Nuance ECNR), but only for call audio on its own "Phone Direct Out" output with its "Carplay Phone input" microphone open, which Java can't reach. A small native library (`libmg4_call_audio.so`) opens both the way the car's own CarPlay does: device 0x400 connected as `carplay_source=phone`, a direct `STREAM_VOICE_CALL` track at 24 kHz, and the microphone with the car's 0x200 input flag at 24 kHz (48 kHz call audio is converted). If the output fails the call uses the normal track; if the microphone gives no audio in 1.5 s the call switches to the plain microphone. Emulator: the policy picks the right output and input and the HAL starts ECNR; whether it removes the echo can only be judged in the car. Log: `Audio: ready audioType=telephony route=car phone output`, `Microphone telephony: started source=car phone input`.
- **Test call echo.** Plays a speech-like test sound through the normal call path and then the car's path for about 20 seconds, records the call microphone, and reports how loudly it came back (`Call echo test:` lines in the report, WAVs in Download to iPhone). No phone call needed.
- **Car microphone processing for Siri (test).** Siri records through the car's Siri microphone path (voice recognition with the car's 0x100 flag, 24 kHz), which the HAL runs through its Nuance Siri processing; falls back to the normal microphone if it gives no audio. Emulator: Siri understood every request.
- **Siri own audio focus (test).** Siri asks for its own focus (assistant usage) instead of riding on music's, so the car opens its voice-assistant channel for Siri. Emulator: DSP `03 00 00 03 13 00` on each request.
- **Siri prewarm on voice button (test).** The wheel voice button sends Siri prewarm when it goes down, as the car's own CarPlay does.
- **Navigation prompts lower music (test).** On the iPhone's duckAudio, DiPlay holds navigation focus (car source 52) and calls the car's `setNaviDucking`, which is what lowers music on this car; both are undone 1 s after unduckAudio. Needs the car's own "lower media for navigation" setting. Emulator: music dipped under Apple Maps prompts and came back after. Log: `Nav duck: on ... setNaviDucking=ok`, `Nav duck: off`.

Ported from [andrew-snape/DiPlay-MG4](https://github.com/andrew-snape/DiPlay-MG4) (his mg4.97 to 0.4.0, commits `ab489ba2`, `3354013a`, `d9748480`, `a4c3f1e1`, `36c8551c`, `61b72d36`, `87494d19`, `09ee4e04`). His version scheme, language removal and SWI165-only car data are not taken. The notes below are his; none has been tried in a car yet. Why these were picked: [docs/upstream-review-2026-11-14.md](docs/upstream-review-2026-11-14.md).

- **Siri microphone fix.** The microphone's RTP clock always counted at 48 kHz, so when the iPhone asks for Opus at 24 kHz (usual for Siri) or 16 kHz (usual for calls), each 20 ms packet claimed to be 40 ms or 60 ms long and the iPhone heard speech full of gaps. The microphone now counts at the negotiated rate (the same fix upstream #644 made for playback). The 2026-11-04 report showed the microphone sending clean audio at a good level on all three sources, and Siri still failing on each. Calls get the same fix; check callers still hear you clearly. The log now shows `airplay audio format ... audioFormat=0x... rtpClock=` and the microphone line ends with `rtpClock= tsStep=`.
- **Record microphone (test).** Settings > Developer. Off by default. Keeps the first 10 seconds of each Siri request and each call as a WAV on the car, taken before encoding (the newest 8). **Download to iPhone** then sends one zip with the report and the recordings, so you can listen to what Siri and callers were sent. Turning it off deletes them. The log shows `Microphone <type> recording saved file= bytes=`.
- **Siri microphone as PCM (test).** Settings > Developer. Off by default, applies after a reconnect. Offers Siri plain audio only, so the software Opus encoder is out of the path; calls keep Opus. The log shows `Microphone speechrecognition: started ... codec=LPCM`. If CarPlay loses sound with it on, turn it off: removing Opus from every microphone format did that in mg4.67.
- **Noise floor.** Each `Microphone ... stats` line now ends with `floorDb=`, the quietest 20 ms in the window. If it sits close to `rmsDb`, the cabin is never quiet enough for Siri to hear the end of speech.
- The log and report have a `Microphone test: siriFormat= recording=` line.
- **Call echo fix (not yet tried in the car).** On 2026-11-11 the person on the other end of a call heard themselves, which stock CarPlay in the same car did not do. Calls now turn on Android's echo canceller on the microphone. The log shows `Microphone telephony: started source=VOICE_COMMUNICATION ... aec=on`. If it says `aec=unavailable`, the head unit has no canceller and echo needs another fix. Test plan D2i.
- **Calls use the plain microphone by default (not yet tried in the car).** The car's own CarPlay audio library (`libCarPlayPlatformAudio.so`, pulled from the car 2026-11-12) opens `AudioSource.MIC` for calls and `VOICE_RECOGNITION` for Siri, mono 16-bit, and never `VOICE_COMMUNICATION`. DiPlay now does the same on Automatic; before, calls asked for `VOICE_COMMUNICATION`, which this head unit may refuse or leave silent. The echo canceller is still attached and logged. The log shows `Microphone telephony: started source=MIC ... aec=...`. Test plan D2i and C8. See [docs/MG4_SWI165_ALLGO_NOTES.md](docs/MG4_SWI165_ALLGO_NOTES.md).
- **Software echo suppression on calls (not yet tried in the car).** The 0.3.3 car log (2026-11-13, call at 06:58) shows `Microphone telephony: started source=MIC ... aec=unavailable`: this head unit has no Android echo canceller, so the other person still heard themselves. Turning **Calls over CarPlay only** off doesn't change this, because the iPhone still sends the call over CarPlay. DiPlay now works out how loud the call audio it plays is, learns the delay and strength of the echo in the microphone, and turns the microphone down while it is no louder than that echo. When you talk over the other person, your voice is louder than the echo and passes through. New switch **Echo suppression on calls** (Settings > Sound, on by default) so it can be compared with it off. The log shows `swEcho=on` on the `Microphone telephony: started` line, and each 5 s stats line adds `echo=on lag=...ms corr= coupling= suppressed=%`. Recordings from **Record microphone (test)** now hold the suppressed audio. Test plan D2i.
- **More call diagnostics (not yet tried in the car).** To tell whether a call also reaches the car's own phone through Bluetooth hands-free: the log now says whether hands-free was kept or released (`car Bluetooth: hands-free kept connected` or the release lines), the hands-free link state after the handoff and on every change, whether the car's Bluetooth call audio opens (`hands-free call audio (SCO) state=`), and any call the car's phone sees (`car phone call changed`). The `Audio: mode set=2` line adds `scoOn= speaker= btA2dp=`. The report header and the session line show `callsOverCarPlayOnly=`. `Microphone telephony: echo delay found lag=...ms` states how long the call takes to come back from the speakers, which also shows the delay callers hear. Test plan D2k.
- **Cluster turn distance matches CarPlay (not yet tried in the car).** The instrument cluster showed the iPhone's raw distance to the next turn (537 m, then 512 m) while CarPlay's screen showed 600 m, then 500 m. The cluster now gets the value CarPlay shows (the iPhone's display text and units), and if the iPhone does not send one, the raw distance rounded to 100 m above 500 m, 50 m above 100 m and 10 m below. A new distance is sent only when the shown value changes. The log has a `Cluster nav: distance raw=... phoneText=... units=... shown=...m via phone|rounded` line for each change: `via rounded` means the iPhone's display text was missing or not trusted. Test plan D4.

# Earlier (SWI165 fork and Plus)

mg4.96-swi68.11, the first DiPlay-MG4 Plus release.

- **Updates come from this repository.** The in-app update check reads releases of `kennyvv/DiPlay-MG4-Plus` instead of `andrew-snape/DiPlay-MG4`, so a Plus install is never offered an upstream build without SWI68 support. Earlier swi68 builds still check andrew-snape's releases; install this one by hand.

mg4.96-swi68.10 (test build for SWI68 and SWI165).

- **Map renderer setting removed.** The overlay SurfaceView looked clearly better in the car and performs fine (2026-11-08), so it is the only renderer again, hiding by parking where the cluster never looks. The TextureView option from swi68.8-9 is gone.

mg4.96-swi68.9 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Map renderer setting.** Experimental row next to the map: Overlay (default) or Texture. Overlay is the SurfaceView the map was first seen working with; since its layer ignores window transparency, it hides by parking in the display's top-left corner, which the cluster never shows (it takes only the square). Texture is the TextureView from swi68.8, hiding by transparency in place. The song card and lane strip keep hiding by transparency.

mg4.96-swi68.8 (test build for SWI68 and SWI165). Fixes from the first car test (2026-11-08): album art, map, song card and wheel control all worked.

- **Song card drew over the navigation page.** Cluster windows were hidden by moving them off the display, but Android pulls such a window back to the edge (x 540 on the 800 px display, beside the square at 482), so the song card stayed visible over the map. Hidden windows now stay on the square and turn fully transparent. The map draws on a TextureView, since a SurfaceView's layer ignores window transparency.
- **Map element size.** Apple Maps lays the cluster stream out by its pixel size, so at 264 px everything was very large. New Experimental row next to the map: Large (264 px, as before), Medium (400 px, the default) or Small (528 px); the stream is scaled into the 260 px square.
- **Wheel control in Apple Maps.** Up and down nudged the knob, which pans Apple Maps when the map has focus. They now turn the knob, which steps CarPlay's highlight from element to element; left and right stay nudges.

mg4.96-swi68.7 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Steering-wheel joystick for CarPlay (experimental).** Settings > Experimental. Hold the centre of the right-hand joystick while CarPlay is on screen to switch CarPlay control on: up, down, left and right nudge CarPlay's highlight (the knob MG4CPlay already declares) and the centre selects; hold it again, or leave CarPlay, to give the joystick back to volume and tracks. The car sends wheel keys as broadcasts no app can stop, so an accessibility service filters the joystick first (297-301 only), the way MG4Control's advanced shortcuts do; out of CarPlay control, a short centre press is held for 500 ms and replayed to the car unchanged. The switch adds or removes only MG4CPlay's service in Android's accessibility list. Logged as `Wheel:` lines.

mg4.96-swi68.6 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Lanes drawn by MG4CPlay (experimental).** Lane guidance on the dashboard is now Off, Car icons or Drawn by MG4CPlay. The cluster's own lane icons know five directions and seven combined lanes, so slight and sharp turns and other combinations get flattened. Drawn by MG4CPlay draws every arrow at the iPhone's angle (the way to go white, other usable arrows grey, unusable lanes dim) in a transparent band over the navigation card's lane row (144-184 px of the square, read from the SWI68 `fragment_navi` layout), and sends the car an empty lane list. Hidden on other pages and under the dashboard map. Logged as `Cluster lanes:` lines.

mg4.96-swi68.5 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Settings > Experimental.** A new category for features not yet tried in a car, each off by default, in the order they were added: Map on the dashboard, Lane guidance on the dashboard, Song on the dashboard, Album art on the song card. Map and song moved here from Car. Fixes (SWI68 detection, no Bluetooth settings while hands-free is up) stay always on.
- **Lane guidance is now a switch.** Off, the iPhone isn't told the car can store lanes, so the identification is exactly as before swi68.3. Needs Directions on the dashboard.
- **Album art on the song card (experimental).** Ports upstream DiPlay's iAP2 file-transfer receiver for Now Playing artwork. Off, file-transfer sessions are left unanswered as before; on, covers are reassembled (2 MB limit), decoded off the main thread near the card size, and shown only for the song whose artwork transfer id they carry.

mg4.96-swi68.4 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Song on the dashboard (experimental).** New in Settings > Car, off by default. Shows the CarPlay song (title, artist, album, a paused mark) in the cluster's centre square on its Music page, drawn by MG4CPlay from the iPhone's now-playing updates (song parser ported from upstream DiPlay). The car's own song card can't be used: read from the SWI68 firmware, every way into it is bound to a stock app by package name (the AllGo media browser, fed only by AllGo's CarPlay service; the Amazon online music app; the Bluetooth stack). The map and the song card share one cluster-square window helper (`Mg4ClusterSquare`); when the cluster page can't be read, the square goes to the map. Logged as `Cluster song:` lines.

mg4.96-swi68.3 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Lane guidance on the dashboard.** With Directions on the dashboard on, CarPlay's lane arrows (iAP2 0x5204) now show on the cluster's navigation card: grey lanes and the lanes to take lit, including combined arrows such as straight-and-right. The car asks the iPhone for lanes the way upstream DiPlay does (MaxLaneGuidanceDescriptionLength and MaxLaneGuidanceStorageCapacity), the lane parser is ported from upstream, and the lanes go to the car's `IMapService.vehicleLaneInfoChanged` (48) as the cluster app's lane codes, read from the SWI68 `clusterprojection` app. Logged as `Cluster nav: lanes` lines with the raw lanes from the iPhone.

mg4.96-swi68.2 (test build for SWI68 and SWI165). Not yet tried in the car.

- **Map on the dashboard (experimental).** New in Settings > Car, off by default: Off, Map or Next turn. CarPlay is asked for its instrument-cluster screen (the alternate screen, stream 111, which upstream DiPlay drew on BYD clusters before the BYD code was removed) and it is shown in the square in the middle of the cluster. The cluster shows that square from the head unit's second Android display (display 1, 800x480), where the car's `clusterprojection` app draws a 260 px square; MG4Control found that a window over it shows on the cluster. The map covers the square only on the cluster's Navigation page and moves aside on the others, so the Music page keeps the car's song card. Logged as `Cluster map:` lines. Unknowns: whether the head unit decodes two CarPlay video streams at once, and how Apple Maps lays out such a small square.

mg4.96-swi68.1 (test build for SWI68). Not yet tried in the car.

- **SWI68 is treated like SWI165.** The SWI68-29958-1300R59 firmware is the same eh32 platform as SWI165: same launcher, media service, nFore Bluetooth, wheel-key broadcast, cluster MapService and AllGo CarPlay app. SWI68 was unknown firmware before, so it got none of the SWI165 fixes and fell back to Bluetooth Music, which sent the driver to Bluetooth settings ("connect to Bluetooth for audio") each time CarPlay opened. It now gets Wi-Fi audio, the overlay video renderer, square corners, wheel keys and the other SWI165 defaults. Every SWI165 interface DiPlay calls was checked against the SWI68 apps and matches; see [docs/MG4_SWI68.md](docs/MG4_SWI68.md).
- **No Bluetooth settings over CarPlay while hands-free is connected.** In Bluetooth Music mode, a phone with no A2DP link but a live hands-free link is left alone instead of opening Bluetooth settings: iOS drops A2DP to the car during wireless CarPlay on purpose.
- **EVHardware vendored.** The `vendor/MG4ABRP` submodule's repository is gone (404), so the tree builds against the EVHardware copy vendored in [Skittle6938/DiAuto-MG4](https://github.com/Skittle6938/DiAuto-MG4) (`evhardware/`, commit 5e2e6d8), which also knows SWI68 and its vehicle-speed scale.

mg4.96 (test build). Not yet tried in the car: test plan E6, F12.

- **Pair your iPhone inside the app.** On MG4, **Pair another** (or **Open Bluetooth** when no iPhone is paired) now searches for nearby phones in MG4CPlay, lists them, and pairs when you tap one, with the confirm-the-code step shown in the app. It saves the iPhone and connects. If the car's Bluetooth refuses any step, it opens the Android Bluetooth settings as before. Log lines start with `Pairing:` in the Settings changes section of the report.
- **USB CarPlay removed on MG4.** The mg4.95 car log shows the iPhone could not be claimed (`Android could not claim USBMUX interface 1`) and then left the USB bus, which fits the car's own wired CarPlay holding it. That is outside the app's control. The USB button, the USB choice in Settings > Connection and the VPN prompt are gone, and a saved USB choice now means wireless. The USB code stays in the tree, unused.
- **Stall diagnostics.** When audio or video stops arriving for a second or more, the `Receive:` line now ends with `stall=[...]`: hotspot packets in and out, signal, load and garbage collection time, at most once every 10 seconds. The mg4.95 drive showed two 2 to 5 second freezes where both streams stopped together and the car received almost nothing, which points to the Wi-Fi link or the iPhone, not the app. The app cannot raise the kernel's 512 KiB socket buffer cap.
- **Music buffer 2000 ms.** New longest setting. It rides out a short Wi-Fi dropout and adds about a second of music delay. Calls, Siri and prompts are unaffected.

New Settings screen on MG4: built and unit tested, not yet tried in the car (test plan section H).

- **Two-pane Settings on MG4.** A category list on the left (Connection, Display, Sound, Car, Apps, General, Help, About) and the chosen category on the right, like iOS Settings. Short lists (CarPlay size, colour range and so on) open as a page of rows with a tick, not a dialog. Other head units keep the old settings page.
- **Reconnect is clearer.** The **Apply and reconnect** dialog button is gone. Rows that need a reconnect carry a **Reconnect** tag, and changing one while CarPlay runs shows **Some changes apply after CarPlay reconnects. Reconnect now**.
- **Behaviour change.** Dark mode at sunset, Car battery on iPhone and Video while parked no longer reconnect CarPlay by themselves; they take effect straight away.
- **Connection setup is now Settings > Connection** on MG4. Send location to iPhone is under Car > Driving.
- **Settings changes in the report.** The diagnostic report lists the last 40 changes (`--- Settings changes (newest last) ---`).
- **MG4 only.** Removed the code for other cars that came from upstream: the windshield HUD and cluster directions, the ADB battery, wheel-speed and parked readers, the cluster-map screen and its settings, and their steering-wheel key codes. MG4 cluster directions, battery, charging, wheel speed, gear and video while parked are unchanged. Saved battery, connector, low-charge and video choices carry over. Not yet tried in the car.

Four fixes ported from upstream DiPlay ([shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)). Not yet tested in the car.

- **USB microphone at 48 kHz** (upstream #1148). When the iPhone asks for 48 kHz PCM without naming a packet size, MG4CPlay now sends 10 ms per packet instead of 20 ms, so each packet fits the 1,500-byte link and no longer splits in two. Upstream, those split packets made voice messages, Voice Memos and dictation over USB record silence. Siri (24 kHz) and calls are unchanged. The `Microphone <type>: started` line now shows `codec=` and `frameMs=`.
- **Faster wireless Bonjour** (upstream #972). The address handed to JmDNS already carries its `carplay-…` name, so JmDNS doesn't ask the system resolver for it at start-up.
- **Quieter USB log** (upstream #1144). Bare USB TCP acknowledgements and large writes are logged for the first 50 only; connection opens and closes are always logged. Logcat only.
- **iPhone USB id hidden in the report** (upstream #1141). The hyphenated iPhone USB id (`00008110-001E2C3A0123401E`) is now redacted like other identifiers.

# MG4CPlay 0.2.9-mg4.95 (SWI165 fork), 2026-11-03

- **USB CarPlay now authorises itself when the dialog cannot start.** In the mg4.93 car report the head unit said the VPN dialog existed, so the app launched it and crashed (`CarPlayHostActivity.kt:584`). If that launch fails, the app now falls back to authorising its own VPN, as it does when the dialog is missing. Log: `USB VPN: consent dialog launch failed` then `authorised for this app` or `self-authorisation failed`.

# MG4CPlay 0.2.9-mg4.94 (SWI165 fork), 2026-11-03

- **USB CarPlay no longer crashes on a missing consent dialog.** If launching Android's VPN dialog still fails on this head unit, the app now logs `USB VPN: consent dialog launch failed` and shows that USB CarPlay could not start, instead of closing.
- **Crash report in English.** The previous-crash dialog was in Turkish (from upstream); it now reads Previous crash report, Copy, Close.

# MG4CPlay 0.2.9-mg4.93 (SWI165 fork), 2026-11-03

From the mg4.92 car test.

- **Siri on hold.** Holding the wheel voice button now opens Siri as soon as the car reports it held (about half a second), instead of on release; letting go afterwards does nothing. A short press still opens Siri on release. The log shows `Wheel raw key: code=287 down=true long=true -> Siri sent=true`.
- **USB CarPlay works without the missing dialog.** USB CarPlay needs Android's VPN permission dialog, which this head unit doesn't have, so the app crashed opening it (2026-11-03). Choosing USB now counts as consent: the app (a system app on MG4) authorises its own VPN as the dialog would, for this app only. If that fails it says USB CarPlay could not start, instead of crashing. Log: `USB VPN: authorised for this app without the system dialog` or `self-authorisation failed`.
- **Australian labels.** The charging connector choice reads "CCS2 and Type 2 · Europe and Australia" (the MG4's plug here, and already the default), and the MG4 "Car battery for the iPhone" switch no longer says "needs ADB" (it reads the car directly).
- **Gears.** The selector reads 1 P, 2 R, 3 N, 4 D (car test 2026-11-03), so reverse and neutral now go to the iPhone correctly; before, everything but P was sent as D.
- **Microphone level.** Each `Microphone ... stats` line now ends with `peakDb= rmsDb= clipped=`, to tell a microphone that is too quiet or clipping from a network problem. In the mg4.92 test Siri's audio arrived complete and on time (no drops, encoding 5 ms per 20 ms frame), so its level is the next thing to check.
- **HEVC colour.** Colour range set to Limited still looks washed out with HEVC (test F11), so the car's video plane ignores the range hint. H.264 shows the right colours. No code change yet.

# MG4CPlay 0.2.9-mg4.92 (SWI165 fork), 2026-11-03

From the mg4.91 car report of 2026-11-03.

- **Dark mode follows the car.** The day/night probe found the car's own setting (`screen_day_night_mode`), which switches with the headlights; Android's night mode, and so CarPlay, already follows it. **Dark mode at sunset** is now off by default (a saved choice wins). In the report, `Dark mode on (car)` or `(sunset)` says which decided, and `(car, no position for sunset yet)` when sunset mode has no position; the old log said "sunset" in that case. `AirPlay dark mode=` now reaches the report too.
- **Vehicle lines in the report.** The MG4 wheel speed (`MG4 wheel speed started`, `MG4 first wheel speed`), gear (`MG4 gear raw=`) and charging (`MG4 battery ... chargeStatus= charging=`, on change) lines only went to logcat, which the report doesn't include. They now go to the session log, so tests D5–D7 can be read from a report.
- **Video while parked** label on MG4 no longer says "needs ADB" (#13).
- **Wheel voice button opens Siri.** The car test found the voice button is raw key 287. A press, short or held, opens Siri on release while CarPlay runs. The log shows `Wheel raw key: code=287 ... -> Siri sent=true`.
- **Update check.** Offers the highest newer release rather than the first one GitHub lists, and the diagnostic report shows the last check (`Update check: <time> installed=<version> repo=andrew-snape/DiPlay-MG4 up to date | found <version> | failed <error>`). README corrected: the check has used this fork's releases since mg4.84, not upstream.
- **Wi-Fi scan log.** The mg4.91 drive still showed audio hiccups about every 160 s with the Wi-Fi lock held, and the lost packets were counted by the car's UDP receive buffer (`udpRcvbufErrors`), with nothing lost on the Wi-Fi radio (`ap0RxDrop=+0`). Each scan the head unit finishes is now logged (`Wi-Fi scan done ... sinceLast=`), to check whether scans line up with the hiccups. Read-only; it never starts a scan.

# MG4CPlay 0.2.9-mg4.91 (SWI165 fork), 2026-11-02

Two fixes from upstream [xcertplay](https://github.com/shilapi/xcertplay) 1.4.1. Not yet tested in the car.

- **Wi-Fi high performance.** While wireless CarPlay runs, MG4CPlay holds Android's high-performance Wi-Fi lock, which keeps the car's Wi-Fi out of power saving. It is aimed at the stream freezes about every 160 s (test F10). New switch in **Display and performance**, on by default; turn it off to compare. The log shows `Wireless Wi-Fi lock acquired: mg4cplay:high-performance` (or `unavailable` if the car refuses it).
- **Location sent as the iPhone asks.** Each NMEA sentence (position, course, wheel speed) now goes in its own field, only the types the iPhone requested are sent, and an interval longer than a second is honoured. Before, all of them went in one field every second. The log's `start-location-information` line now shows the intervals too. A request naming no known type still gets everything.

# MG4CPlay 0.2.9-mg4.90 (SWI165 fork), 2026-11-02

Not yet tested in the car. Test plan steps D5–D8 cover it.

- **Car GPS and wheel speed to the iPhone.** **Report location to iPhone** is now on by default on MG4 (a saved choice wins), and asks for location permission once. With it, MG4CPlay sends the car's own wheel speed and gear with the GPS fix, read directly from the car without adb, so Apple Maps keeps moving in tunnels and car parks. Only gear 1 = P is known on SWI165; other gears are sent as D, and the log shows `gear raw=<n>` so a test can identify R.
- **Charging in Apple Maps.** The "charging" flag now comes from the car's charging status: AC (1) or DC (10) is charging; connecting, connected-but-waiting and stopped are not. Without a status it falls back to the plug or power flowing into the pack. The battery log line shows `chargeStatus=` and `charging=`.
- **Dark mode at sunset.** New **Dark mode at sunset** setting in **MG4 vehicle**, on by default. CarPlay goes dark between sunset and sunrise at the car's position, checked every minute and at each connection. The last place is kept, rounded to about 10 km, for start-up before a GPS fix. With no place, or the setting off, it follows the head unit's night mode as before. The log shows `Dark mode on (sunset)`.
- **Day/night probe.** No car day/night signal is known yet. On MG4 the log records Android's night mode, the screen brightness and any setting whose name suggests day/night, and every change to one while CarPlay runs (`Day/night probe:`). The diagnostic report has a `Day/night:` line.

# MG4CPlay 0.2.9-mg4.89 (SWI165 fork), 2026-11-02

Fixes ported from upstream DiPlay 0.2.19–0.2.22 ([shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay)).

- **Call and Siri audio clock** (upstream #644). For 16 kHz and 24 kHz Opus streams (calls, Siri, navigation prompts), DiPlay told the iPhone its playback position at the 48 kHz decode rate, up to 3x too fast. It now counts at the stream's own RTP rate. May help call quality; check whether the far end sounds clearer.
- **Wi-Fi drop detection on Android 9** (upstream #1042). TCP keepalive on the CarPlay control connection, so DiPlay notices about 20 s after the iPhone drops off Wi-Fi and reconnects, instead of a frozen picture until the screen is touched. Worth checking against the 160 s freezes (F8, F9).
- **Pinch and two-finger gestures** (upstream #912). Each finger keeps its own touch slot from press to release, so pinching in Apple Maps no longer jumps when the first finger lifts.
- **Drags over a slow link** (upstream #1134). A drag sends the finger's newest position instead of queueing every old one.
- **Hardening** (upstream #1122, #1123, #1125, #1126, #1128). Control requests, iAP packages, Bonjour probe replies, binary plists and USB network blocks are size-bounded. Screen, iAP, video-settings and event connections, and clock-timing replies, are accepted only from the iPhone that holds the control session. Upstream did not test this in car hotspot mode: if the picture or touch stops working, look for `rejected sender outside the control session` in the report.
- Not ported because the MG4 doesn't need them: upstream #804 (Android 9 here has no audio focus or MediaSession) and #994 (the call mode already returns to `MODE_NORMAL`).

# MG4CPlay 0.2.9-mg4.88 (SWI165 fork), 2026-11-02

- **Download to iPhone (test).** Settings > Diagnostics has a new **Download to iPhone** button next to **Save diagnostic report**, so a report no longer needs a USB stick. During wireless CarPlay the iPhone is already on the car's Wi-Fi (the car hotspot `ap0`, or the Wi-Fi Direct `p2p-*` group), so MG4CPlay builds the same redacted report and serves it once from a small web server bound only to that network's IPv4 address. A dialog shows a QR code and the link; scan it with the iPhone camera, and Safari downloads `MG4CPlay-<time>.txt` to share with AirDrop or save to Files. The link has a random 128-bit one-time path (anything else gets a 404) and stops after one download, after 5 minutes, or when the dialog closes. The button is greyed out in USB mode or when wireless CarPlay isn't connected. The log shows `Report download served bytes= ms=`, `Report download expired` or `Report download stopped`.

# MG4CPlay 0.2.9-mg4.87 (SWI165 fork), 2026-11-02

- **Wi-Fi Direct on Android 9: check it is on first.** mg4.86 car test: 12 tries over 5 restarts, all failed. The channel call never answered (`setChannels ... answered=false`) and every group request came back BUSY (`create rejected code=2`), while the group query still answered. That is how Android 9's Wi-Fi Direct service behaves while it is disabled. DiPlay now reads Android's Wi-Fi Direct state before starting, waits up to 3 s for it to come on, and otherwise stops at once with "Wi-Fi Direct is switched off on this car ... Choose Car hotspot", instead of 8 s of failing calls per try. The log shows `Wi-Fi P2P legacy state= waitedMs= carHotspot=`, and a rejection now names its reason and the state (`create rejected code=2 reason=busy state=`).
- **5 GHz on Automatic.** The MG4 reports no 5 GHz support (`fiveGhzSupported=false`) yet runs its own hotspot on channel 36, so mg4.86 skipped 36 and 149 and planned 2.4 GHz only. A car hotspot saved on 5 GHz now counts as 5 GHz support. The preflight line adds `carHotspotBand=` and `fiveGhzCapable=`.
- mg4.86 car test, car hotspot: sessions connect and run; audio loss bursts show `udpRcvbufErrors` (up to +180) with no Wi-Fi errors (`ap0RxErr`/`ap0RxDrop` +0), so loss was in the car's receive buffer, not the air.

# MG4CPlay 0.2.9-mg4.86 (SWI165 fork), 2026-11-01

- **Wi-Fi Direct on Android 9 (test).** Wi-Fi Direct was hidden on Android 9 because the upstream code needs Android 10 calls to set the group's name, passphrase and channel. MG4CPlay runs as the system user on the MG4, so a separate Android 9 path now uses the older `createGroup` (system-made `DIRECT-xx` name and passphrase, sent to the iPhone over Bluetooth as usual) and the hidden `setWifiP2pChannels` call to steer the channel. Choose **Wi-Fi Direct** in the wireless link settings, with the car's Wi-Fi switch on and the car hotspot off.
- New **Wi-Fi Direct band** choice on Android 9: **Automatic (5 GHz first)** tries channels 36 and 149, then 6, 1 and 11, then the system's own choice; **2.4 GHz only** skips 5 GHz. Each channel gets up to 7 s before the next is tried. The channel is read back from the driver and never assumed.
- On disconnect the group is removed, the persistent group Android 9 saves is deleted, and the channel restriction is cleared so the stock apps are unaffected.
- The log shows `Wi-Fi P2P legacy plan channels=`, `setChannels operating= ... code=`, `create accepted` or `create rejected code=`, and `Wi-Fi P2P ready mode=LEGACY band= channel= frequencyMHz=`.

# MG4CPlay 0.2.9-mg4.85 (SWI165 fork), 2026-11-01

- **Temporary: the diagnostic report carries the stock AllGo apps' code.** The cluster reads CarPlay song details only from the stock `com.allgo.remoteui.mediabrowserservice` media session (see [docs/MG4_SWI165_CLUSTER_MEDIA_NOTES.md](docs/MG4_SWI165_CLUSTER_MEDIA_NOTES.md)). That app is exported with no permission and has a `...RemoteUIMediaBrowserServiceInterface` action that looks like its feed, but its APK has no code: it was compiled ahead of time into `oat/<abi>/*.vdex` and `.odex` beside the APK. On MG4, the report now ends with a `Stock app code` section holding those files for `com.allgo.remoteui.mediabrowserservice` and `com.allgo.rui`, base64-encoded with size and SHA-256 (read-only, 6 MB per file, 12 MB in all). This replaces pulling them with ADB and will be removed once read.

# MG4CPlay 0.2.9-mg4.84 (SWI165 fork), 2026-11-01

- **Hide lost call packets.** mg4.83 car test: calls work over the car's phone path (`call track car source=53`, `mode set=2`, then `mode set=0` at hang-up), but the call had some static. The log shows no loss for 40 s, then 30-60% of call packets missing for the last 25 s (gaps up to 2 s, and the whole Wi-Fi link slowed at the same time). The call stream gives no control port, so nothing can be resent, and the platform decoder skipped the gaps. Wi-Fi calls now decode in software (Concentus): a gap of up to 10 frames (200 ms) is filled from the next packet's in-band FEC where the iPhone includes it, and otherwise with Opus packet-loss concealment. The software decoder warms up with the encoder at connect. The log shows `call decoder=software, hides lost packets` and, at hang-up, `call loss packets= fec= concealed= unfilledGaps=`.
- mg4.83 car test: navigation prompts arrive as stream type 101, `audioType=default`, and play on DiPlay's navigation channel with no loss. The iPhone sends `duckAudio` / `unduckAudio` around each prompt; DiPlay doesn't turn music down for them yet.

# MG4CPlay 0.2.9-mg4.83 (SWI165 fork), 2026-11-01

- **Call audio like the stock CarPlay app.** Read from the car's AllGo CarPlay APK (see [docs/MG4_SWI165_ALLGO_NOTES.md](docs/MG4_SWI165_ALLGO_NOTES.md)): it plays calls as `USAGE_VOICE_COMMUNICATION` tagged with the SAIC car source `key_car_source_type=53`, and puts the car in `MODE_IN_CALL` while the call plays. DiPlay's mg4.65 voice track had neither and was silent, so mg4.66 to mg4.82 played calls on the music stream. Wi-Fi calls now use the stock setup and set `MODE_NORMAL` again when the call track stops. Music is unchanged. The log shows `call track car source=53` and `mode set=2 now=2`. If calls are silent, mg4.82 is the fallback.

# MG4CPlay 0.2.9-mg4.82 (SWI165 fork), 2026-11-01

- **Calls over CarPlay only** is now on by default on MG4 (a saved choice still wins). The mg4.80 log's one call with it off arrived over Wi-Fi, but 0.1 s after it started the car's own Bluetooth phone took audio focus (`focus change=-1`), and the car was still in call mode (`mode=2`) at the next start-up. With hands-free released, the iPhone has only CarPlay to send calls to.

# MG4CPlay 0.2.9-mg4.81 (SWI165 fork), 2026-11-01

- **Calls over CarPlay only:** keep hands-free released for the whole session. mg4.80 released it at handoff (and checked at 2 and 5 s), but the iPhone reconnects it on its own later, and calls then went back to Bluetooth. DiPlay now watches the car's hands-free connection and releases it again each time it reconnects during CarPlay, logging `hands-free reconnected ... releasing again`.
- Microphone: warm up the software Opus encoder when CarPlay connects. mg4.80 showed the first half-second of each call at 23 ms per 20 ms frame (worst 173 ms) before settling at 5 ms, which added delay at the start of every call and Siri request.
- Fix the display list (mg4.79) never appearing: the diagnostic privacy filter drops lines containing `name=`.
- mg4.80 car test: music resend requests now reach the iPhone (the music stream's SETUP carries `controlPort`; call and Siri streams don't). Hands-free release works. Steering-wheel raw codes confirmed: 297 volume up, 298 volume down, 299 previous, 300 next, 3 home.

# MG4CPlay 0.2.9-mg4.80 (SWI165 fork), 2026-11-01

- New **MG4 vehicle > Directions on the dashboard (test)** (SWI165, off by default). Sends CarPlay's next turn, distance, street, remaining distance and time to the instrument cluster's navigation area. Read from the car's own APKs: the cluster takes navigation from `com.saicmotor.adapterservice`, whose exported `MapService` accepts the sat-nav's guidance (`guideStatusChange`, `guideInfosChange`, `roadInfoChange`, `remainingDistanceChange`, `remainingTimesChange`) and relays it to the cluster app and the cluster ECU. CarPlay maneuver types are mapped to the cluster's own turn icons. The stock CarPlay app does not do this on SWI165. Each turn change is logged (`Cluster nav:`).

# MG4CPlay 0.2.9-mg4.79 (SWI165 fork), 2026-11-01

- Log every Android display once per session (id, name, size, density, flags, whether it accepts presentations), to find out whether the SWI165 instrument cluster is an Android display. If it is, CarPlay's own instrument-cluster map could be shown there, as upstream does on other cars.

# MG4CPlay 0.2.9-mg4.78 (SWI165 fork), 2026-11-01

- Steering-wheel keys, from the car's SystemUI (`PhysicalKeysBroadcastReceiver`): SystemUI receives raw key codes (297 volume up, 298 volume down, 299 previous, 300 next, 301 mute, 291 phone, 286 and 17 the custom keys, 288 HVAC, 3 home) and reformats the wheel rocker to KEY_CODE 1 to 5 in the wheel's default mode (6 to 10 and 11 to 15 in its other modes). DiPlay now also logs every raw key (`Wheel raw key:`), so the voice button's code can be identified.
- With **Calls over CarPlay only** on, a short press of the steering-wheel phone key answers or ends the CarPlay call (CarPlay telephony hook switch).

# MG4CPlay 0.2.9-mg4.77 (SWI165 fork), 2026-11-01

- New **Display and performance > Colour range (test)**: from the video (default), limited (video range) or full (PC range). Whites still look washed out next to USB CarPlay with both HEVC and H.264, so the codec isn't the cause. The likeliest remaining cause is the colour range the car's video plane assumes: limited-range video shown as full range gives grey blacks and dim whites. The choice is passed to the decoder with BT.709 and SDR transfer, and logged on the decoder line (`colourRangeHint=`) next to the decoder's own output colour.

# MG4CPlay 0.2.9-mg4.76 (SWI165 fork), 2026-11-01

- **Efficient video (HEVC)** is back in Display and performance on MG4, still on by default. Whites still look slightly washed out next to USB CarPlay, and the stock AllGo app (read from its APK) decodes H.264 with a plain decoder format, while DiPlay uses HEVC. Turning the switch off sends H.264 like the stock app, to compare. The log's video output line records the colour standard, range and transfer for each.

# MG4CPlay 0.2.9-mg4.75 (SWI165 fork), 2026-11-01

- **Square corners** is now on by default on SWI165 (confirmed on the car in mg4.70) and no longer marked as a test. Other firmware keeps view areas. A saved choice still wins.
- Video stall watchdog: no keyframe requests while CarPlay is hidden behind another app. mg4.71 logged one every ~10 s in that state, each making the iPhone send a full frame nobody could see. A keyframe is still requested when CarPlay comes back.

# MG4CPlay 0.2.9-mg4.74 (SWI165 fork), 2026-11-01

- Ask the iPhone to resend lost audio packets. The mg4.71 counters showed every resend request was skipped because DiPlay didn't know the iPhone's control port; it now takes it from the audio stream's SETUP when given. The log records the request keys and the port.
- New **Audio routing > Microphone (test)** choice: Automatic (default: call processing for calls, voice recognition for Siri), call processing for calls and Siri, or the plain microphone. For comparing how well Siri and callers hear you; the microphone line in the log shows the source used.
- mg4.71 car test (14:01 to 14:16): music, calls and Siri audio arrived almost clean (call and Siri streams lost nothing; music lost a few short bursts). The car's UDP and hotspot counters stayed at zero, so the loss that remains is between the iPhone and the car.

# MG4CPlay 0.2.9-mg4.73 (SWI165 fork), 2026-11-01

- Microphone diagnostics: every 5 s while the microphone runs (calls and Siri), the log records frames sent, audio time against real time, Opus encode time (average and worst), the longest wait for the microphone and the bitrate. If encoding can't keep up on the head unit, the delay grows over a call, and this line shows it.
- Software Opus encoder effort lowered (complexity 5 to 3) so each 20 ms frame encodes well within real time. mg4.70 testing found the microphone laggy compared with USB CarPlay.

# MG4CPlay 0.2.9-mg4.72 (SWI165 fork), 2026-11-01

- New **Audio routing > Calls over CarPlay only** switch (off by default). With Wi-Fi audio, it also disconnects the car's hands-free (HFP) link to the iPhone after handoff, as factory wireless CarPlay does, so the iPhone can't send calls over Bluetooth. The car's own phone screen and call button don't work while CarPlay runs. DiPlay asks the car to reconnect hands-free when CarPlay closes. Each release and reconnect is logged.
- Fix the mg4.71 build: the About screen's update section referred to the settings screen's MG4 check.

# MG4CPlay 0.2.9-mg4.71 (SWI165 fork), 2026-11-01

- Diagnostics for audio loss: every 5 s while packets are missing, the log records resend requests sent to the iPhone, packets recovered, whether resends could be sent at all, and the car's own network counters (UDP receive errors and buffer overflows, and `ap0` hotspot drops and errors). This tells Wi-Fi loss in the air apart from loss inside the head unit. mg4.70 lost about 1 in 7 music packets and half the call packets before they reached DiPlay.
- Settings tidied for MG4. Hidden because the firmware profile sets them: efficient video (HEVC is now always on for MG4), audio focus (Wi-Fi audio takes it; Bluetooth Music must not), advanced audio channel mapping and the music audio channel (MG4 uses the music stream), and in-app updates (they offered upstream builds without this fork's changes). Navigation audio channel stays until Maps prompts are tested.
- Fix the music buffer labels. They read 300, 500 and 1000 ms but set 500, 750 and 1000 ms. They now read 500 ms, 750 ms (default) and 1000 ms.

# MG4CPlay 0.2.9-mg4.70 (SWI165 fork), 2026-11-01

- Test switch for the rounded black corners the iPhone draws on CarPlay (the stock CarPlay app shows square corners). **Display and performance > Square corners (test)** describes the screen without view areas, the way older factory receivers do. Off by default; the log's video renderer line records `viewAreas=true|false`.

# MG4CPlay 0.2.9-mg4.69 (SWI165 fork), 2026-11-01

- Fix: no CarPlay audio at all in mg4.67 and mg4.68. Those builds stopped offering Opus for the microphone on Android 9, and the iPhone then treated the car as screen-only: CarPlay was not offered as an audio output, so music and calls stayed on Bluetooth while DiPlay had released the car's Bluetooth music. DiPlay offers Opus again.
- Call microphone on Android 9: Android 9 has no Opus encoder, so DiPlay now encodes the microphone in software with Concentus, a pure-Java Opus encoder. The platform encoder is still used where one exists. The microphone log line shows which (`opus=software` or `opus=platform`).

# MG4CPlay 0.2.9-mg4.68 (SWI165 fork), 2026-11-01

- Calls: the call audio player stalled on every call in mg4.65 and mg4.66. Call audio arrived from the iPhone and the player was set up, but it never logged stats, focus or an error, even when the call ended, so it was stuck before playing anything. Calls and Siri now write to the car's audio output without waiting, so a car output that never starts can no longer hang the stream. Music is unchanged.
- The diagnostic log now records each step of every audio stream: decoder ready or failed, audio focus requested, kept or not requested, first sound decoded, playback started, the first write error, and a summary when the stream ends. If a stream has not started playing after 6 seconds, the log names the step it is stuck on.
- AVRCP on SWI165 has no disconnect command. The log now says so instead of reporting `NoSuchMethodException`; AVRCP still drops with the A2DP sink.

# MG4CPlay 0.2.9-mg4.67 (SWI165 fork), 2026-11-01

- Fix the call microphone on Android 9. DiPlay offered Opus for microphone input, so the iPhone chose it for calls, but Android 9 has no Opus encoder and the microphone never started (`Microphone telephony: failed`, codec OPUS, in mg4.66). Opus input is now offered only when the head unit has an Opus encoder; otherwise the iPhone sends plain PCM.
- Confirmed on the car in mg4.66: Wi-Fi music, and steering-wheel next and previous (wheel key codes 5 next, 4 previous, 3 mute, 1 volume up).

# MG4CPlay 0.2.9-mg4.66 (SWI165 fork), 2026-11-01

- Wi-Fi audio: steering-wheel next and previous now control CarPlay. On SWI165 the wheel keys arrive as a SystemUI broadcast handled by the car's MediaService, not as Android media keys, so DiPlay listens for that broadcast while CarPlay runs. Every wheel key is logged; mute and volume stay with the car.
- Wi-Fi audio is now the default on SWI165 (other firmware keeps Bluetooth music). The switch under Audio routing is no longer marked experimental and still turns it off.
- In Wi-Fi audio mode, disconnect the car’s Bluetooth music (A2DP and AVRCP) from the iPhone once CarPlay is up, as factory wireless CarPlay does when the phone asks. Hands-free stays connected and pairing is untouched. This replaces an unused upstream release that switched the whole Bluetooth radio off.
- Wi-Fi audio: fix silent phone calls. With audio over Wi-Fi the iPhone sends calls to CarPlay, and the car did not play call audio from DiPlay. On Android 9 call audio now uses the music stream (which reaches the speakers), and the microphone falls back to the plain mic if the car refuses the call-processing source. The log records the microphone source and call audio route.
- Fix the Bluetooth Music switch on SWI165. Read from the car's own MediaService: its Bluetooth interface has no `requestBtAudioFocus` (DiPlay was sending command 15, which does not exist), and `play` (2) is what switches the car to Bluetooth Music. DiPlay now sends only `play` and logs its result, plus the Bluetooth connection and play state.

# MG4CPlay 0.2.9-mg4.65 (SWI165 fork), 2026-11-01

- Bluetooth Music switch: connect to the car's media service when CarPlay starts instead of at the handoff, keep trying for up to 10 seconds, and record each outcome in the diagnostic report (bind refused, not connected, focus or play rejected). mg4.64 logs showed "never answered" after 3 seconds with no reason.
- If the SAIC media service cannot switch the car to Bluetooth Music, fall back to pressing play on the car's A2DP media browser, the method MG4Dash uses. The log says which path ran and whether that service exists on this firmware.
- Car audio source codes now follow MG4Control's on-car measurements and the SWI68 launcher (6 USB, 14 online, 50 CarPlay, 70 Android Auto), and the log adds the current player type.
- More diagnostics for audio testing: the car's audio source before and after the Bluetooth Music switch (and once in Wi-Fi mode), and the head unit's music and call volumes, mute state and audio mode at session start.
- Update Bouncy Castle 1.79 to 1.86 and JmDNS 3.6.3 to 3.6.5 (patch releases).
- Fix the overlay video renderer and Wi-Fi audio switches not appearing. They had been added to an old settings menu that DiPlay no longer opens. They are now in DiPlay's settings: **Display and performance > Overlay video renderer** and **Audio routing > CarPlay audio over Wi-Fi (experimental)**.

# MG4CPlay 0.2.9-mg4.64 (SWI165 fork), 2026-11-01

- Add an optional **CarPlay audio over Wi-Fi (experimental)** switch under Settings (shown from mg4.65). When on, the iPhone sends music over Wi-Fi and DiPlay plays it, DiPlay takes audio focus for music, and the car is no longer switched to Bluetooth Music. Applies when CarPlay reconnects.
- Off by default on every firmware: the car’s Bluetooth Music (hybrid) path is unchanged.
- The log now records the audio mode, firmware, profile and focus setting once per session.
- Not yet tested on the car. Claiming the car’s CarPlay audio source is still to come if the sound does not reach the speakers.

# MG4CPlay 0.2.9-mg4.63 (SWI165 fork), 2026-11-01

- Fix the "connect Bluetooth for audio" prompt appearing on every connect. The check asked the wrong Bluetooth profile (A2DP source instead of the car's A2DP sink), always found nothing after a 3-second wait, and opened Bluetooth settings over CarPlay. The car now switches to Bluetooth Music when the phone is connected, and only prompts when it is known not to be.

# MG4CPlay 0.2.9-mg4.62 (SWI165 fork), 2026-11-01

- Detect the MG4 firmware generation and apply SWI69 or SWI165 settings; unknown firmware keeps the previous behaviour.
- SWI165: use the `com.saicmotor.hmi.launcher` stock launcher; the CarPlay-card shortcut stays off until that card is measured.
- SWI165: draw CarPlay on the hardware video overlay (SurfaceView), like the stock AllGo CarPlay app, to fix the washed-out picture.
- Add **Settings > Window > Overlay video renderer** to choose the video path on any firmware.
- Build script passes the detected Android SDK to Gradle and writes the signed APK to `dist/`.
- README and docs rewritten for this fork, with Mac build and USB-stick install steps.

# DiPlay 0.2.8 — 2026-09-30

- Keep iPhone location reporting active across the wireless Bluetooth-to-Wi-Fi CarPlay handoff; limit location updates to one per second on wireless and USB.
- Add optional ADB wheel-speed and gear reporting for iPhone dead reckoning when GPS is unavailable. Tunnel use has not yet been verified.
- Add optional iOS 27 video playback on the car screen while parked, with iPhone, touchscreen and steering-wheel controls; close playback when leaving P.
- Explain unsupported DRM-protected video such as Apple TV+, which requires a licensed FairPlay receiver.
- Improve playback error reporting and preserve CarPlay when the head unit cannot play a video.

# DiPlay 0.2.7 — 2026-09-29

- App interface in English, Simplified Chinese, Arabic, Russian and Spanish; synchronized Android app-language settings.
- Steering-wheel media controls and long-press Siri on supported BYD firmware while CarPlay is on screen.
- Dashboard display choices: map, turn card, or both; corrected dashboard keyframe recovery.
- Optional ADB feature on supported DiLink 5.0: pause the dashboard map stream when its display mode hides the map.
- Optional ADB battery reporting for Apple Maps, with warning threshold, charging-connector selection and a checked reconnect action.
- Audio playback reliability fixes and clearer dashboard settings.
- Clarify the BYD-only support scope on the README and all five website editions.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
