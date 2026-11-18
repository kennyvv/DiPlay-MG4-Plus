# Car test checklist: next drive

For build **0.2.9-mg4.96-swi68.13**. Everything here works in the emulator (or is new) but has not been
tried in the car yet. Tick a box when it passes, write a word or two when it doesn't, and download the
report at the end (last section). The codes in brackets are the steps in
[MG4_SWI165_TEST_PLAN.md](MG4_SWI165_TEST_PLAN.md), which has the log lines to look for.

Settings > Developer switches are off by default: turn on only what a step says, then **Reconnect now**.

## 1. Install and start (parked, engine on)

- [ ] Install swi68.13 from the USB stick over the current build; Settings keep your choices (H1)
- [ ] CarPlay connects on its own; note how long from opening DiPlay to the first picture (A1)
- [ ] Music that was already playing on the iPhone is heard straight away, no pause and play needed (M10;
      Settings > Experimental > **Recheck the car's audio source** on)

## 2. Parked: screens, dashboard, launcher

- [ ] Home screen, wait 10 s, back to CarPlay: music never stops, CarPlay is back at once (M2, on by default now)
- [ ] Put it in reverse while CarPlay is on screen: the camera shows; after reverse, CarPlay comes back (M3)
- [ ] Dashboard on the Music page: song card with a soft gradient in the cover's colour, a progress bar and
      "1:23 / 3:45" moving every second; pausing stops it (M8)
- [ ] Dashboard: switch Navigation, Music, Navigation pages during an Apple Maps route: the map is back within
      about a second each time (M9, on by default now)
- [ ] MG4 Launcher: song, artist and cover; play, pause, next and previous work (L1, L2)
- [ ] Launcher shows the iPhone's battery; put the iPhone on the wireless pad: a charging mark appears;
      take it off: it goes (L5)
- [ ] Launcher song position follows when you skip forward in a song on the iPhone (L6)

## 3. Radio and CarPlay audio (Wi-Fi audio)

- [ ] Play CarPlay music, start the radio: only the radio is heard and CarPlay shows paused (M4)
- [ ] Press play in CarPlay: CarPlay music plays and the radio stops (M5)
- [ ] Radio again, then pick CarPlay as the source on the launcher or the car's source button (M6)
- [ ] CarPlay music while the car's own navigation or a chime speaks: music pauses only for the moment (M7)

## 4. Calls (needs someone to call you)

Calls over CarPlay only stays on. Do the first two with all Developer call switches off.

- [ ] Outgoing call, 30 s, count out loud: clear both ways, music comes back after (C1, C6)
- [ ] Incoming call while music plays, answer and end with the wheel phone key (C3, C4, C5)
- [ ] The other person does not hear themselves while you stay quiet (D2j, software echo suppression)
- [ ] Developer > **Ringtone on the car's ring output** on: incoming call rings at ring volume; turning the
      knob while it rings changes ring volume (J9)
- [ ] Developer > **Calls get their own audio focus** on: music stops for the call and comes back after (J8)
- [ ] Parked, quiet: Developer > **Test call echo**; note the two `echo=` numbers in the report (J1)
- [ ] Developer > **Car echo canceller for calls** on: call someone. Either the call is clear with no echo
      (the car's canceller works), or there is up to 2 s of static and then the call carries on normally
      (the car path stalled and DiPlay fell back; the report says `car call path stalled`). Note which (J2)

## 5. Siri

- [ ] Hold the voice button: "What's the time?", then "Play Triple J": understood both times (D2c)
- [ ] Developer > **Car microphone processing for Siri** on: dictate a long sentence; note what Siri wrote (J3)
- [ ] Developer > **Siri own audio focus** on: ask Siri something while music plays; music drops for Siri (J4)
- [ ] Developer > **Siri prewarm on voice button** on: short press and hold both open Siri, no slower (J5)

## 6. Driving

- [ ] Apple Maps route with a few turns: cluster directions and distances match CarPlay (D4)
- [ ] Lane guidance on the dashboard (Experimental), car icons, then Drawn by MG4CPlay: lanes match CarPlay
- [ ] Developer > **Navigation prompts lower music** on, the car's lower-media-for-navigation setting on:
      music dips under each prompt and comes back; the volume knob during a prompt sets prompt volume (J6)
- [ ] Joystick for CarPlay, CarPlay control on: up and down move the highlight the right way (K3)
- [ ] 15 minutes of route plus music: note the time of any freeze (F12)

## 7. At the end

- [ ] Settings > Help > **Download to iPhone** (or Save report to USB). The report also shows whether the
      car's MFi chip works (K1)
- [ ] Send me the report and this list with the ticks
