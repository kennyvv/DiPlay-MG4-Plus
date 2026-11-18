# SWI165 release test plan: calls, music, Bluetooth and Wi-Fi

For checking a build on the car before it's called a release. Written for mg4.84.

## Before you start

| Item | Setting |
|---|---|
| Build | The version shown on DiPlay's About page matches the one you built |
| CarPlay audio over Wi-Fi | On |
| Calls over CarPlay only | On (turn off only for section E) |
| Microphone (test) | Automatic, unless the step says otherwise |
| iPhone | Bluetooth on, paired to the car, Low Power Mode off, on the mount |
| Car | Fully off for a few minutes first, so each run starts from a cold start |

Note the time at each step, so it can be matched to the log. Save one diagnostic report at the
end, wait a few seconds, then copy it off.

## A. Start-up

| # | Step | Pass | Log lines to check |
|---|---|---|---|
| A1 | Start the car, open DiPlay, wait for CarPlay | Picture within about 10 s | `AirPlay session active`, `first frame rendered` |
| A2 | Check what's connected over Bluetooth on the iPhone | Car shows as CarPlay only, not as a Bluetooth audio or phone device | `A2DP sink release ... result=true`, `Hands-free release ... result=true` |
| A3 | Wait 60 s | Still nothing on Bluetooth audio or phone | Any `hands-free reconnected ... releasing again` lines (fine if present: the watcher worked) |

## B. Music

| # | Step | Pass | Log lines |
|---|---|---|---|
| B1 | Play music for 2 minutes | No stutter, no loud clipping | `audio stats ... underruns=0`, `Audio loss ... resendRequests` (resends being sent is good) |
| B2 | Wheel next, previous | Track changes once per press | `Wheel key: code=5/4 ... -> CarPlay` |
| B3 | Wheel volume up and down, then mute | Volume changes; mute silences | Raw codes 297, 298, 301 |
| B4 | Pause on the iPhone, wait 30 s, play | Resumes within 2 s | New `Audio: playback started` |
| B5 | Car's own radio button, then back to CarPlay | Radio stops; CarPlay music comes back | `car source:` lines |

## C. Calls (CarPlay)

| # | Step | Pass | Log lines |
|---|---|---|---|
| C1 | Outgoing call from CarPlay, 30 s, count out loud | Both sides hear each other; no echo; delay under about a second | `audioType=telephony`, `Microphone telephony: started ... opus=software`, `Microphone telephony stats ... encodeAvgUs` under 10000 |
| C2 | Same call: does the iPhone show the car as CarPlay, not Bluetooth? | CarPlay | No `mode=2` in `Audio environment` |
| C3 | Incoming call while music plays | Rings through the car speakers; music pauses | Look for an `alert` or `telephony` stream and whether it played (see risk R1) |
| C4 | Answer with the steering-wheel phone key | Answers | `Wheel raw key: code=291 ... -> CarPlay call` |
| C5 | End with the phone key | Hangs up | Same |
| C6 | Music after the call | Resumes on its own | `audioType=media` restarts |
| C7 | Call with music paused, then start music mid-call | Call keeps priority; music stays quiet | Focus lines |
| C8 | mg4.93, Wi-Fi, fan on medium, windows up: call someone and talk normally for 30 s, then ask whether you sound clear, echoey or noisy. Repeat with Microphone (test) on each option, noting the time of each call | Calls work on every option; note which one the other person hears best | `Microphone telephony: started source=...` (`refused, MIC` means the car's call processing wasn't used), `Microphone telephony stats ... peakDb= rmsDb= clipped=`: speech around -20 to -6 dB with clipped=0 |

## D. Siri and Maps

