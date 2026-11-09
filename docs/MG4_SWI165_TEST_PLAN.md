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
| D2d | mg4.97: make a phone call and ask the other person if you sound the same, clearer or worse than before | Same or clearer | `Microphone telephony: started ... rtpClock= tsStep=` (expect rtpClock=16000 tsStep=320) |
| D3 | Start an Apple Maps route; wait for a spoken prompt | Prompt is heard; music lowers, then comes back | `audioType=default` or `alert` stream, and whether it played (see risk R1) |
| D4 | Dashboard (Directions on the dashboard on) | Next turn, distance, street on the cluster | `Cluster nav: guidance started`, `Cluster nav: apple=...` |
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

## Known risks to check in code before release

| # | Risk | Why | What to check |
|---|---|---|---|
| R1 | Ringtone and Maps prompts may be silent | They use the navigation channel, which plays as `USAGE_*` audio, not the music stream. Calls were silent on this firmware until they were moved to the music stream | C3 and D3. If silent, route `alert` and `default` to the music stream in Wi-Fi mode, as calls already are |
| R2 | Hands-free given back on every DiPlay close | Leaving CarPlay closes the session (`Activity destroyed`) and reconnects hands-free, then the next session releases it again | E1. If it flaps, keep the session alive in the background instead |
| R3 | No call audio if Wi-Fi drops mid-call | Hands-free is released, so the iPhone has nowhere to send the call except its own earpiece | F3. The iPhone should fall back to its earpiece; confirm |
| R4 | Call and Siri streams can't ask for resends | The iPhone doesn't send a control port for these streams | mg4.84 hides call gaps up to 200 ms (FEC, then concealment); check `call loss` at hang-up |
| R5 | Bluetooth music doesn't come back after CarPlay | A2DP is released and not reconnected on close | E1: check whether Bluetooth music needs a manual reconnect |
| R6 | Siri mishears | Microphone source or processing | D2 results per option |
| R7 | Calls sound noisy or echoey | DiPlay adds no noise or echo processing of its own; that comes from the car when the call source (`VOICE_COMMUNICATION`) is used. If the car refuses it, or does no processing for it, the call uses the raw microphone | C8 results per option |

## What to send back

- The diagnostic report.
- The times of each step, with anything that failed.
- For D2: what Siri heard each time, for each Microphone (test) option.
- For C8: how the other person heard you on each Microphone (test) option.
