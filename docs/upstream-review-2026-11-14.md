# Upstream review, 2026-11-14

What the upstream projects changed since DiPlay-MG4 Plus forked, and what that means for four
problems seen in the car. Nothing has been merged or cherry-picked yet.

## Upstreams checked

Three remotes were added to this checkout and fetched (no tags, nothing pushed):

| Remote | Repository | Ahead of us | We are ahead |
|---|---|---|---|
| `upstream-andrew` | [andrew-snape/DiPlay-MG4](https://github.com/andrew-snape/DiPlay-MG4) (SWI165 fork, our direct parent) | 73 commits since our base `47b435a` (mg4.96), up to 0.4.0 on 2026-11-13 | 14 (`cb5bc3d`..`b2b3045`, the SWI68 and cluster work) |
| `upstream-fatih` | [fatihdonmezdev/DiPlay-MG4](https://github.com/fatihdonmezdev/DiPlay-MG4) | 1 | 137 |
| `upstream-shihab` | [shihabal3amri/DiPlay](https://github.com/shihabal3amri/DiPlay) (original DiPlay) | 1506 on `main` (914 not merges, last 2026-10-28), plus many fix branches | 199 |

Our tree split from shihab's DiPlay at 0.2.8 (2026-09-30), through Fatih's fork. Most of
shihab's October work never reached Fatih's or Andrew's fork, and their code has moved apart a lot (our
`media/` package is about 3,000 lines; shihab's is much larger). His commits are ideas to port, not
patches that apply cleanly.

Links: Andrew `https://github.com/andrew-snape/DiPlay-MG4/commit/<hash>`, shihab
`https://github.com/shihabal3amri/DiPlay/commit/<hash>`.

## Andrew's changes since mg4.96, by area

Almost all of it is marked "not yet tried in the car" in his changelog.

| Area | Commit | What it does |
|---|---|---|
| Microphone, Siri | [`ab489ba2`](https://github.com/andrew-snape/DiPlay-MG4/commit/ab489ba2) (mg4.97) | Mic RTP timestamps count at the negotiated clock (24 kHz Siri, 16 kHz calls), not 48 kHz. See issue 2 |
| Microphone, Siri | [`3354013a`](https://github.com/andrew-snape/DiPlay-MG4/commit/3354013a) (mg4.98) | Record microphone (test): WAVs of the first 10 s of each Siri request and call. Siri microphone as PCM (test). `floorDb=` in mic stats |
| Calls, echo | [`d9748480`](https://github.com/andrew-snape/DiPlay-MG4/commit/d9748480) (0.3.1) | Attaches Android's echo canceller to the call mic. The car then logged `aec=unavailable` |
| Calls, mic | [`a4c3f1e1`](https://github.com/andrew-snape/DiPlay-MG4/commit/a4c3f1e1) (0.3.3) | Calls use `AudioSource.MIC`, as the stock CarPlay library does, not `VOICE_COMMUNICATION` |
| Calls, echo | [`87494d19`](https://github.com/andrew-snape/DiPlay-MG4/commit/87494d19), [`09ee4e04`](https://github.com/andrew-snape/DiPlay-MG4/commit/09ee4e04) (0.4.0) | Software echo suppression (learns the echo delay, turns the mic down while only echo is present). Call path logs (hands-free kept or released, SCO state, echo delay) |
| Calls, Siri, audio | [`56454484`](https://github.com/andrew-snape/DiPlay-MG4/commit/56454484) (0.4.0) | Ports from the stock AllGo app, each off or log only: calls listed in the car's Telecom, car phone mode (`setDeviceConnectionState(0x400, "carplay_source=phone")`), Microphone (stock), music tagged as car source 50, vendor call listener, phone focus retry, mute key during Siri |
| Calls | [`bfa103a1`](https://github.com/andrew-snape/DiPlay-MG4/commit/bfa103a1) (0.4.0) | Narrow-band calls (8 kHz, test). Screen size reported to iPhone (Automatic or stock 244 x 80 mm) |
| Authentication | [`7d9f4649`](https://github.com/andrew-snape/DiPlay-MG4/commit/7d9f4649) (0.4.0) | Probe of the car's own MFi chip, and a test option to sign with it |
| Cluster | [`61b72d36`](https://github.com/andrew-snape/DiPlay-MG4/commit/61b72d36) (0.3.2) | Turn distance follows CarPlay's display value (600 m, not 537 m) |
| Settings | [`416395b5`](https://github.com/andrew-snape/DiPlay-MG4/commit/416395b5) | Settings screen hides the car's side bar |
| Car data | [`1811c816`](https://github.com/andrew-snape/DiPlay-MG4/commit/1811c816) | Removes EVHardware and ABRP and reads the car directly, **SWI165 only**. Clashes with our SWI68 support (`cb5bc3d`): don't take it as it is |
| Other | `51228408`, `5000c236`, `5ab9d442`, `51c9ef47` | English only, `MAJOR.MINOR.PATCH` versions (0.3.0 on), CI rejects iCloud `* 2.*` files, gear codes in one place |
| Connection | none | Nothing on start-up time |

A straight merge of `upstream-andrew/main` conflicts in 11 files (README, CarPlayHostActivity,
Mg4SettingsPage, SettingsModel and its test, strings, build files, `settings.gradle.kts`,
CarPlayController, VehicleIntegration), mostly over EVHardware and versioning.

## shihab's DiPlay, relevant to the four issues

| Area | Commits | What they do |
|---|---|---|
| Mic clock | `3868eea3`, `6dbde48f`, `79eeec9d` | Same fix as Andrew's `ab489ba2`: mic RTP timestamps in the clock the iPhone chose, and logged |
| Mic reliability | `9647d689`, `ddfb3fd2`, branch `fix/1214-microphone-recorder-retry` | Restart a mic that returns no audio; recover the call mic after a failed read |
| Mic packets | `8d5fd4b2` | PCM mic packets fit one MTU (already ported as #1148) |
| Call echo | `f19a101a`, `019d5bc1`, `806bbf6f` | Echo canceller with a contiguous call reference, voice filter (opt-in) |
| Call echo | `8a1f35de` | Holds a Telecom call so the car's phone audio state handles echo (Andrew's "Show calls to the car" is the same idea) |
| Navigation focus | `ef485eb4`, branch `feat/navigation-audio-focus` | Opt-in: navigation prompts ask for `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` between the iPhone's `duckAudio` and `unduckAudio`, released 1 s after unduck (30 s if no unduck) |
| Navigation volume | `49432183`, `db3d3bd2`, `6648f6dc` | Wheel volume to the navigation stream; a navigation-only attenuation slider |
| Start-up | `88e847ec` | No reverse DNS in Bonjour (already ported as #972) |
| Start-up | `37f06926`, `118ad7b6`, `ccb4a30d`, `7b6f4585`, `f2dab427` | Bonjour discovery fixes, recover when no AirPlay TCP follows StartSession, wait for a stable hotspot, alternate address family after first-TCP timeouts, IPv6-only mDNS fix. Mostly reliability, not speed |
| Start-up | `fe1a59db` | Open DiPlay when the chosen iPhone reconnects over Bluetooth |

## The four issues

### 1. No echo cancellation on calls

**Cause.** Our calls record from `VOICE_COMMUNICATION` with no canceller attached
(`MicrophoneUplink.kt:74`), and play the call as a Java `AudioTrack` with
`USAGE_VOICE_COMMUNICATION`, car source 53, in `MODE_IN_CALL` (`AndroidMediaSink.kt:1039`). This head
unit has no Android echo canceller (`aec=unavailable` in Andrew's 0.3.3 car log), so nothing removes
the echo.

The head unit does have one, in its audio HAL, but only on the stock CarPlay call path:

- The firmware's audio policy (`vendor/etc/audio_policy_configuration.xml`) has a direct output
  `carplay_phone` (`AUDIO_OUTPUT_FLAG_DIRECT`, 8/16/24 kHz mono) to the device `Phone Direct Out`
  (`AUDIO_DEVICE_OUT_HDMI` = 0x400), and an input `Carplay Phone input` (`AUDIO_INPUT_FLAG_CARPLAY_PHONE`).
- In the HAL, audio on that output goes through `AudioALSACarPlayPhone` into
  `AudioALSACaptureHandlerECNR` (Nuance `libECNR`), which uses the call audio as the echo reference
  for the mic (headunit-emulator `docs/audio.md` §1.2).
- The car's `AudioPolicyManager::getOutputForAttr` has a vendor branch ("for CarPlay Phone"): when the
  requested output flags include `DIRECT` (bit 0), it forces device 0x400
  (`work/audio-re/routing/carplay/apm_getOutputForAttr.ann`, 0x1f90e).
- The stock `libCarPlayPlatformAudio.so` does exactly that: `setDeviceConnectionState(0x400, AVAILABLE,
  "", "carplay_source=phone")`, then a native `android::AudioTrack` on `STREAM_VOICE_CALL` with flags
  `AUDIO_OUTPUT_FLAG_DIRECT` (0x2720 in the disassembly), and an `AudioRecord` with a vendor input flag
  (0x200 for calls, 0x100 for Siri).

Java can't ask for a direct PCM output or set vendor input flags on Android 9, so DiPlay's call audio
can never reach the canceller.

**Upstream.** Andrew's software suppression (`87494d19`) is a half-duplex workaround: it mutes the mic
while only echo comes in, so talking over each other is clipped. His car phone mode (`56454484`) sends
the same `carplay_source=phone` message, but with a Java track that is not `DIRECT`, so the policy
branch above never fires. Neither is tested in the car.

**Fix to write.** A small native library (JNI, MG4 only, behind a test switch) that does what the stock
library does: connect device 0x400 with `carplay_source=phone`, play the call downlink through
`android::AudioTrack(STREAM_VOICE_CALL, 16 kHz, mono, PCM16, AUDIO_OUTPUT_FLAG_DIRECT)`, and record the
call mic through `android::AudioRecord(MIC, flags 0x200)`; disconnect on hang-up. These are private
C++ classes, so the library must be built against the Android 9 headers and `dlopen` the car's
`libaudioclient.so`/`libmedia.so`. That's fragile in general but fine on a fixed firmware that we
detect first. Keep Andrew's software suppression as the fallback when the native path fails. The
emulator runs the real `audioserver` and HAL, so `dumpsys media.audio_policy` can show whether the call
lands on `Phone Direct Out` before a car test (audio.md says the call is heard only once the
emulator's capture path, phase 2, works).

### 2. Microphone stutters, Siri hears half of what you say

**Cause, found.** `MicrophoneUplink.kt:261` advances the RTP timestamp by `config.samplesPerPacket`,
which is 960 for Opus (`MicrophonePacketizer.kt:18`). Capture is at 48 kHz, but the iPhone counts mic
timestamps at the negotiated rate: 24 kHz for Siri, 16 kHz for calls. Each 20 ms packet therefore
claims 40 ms (Siri) or 60 ms (calls), and the iPhone hears speech with gaps, which matches "Siri gets
half of it". Playback already uses the negotiated clock (`AudioStream.kt:378`, upstream #644); the mic
never did.

**Upstream fix.** Andrew [`ab489ba2`](https://github.com/andrew-snape/DiPlay-MG4/commit/ab489ba2) adds
`rtpClockRate` to `MicrophoneConfig` and steps by `rtpClockRate * frameMillis / 1000` (480 for Siri,
320 for calls), with tests and log fields (`rtpClock=`, `tsStep=`). shihab fixed the same thing
independently (`3868eea3`, `6dbde48f`). The code part applies to our tree without conflicts; only
`CHANGELOG.md`, `mobile/build.gradle.kts` and `scripts/build_mg4.sh` conflict.

Also worth taking: Andrew's `3354013a` (record the mic, to prove the fix in the car) and his
`a4c3f1e1` (plain `MIC` for calls, as stock does).

### 3. Navigation prompts and Siri not recognised by the car

How the car works (headunit-emulator `docs/vehicle-signals.md` gid 0x03): CarService's
`CarAudioControl` tells the DSP which source to play on which channel (`03 00 00 CH SRC`) **when an app
gains audio focus**, picking the source from the focus request's usage: usage 12 (navigation) → source
5 on CH3 NAVI, usage 16 (assistant) → source 13. Media ducking is `03 00 13` from
`CarAudioService.onSetAttenuation`, also driven by focus. The navigation volume is the DSP volume of
CH3. So the car only treats audio as navigation or assistant when there is a focus request with that
usage.

**Causes found.**

- Navigation prompts never ask for focus. `AudioFocusCoordinator.acquire` returns early for
  `NAVIGATION`, and `requestAudioFocus` skips it on purpose ("overlays without ducking",
  `AndroidMediaSink.kt:60-75`, `:1094`). So the car doesn't duck music and doesn't open its navigation
  source for CarPlay.
- Siri usually never gets its own focus request either. The coordinator keeps one request for the
  highest-priority active track, and `MEDIA` (3) outranks `ASSISTANT` (1)
  (`AndroidMediaSink.kt:127-131`). While the CarPlay music track is open, a Siri track only logs
  `focus kept channel=MEDIA`, so the car still sees media focus.
- We ignore the iPhone's `duckAudio` and `unduckAudio` messages (no handler in `AirPlaySession.kt`).
  These mark the start and end of each prompt.
- The stock app also adds car source 52 to navigation attributes (Andrew's AllGo notes); we tag only
  calls (53).

Not verified: whether our prompt tracks reach bus1 at all (the emulator wasn't running, so no
`dumpsys` was taken). A saved Navigation channel other than 0 would switch them to a legacy stream.

**Upstream.** shihab `ef485eb4` (opt-in focus between duck and unduck) is the closest. Andrew has
nothing for this.

**Fix to write** (MG4 only, Wi-Fi audio):

1. Handle `duckAudio`/`unduckAudio` in `AirPlaySession` and pass them to the sink (port the shape of
   `ef485eb4`).
2. On duck, request `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`,
   tagged car source 52, as a separate request from the media one. Release it about 1 s after unduck,
   or 30 s after the last duck.
3. Give Siri its own `GAIN_TRANSIENT` request with `USAGE_ASSISTANT` while the Siri track plays (the
   stock app uses `GAIN_TRANSIENT` for both), instead of folding it into the media request.
4. Make sure focus loss doesn't pause our media track: the listener already only changes volume.
5. Log each request, the result and the focus changes, and check in the emulator that `03 00 00 03 05
   00` and `03 00 13` appear on the bus when a prompt plays. Then test the car's navigation volume knob.

### 4. Slow CarPlay start-up

**Upstream.** Nothing in Andrew's fork. shihab's start-up commits are mostly about reliability. The
speed one (#972, no reverse DNS) is already in our tree.

**What the code shows** (not measured in the car):

- The head unit does a full Android cold boot on every drive (headunit-emulator
  `docs/startup-latency.md` §3, about 20-30 s to the home screen), and our `BootReceiver` only launches
  CarPlay after `BOOT_COMPLETED`, and only if **Start on boot** is on (off by default).
- `DiPlayActivity` waits a fixed 1.5 s (`HOTSPOT_START_DELAY_MS`) before connecting on every launch
  (`DiPlayActivity.kt:165`, `:192`).
- Bonjour retries its probe after 1.5 s (`CarPlayBonjour.kt:590`).

**To do.** Get one timed log from the car: our `Receive:`/session lines already carry times. Add one
line per stage (Bluetooth RFCOMM up, iAP2 identified, Wi-Fi details sent, iPhone joined the hotspot,
Bonjour found, AirPlay setup, first video frame) so the slow stage is visible. Likely quick wins after
that: drop the fixed 1.5 s when the hotspot is already up, and start the Bluetooth part as soon as the
iPhone's Bluetooth connects (shihab `fe1a59db` does this).

## Recommendation

1. **Cherry-pick now** (fix the changelog and version conflicts by hand):
   `ab489ba2` (Siri mic clock, issue 2), `3354013a` (mic recording, to prove it), `a4c3f1e1` (plain mic
   for calls), `61b72d36` (cluster distance). Then `87494d19` + `09ee4e04` (software echo suppression
   and call logs) as a stop-gap for issue 1; these need hand-merging in settings and
   `MicrophoneUplink`.
2. **Don't merge Andrew's `main` as a whole yet.** `1811c816` makes car data SWI165 only, which undoes
   our SWI68 work, and the merge conflicts in 11 files. Take the AllGo ports (`56454484`) later, one
   switch at a time, if needed.
3. **Write ourselves:** the native stock call path for real echo cancellation (issue 1), navigation and
   Siri focus (issue 3), and stage timings for start-up (issue 4). Each gets a test plan step.

## Follow-up, same day (branch `upstream-picks`)

- **Cherry-picked from Andrew:** `ab489ba2`, `3354013a`, `d9748480`, `a4c3f1e1`, `36c8551c`, `61b72d36`, `87494d19`, `09ee4e04`. In the emulator Siri's microphone then ran at `rtpClock=24000 tsStep=480` and Siri understood the user.
- **Built, behind switches in Settings > Developer** (see CHANGELOG and test plan section J):
  - car echo canceller path for calls (native, as stock), plus a **Test call echo** button;
  - Siri through the car's Siri microphone path (flag 0x100);
  - Siri's own audio focus;
  - Siri prewarm;
  - navigation ducking (`setNaviDucking` + focus tagged 52).
- **Emulator results:**
  - Calls reach "Phone Direct Out" and "Carplay Phone input" and the HAL starts Nuance ECNR. How much echo it removes can't be judged there: the canceller's reference is the car DSP's speaker loopback (TDM-in channel 2), which the emulator only imitates, and the PC round trip is about 440 ms. Needs J1/J2 in the car.
  - Siri went through the car's Siri processing and was understood every time, and the car opened its voice-assistant channel for it.
  - Music dipped under Apple Maps prompts and came back.
- **More stock AllGo findings** (in the shared notes, `allgo-stock-review.md`), not built yet:
  - ringtone on the car's ring output (usage 6, `MODE_RINGTONE`);
  - telling the iPhone when the car takes Siri's audio;
  - offering stock's audio formats and 16 ms latencies;
  - starting on the iPhone's Bluetooth connect (start-up, left alone for now).
- **Car's own MFi chip:** Andrew's `7d9f4649` (probe plus "Use car's MFi chip (test)") is not picked yet. It can only be tested in the car; the emulator has no chip.