| # | Step | Pass | Log lines |
|---|---|---|---|
| D1 | Voice button on the wheel: hold it, then a short press (mg4.93) | Holding opens Siri while still held; letting go does nothing more. A short press opens Siri on release. Note whether the car's own voice assistant also starts | `Wheel raw key: code=287 down=true long=true -> Siri sent=true`; for a short press, `down=false long=false -> Siri sent=true` |
| D2 | "What's the weather in Geelong", three times per Microphone (test) option | Siri hears it correctly | `Microphone speechrecognition: started source=...` |
| D2a | n/a since mg4.96 (USB removed). Was USB CarPlay: record a Voice Memo and dictate a message (upstream #1148) | Both record your voice, not silence | `Microphone speechrecognition: started ... codec=LPCM rate=48000 ... frameMs=10` (or another rate with its frameMs) |
| D2b | mg4.93: hold the voice button, speak a normal sentence at normal volume, once per Microphone (test) option | Siri understands; note which option works best | `Microphone speechrecognition stats ... peakDb= rmsDb= clipped=`: speech should peak around -20 to -6 dB with clipped=0 |
| D2c | mg4.97: Microphone (test) on Automatic. Hold the voice button and say "What's the time?", then "Play Triple J", then call a contact | Siri understands and acts each time | `airplay audio format type=100 audioType=speechrecognition ... rtpClock=24000`, `Microphone speechrecognition: started ... tsStep=480` |
| D2d | mg4.98: **Record microphone** on. Call your own voicemail (or a second phone), leave a 10 second message, then listen to it | Your voice is clear in the message | `Microphone telephony: started ... rtpClock= tsStep=` (expect rtpClock=16000 tsStep=320), `Microphone telephony recording saved file=` |
| D2e | mg4.98: **Record microphone** on, **Siri microphone as PCM** off. Parked, music off. Hold the voice button and dictate "Text Andrew: I will be home at half past five and I am bringing dinner". Note what Siri wrote, then cancel | Siri writes the sentence correctly | `Microphone test: siriFormat=Opus recording=on`, `Microphone speechrecognition stats ... floorDb=`, `recording saved` |
| D2f | mg4.98: turn **Siri microphone as PCM** on, tap **Reconnect now**, and repeat D2e. Check music and Siri's voice still play; if CarPlay has no sound, turn it off again | Siri writes the sentence correctly | `Microphone test: siriFormat=PCM`, `Microphone speechrecognition: started ... codec=LPCM` |
| D2g | mg4.98: with the better format, repeat D2e once per **Microphone (test)** option, then once with music playing | Note which option is best | `Microphone speechrecognition: started source=...` |
| D2h | mg4.98: **Download to iPhone**. Open the zip in Files and play a `mic-...-speechrecognition.wav` and a `mic-...-telephony.wav` | Your voice is clear in both, with no gaps, echo or music | Report line `Microphone test: ... recordings=` lists the files |
| D2j | Software echo suppression (new, after 0.3.3): ring a second phone, have the other person say a few sentences while you stay quiet, then talk over each other, then say a sentence yourself while they are quiet. Repeat once with **Echo suppression on calls** off | The other person does not hear themselves when you are quiet. Your voice is complete when you talk over them or after they stop, with no words clipped at the start. With the switch off the echo returns | `Microphone telephony: started source=MIC ... swEcho=on`; stats lines `echo=on lag=` (a value such as 150ms to 500ms once they speak, not `none`) `corr=` above 0.4, `suppressed=` between 20% and 80% |
| D2k | Call path diagnostics (after 0.3.3): make one call with **Calls over CarPlay only** on, then one with it off, each about 30 s. Download the report | The report says which of the two it was and whether any call audio went through the car's Bluetooth hands-free | `Microphone test: ... callsOverCarPlayOnly=`; `car Bluetooth: hands-free kept connected` (off) or `Hands-free release` lines (on); `hands-free call audio (SCO) state=connected` only if the car's phone took the call; `Audio: mode set=2 ... scoOn=`; `Microphone telephony: echo delay found lag=` |
| D2i | Echo cancellation on calls: ring a second phone and have the other person say a few sentences while you talk | The other person does not hear themselves | `Microphone telephony: started source=MIC ... aec=on` (default since the stock-library finding; `unavailable` or `failed` means no canceller). Repeat once with Microphone (test) on Call processing, and once more with the echo canceller result noted, to compare echo and silence |
| D3 | Start an Apple Maps route; wait for a spoken prompt | Prompt is heard; music lowers, then comes back | `audioType=default` or `alert` stream, and whether it played (see risk R1) |
| D4 | Dashboard (Directions on the dashboard on). Drive a route with a few turns | Next turn, distance, street on the cluster. The distance steps (600, 500, 400 ...) with CarPlay's screen and does not count down by odd numbers (537, 512) | `Cluster nav: guidance started`, `Cluster nav: apple=...`, `Cluster nav: distance raw=... phoneText=... units=... shown=...m via phone` |
| D5 | Report location to iPhone (now on by default on MG4). Accept the location prompt once, then drive with Apple Maps open | Maps follows the car; through a tunnel or car park it keeps moving | `wheel speed started`, `first wheel speed`, `gear raw=` lines |
| D5a | mg4.91: same drive as D5 | Maps still follows the car and tunnels | `start-location-information components=[...] intervals={...}`, then `tx=0xfffb` |
| D6 | Select P, R, N and D in turn while parked | Done 2026-11-03: 1 P, 2 R, 3 N, 4 D. mg4.93 sends R and N as such | `MG4 gear raw=2 sent=REVERSE` etc. |
| D7 | Plug in to charge with CarPlay running, then unplug | Apple Maps shows charging while charging, not once it stops | `chargeStatus=` and `charging=` on the MG4 battery line |
| D8 | Switch the headlights on and off (or drive into dusk with auto headlights) | CarPlay turns dark with the headlights and light again without them | `Day/night probe: SYSTEM.screen_day_night_mode=1`, `Dark mode on (car)`, `AirPlay dark mode=dark eventChannelReady=true` |
| D9 | Turn on **MG4 vehicle > Video while parked**, park in P, play a video in a supported iPhone app via CarPlay; then select D | Video plays on the car screen in P; the player closes on leaving P | `video in car allowed=true sent=true` in P, then `allowed=false` on leaving P. Player details only go to logcat, so note what you see |

## E. Bluetooth fall-backs and recovery

| # | Step | Pass | Log lines |
|---|---|---|---|
| E1 | Close DiPlay (home, or swipe away) during music | Car's Bluetooth phone reconnects within about 30 s | `hands-free reconnect requested` |
| E2 | Close DiPlay, then make a call | Call goes over Bluetooth hands-free | n/a |
| E3 | Turn the car off and on again, open DiPlay | CarPlay returns; A1 to A3 pass again | |
| E4 | Calls over CarPlay only off, make a call | Note where it goes (CarPlay or Bluetooth) | `mode=` and stream lines |
| E5 | CarPlay audio over Wi-Fi off (Bluetooth Music mode), play music | Plays over the car's Bluetooth Music | `car source: ... switched`, `source=5 (bluetooth)` |
| E6 | mg4.96: Settings > Connection > iPhone > **Pair another**, with the iPhone's Settings > Bluetooth open | The iPhone appears within 20 s; tapping it shows a code in MG4CPlay and on the iPhone; **Pair** saves it and CarPlay connects without leaving the app. If it falls back to Android settings, note which step | `Pairing: discovery started=`, `found phones=`, `bond requested=`, `pairing request variant=2 handled=true`, `bond result=bonded` (Settings changes section) |

## F. Wi-Fi stress

| # | Step | Pass | Log lines |
|---|---|---|---|
| F1 | Put the iPhone in a pocket or bag for 2 minutes of music | Short skips at most | `Audio loss`: `ap0RxDrop` and `udpRcvbufErrors` stay `+0` (loss in the air, not in the car) |
| F2 | Turn the iPhone's Wi-Fi off for 10 s, then on | CarPlay reconnects by itself within about 30 s | `transport error`, `Reconnecting`, new `AirPlay session active` |
| F3 | During a call, walk 10 m from the car and back | Call carries on over the iPhone, or recovers | Note what happened |
| F4 | Car's own hotspot or Wi-Fi setting changed | n/a: note only | `wireless hotspot backend` line |
| F5 | mg4.87: switch to **Wi-Fi Direct** (car hotspot off, car Wi-Fi on), band **Automatic**, then connect | CarPlay starts; note the band. If it stops with "Wi-Fi Direct is switched off", note it and go back to Car hotspot | `Wi-Fi P2P legacy state=`, `fiveGhzCapable=`, `Wi-Fi P2P legacy plan`, `setChannels`, `Wi-Fi P2P ready mode=LEGACY band=` |
| F6 | If F5 fails or is on 5 GHz and stutters, set band **2.4 GHz only** and repeat | CarPlay starts on 2.4 GHz | `band=2.4 GHz` |
| F7 | Repeat B (music) and C (calls) on Wi-Fi Direct and compare with the car hotspot | Fewer skips and less call static than the hotspot | `Audio loss` lines and `call loss packets=` at hang-up, for both modes |
| F8 | mg4.86 log: with the car hotspot, video and music froze for 1 to 5 s about every 160 s (Android 9's longest gap between Wi-Fi scans while not joined to a network). Car hotspot on, **car Wi-Fi off**, play music with the map showing for at least 10 minutes | No freezes every 160 s | `Receive: video ... interArrivalMaxMs=` stays under 1000 and `audio stats ... underruns=0`; no `Audio loss` bursts with `udpRcvbufErrors=+` about 160 s apart |
| F9 | Repeat F8 with the car Wi-Fi **on** (not joined to a network), for comparison | Note whether the 160 s freezes return | Same lines as F8; note the times of each freeze |
| F10 | mg4.92: 10 minutes or more of music with the map showing, car hotspot, **Wi-Fi high performance** on | Note whether the hiccups line up with scans | `Wi-Fi scan done ... sinceLast=` times against `Audio loss` lines with `udpRcvbufErrors=+` and `interArrivalMaxMs=` over 1000. mg4.91 (2026-11-03): the lock did not stop the ~160 s hiccups |
| F11 | HEVC on, **Colour range (test)** set to **Limited**; compare with H.264 | HEVC no longer washed out | `Video: decoder=...HEVC ... colourRangeHint=limited` and the `output format ... color=` line (H.264 logs `86/1/2`, HEVC `1/1/2` with the default). mg4.92 (2026-11-03): still washed out with Limited |
| F12 | mg4.96: drive 15 minutes with an Apple Maps route and music; note the time of each freeze. Optionally set **Music buffer** to 2000 ms | Each freeze has a `stall=[...]` on a `Receive:` line | Whether `ap0RxPackets` stops while `ap0TxPackets` keeps rising, the `wireless=` signal, `load=` and `gcBlocking=` |

## G. Getting the report

| # | Step | Pass | Log lines |
|---|---|---|---|
| G1 | mg4.88: with wireless CarPlay connected, open MG4CPlay > Settings > Diagnostics, tap **Download to iPhone**, scan the QR code with the iPhone camera and open the link | Safari downloads `MG4CPlay-<time>.txt`; the dialog says it was sent; the file opens in Files and can be AirDropped to the Mac | `Report download served bytes= ms=` |
| G2 | Open the same link again, or wait 5 minutes without scanning | The link no longer loads; after 5 minutes the dialog says the link expired | `Report download expired` |
| G3 | Disconnect CarPlay and open Diagnostics | **Download to iPhone** is greyed out with a short explanation | n/a |

## H. Settings screen

Not yet tried in the car. It applies from the first build with the two-pane Settings screen.

| # | Step | Pass | Log lines |
|---|---|---|---|
| H1 | Install over the last build, open MG4CPlay > Settings | Connection type, iPhone, hotspot details, display, sound and location choices all keep their values | n/a |
| H2 | From Home, open each category (Connection, Display, Sound, Car, Apps, General, Help, About) | Each is reachable in two taps; nothing is clipped at 1920x720 and the list scrolls | n/a |
| H3 | Connected, change **Frame rate** (a Reconnect row) | A **Reconnect** tag shows on the row and the bar appears. **Reconnect now** reconnects, the bar goes, and the new rate is in use | report lines like `FRAME_RATE = 30` and `Reconnect now after FRAME_RATE` |
| H4 | Connected, change **Dark mode at sunset**, **Car battery on iPhone** or **Video while parked** | Takes effect with no reconnect and no bar | Settings changes list |
| H5 | Read Settings in daylight and at night | Text and rows are readable in both | n/a |
| H6 | Save the report | The **Settings changes (newest last)** section lists the changes from H3 and H4 | `--- Settings changes (newest last) ---` |

## J. Developer tests (DiPlay-MG4 Plus)

Settings > Developer. Turn on only the switch for the step, then reconnect CarPlay. Tried in the emulator, not yet in the car.

| # | Step | Pass | Log lines |
|---|---|---|---|
| J1 | Parked, cabin quiet, CarPlay closed or idle: **Test call echo** | Two result lines. With a working canceller the car path's `echo=` is much lower than the normal path's (aim: 20 dB less) | `Call echo test: path=normal ...`, `path=car ...`, `car path echo ... less echo` |
| J2 | **Car echo canceller for calls** on. Call your voicemail, speak for 10 s, then play back the message. If you can, call a person and talk over each other | You are clear on the voicemail; the other person doesn't hear themselves | `Audio: ready audioType=telephony route=car phone output`, `Microphone telephony: started source=car phone input`. Silent call or `gave no audio`: turn it off and note it |
| J3 | **Car microphone processing for Siri** on. Hold the voice button and dictate a long sentence, with music playing and the fan up | Siri writes it correctly | `Microphone speechrecognition: started source=car Siri input` |
| J4 | **Siri own audio focus** on. Ask Siri a question while music plays | Music drops or pauses while Siri talks, and comes back | `Audio: Siri focus requested ... granted=1`, `Audio: Siri focus released` |
| J5 | **Siri prewarm** on. Short press, then hold, the voice button | Siri opens at least as fast as before | `Wheel raw key: code=287 down=true -> Siri prewarm sent=true` |
| J6 | **Navigation prompts lower music** on, the car's lower-media-for-navigation setting on. Start an Apple Maps route with music playing; turn the volume knob during a prompt | Music dips under each prompt and comes back; the knob changes the next prompt's volume, not music | `Nav duck: on focus=1 source52=true setNaviDucking=ok`, `Nav duck: off (released)` |

## K. Car MFi chip and joystick direction (DiPlay-MG4 Plus)

| # | Step | Pass | Log lines |
|---|---|---|---|
| K1 | Any drive: download the report | The report says whether the car's MFi chip answered | `Car MFi chip:` header line; `probe result ...` in the session log |
| K2 | Only if K1 shows the chip working: Settings > Experimental > **Use car's MFi chip (test)** on, reconnect CarPlay | CarPlay connects as before; Siri and a call work | `Car MFi chip: in use for this session`. If it can't be used: `not used, ...; using the bundled identity`. Turn it off if CarPlay won't connect |
| K3 | Steering-wheel joystick on, CarPlay control on (hold centre). Push up, then down, on a list | Up moves the highlight up. If not, flip **Joystick up and down** and try again | `Wheel:` lines |

## L. MG4 Launcher bridge (DiPlay-MG4 Plus)

Needs the MG4 Launcher (`nl.mg4launcher`) installed, signed with the same platform key.

| # | Step | Pass | Log lines |
|---|---|---|---|
| L1 | Wireless CarPlay, play music | The launcher's media card shows the song, artist and cover, and follows track changes | `DiPlay-Launcher: launcher found: sending the CarPlay song` |
| L2 | Press play/pause, next, previous on the launcher's media card | CarPlay follows | `launcher button PLAY sent=true` (etc.) |
| L3 | Wi-Fi audio mode: play the radio from the launcher, then pick CarPlay as the source | CarPlay music comes back | `launcher take_focus asked=true audio=wifi` |
| L4 | Wi-Fi audio mode, connected | The launcher's status bar shows the iPhone's name | none (launcher side) |
| L5 | Connected, iPhone on the wireless pad, then off it | The launcher shows the iPhone's battery and a charging mark that goes away. If the log has no `iPhone power:` line at all, the iPhone doesn't send its battery over wireless CarPlay | `iPhone power: battery=..% charging=charging charger=true` |
| L6 | Play a song, skip forward in it on the iPhone | The launcher's position follows | none (launcher side) |

## M. CarPlay screen and audio hand-over (DiPlay-MG4 Plus)

Settings > Experimental. The `modesChanged` lines come with both switches off too; they show what the iPhone says it holds.

| # | Step | Pass | Log lines |
|---|---|---|---|
| M1 | Both switches off. Connect, play music, start Apple Maps directions, ask Siri something, make a short call | One `CarPlay modes: iPhone reports` line for each change. Siri shows `speech=iPhone(speaking)` or `(listening)`, directions `turnByTurn=iPhone`, the call `phoneCall=iPhone` | `CarPlay modes: iPhone reports screen=... mainAudio=... raw=[...]` |
| M2 | **Tell iPhone when CarPlay is hidden** on. Leave CarPlay for the car's home screen for 10 s, then go back | CarPlay is back at once with a sharp picture, no black screen or reconnect | `CarPlay modes: CarPlay hidden, the car takes the screen`, then `sent screen take ... ok=true`, `iPhone reports screen=car`; on return `CarPlay shown`, `sent screen untake`, `screen=iPhone` |
| M3 | M2 on. Engage reverse while CarPlay is on screen, then drive | The camera shows; afterwards CarPlay comes back by itself or with one tap | as M2 |
| M4 | **Hand CarPlay audio to the car's sources** on, Wi-Fi audio. Play CarPlay music, then start the radio | The iPhone pauses (CarPlay's Now Playing shows paused); only the radio is heard | `focus lost, the car takes the sound`, `sent mainAudio take`, `iPhone reports ... mainAudio=car` |
| M5 | After M4, press play in CarPlay | CarPlay music plays and the radio stops | `iPhone reports ... mainAudio=iPhone`, `the iPhone took the sound back, car audio focus asked again=true` |
| M6 | After M4, pick CarPlay as the source on the launcher (or the car's source button) | The car switches back to CarPlay; CarPlay may need a press of play | `focus back, the sound goes back to the iPhone`, `sent mainAudio untake` |
| M7 | M4 on. Play CarPlay music, let the car's own navigation or a car chime speak | CarPlay music pauses only for the moment and carries on | `the car borrows the sound`, then `unborrow` |
| M8 | Dashboard song card on, play a song | Under the title a thin bar and "1:23 / 3:45" that move on every second; pausing stops them. With a cover, the card's background fades from a dark tone of the cover's colour at the top to black | none |
| M10 | **Recheck the car's audio source** on. With music playing on the iPhone, start the car (or connect CarPlay) several times | Music is heard at every connect without pausing. If one is silent, note the time and save the report | `Car source: com.shihab.diplay.hudtest ...` at each connect; any `plays instead of CarPlay music` line shows the case this covers |
| M9 | Map on the dashboard and **Pause the dashboard map off its page** on. Navigate with Apple Maps, switch the cluster to its Music page for a minute, then back to Navigation | The map is back on the Navigation page within about a second and follows the route | `Cluster map: stopUI reason=cluster page`, no `stream=111` stats while paused, then `Cluster map: showUI` and stats again |

## Known risks to check in code before release

| # | Risk | Why | What to check |
|---|---|---|---|
| R1 | Ringtone and Maps prompts may be silent | They use the navigation channel, which plays as `USAGE_*` audio, not the music stream. Calls were silent on this firmware until they were moved to the music stream | C3 and D3. If silent, route `alert` and `default` to the music stream in Wi-Fi mode, as calls already are |
| R2 | Hands-free given back on every DiPlay close | Leaving CarPlay closes the session (`Activity destroyed`) and reconnects hands-free, then the next session releases it again | E1. If it flaps, keep the session alive in the background instead |
| R3 | No call audio if Wi-Fi drops mid-call | Hands-free is released, so the iPhone has nowhere to send the call except its own earpiece | F3. The iPhone should fall back to its earpiece; confirm |
| R4 | Call and Siri streams can't ask for resends | The iPhone doesn't send a control port for these streams | mg4.84 hides call gaps up to 200 ms (FEC, then concealment); check `call loss` at hang-up |
| R5 | Bluetooth music doesn't come back after CarPlay | A2DP is released and not reconnected on close | E1: check whether Bluetooth music needs a manual reconnect |
| R6 | Siri mishears | Microphone source or processing | D2 results per option |
| R7 | Calls sound noisy or echoey | Automatic now uses the plain microphone for calls, as the car's own CarPlay library does, so any processing comes from the car's mic path or the echo canceller DiPlay attaches. Call processing (`VOICE_COMMUNICATION`) is a test option | C8 results per option |

## What to send back

- The diagnostic report.
- The times of each step, with anything that failed.
- For D2: what Siri heard each time, for each Microphone (test) option.
- For C8: how the other person heard you on each Microphone (test) option.
