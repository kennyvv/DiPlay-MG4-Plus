# How the car's own CarPlay handles calls (SWI68)

Read from the SWI68-29958-1300R59 firmware on 2026-11-18: the stock AllGo app (jadx) and
`libCarPlayPlatformAudio.so`, `libAirPlay.so` and the audio HAL (disassembly). Builds on
[MG4_SWI165_ALLGO_NOTES.md](MG4_SWI165_ALLGO_NOTES.md) and the shared-notes review
`allgo-stock-review.md`. Nothing here is a car test; "DiPlay" means `swi68` at `31cbf3cb`.

## Side by side

| | Stock CarPlay | DiPlay Plus |
|---|---|---|
| Call audio format offered to the iPhone | PCM only: 8, 16, 24 kHz mono (8 kHz only with `persist.sys.carplay.narrow_band`); 16 ms in and out latency | Opus and PCM; the iPhone picks Opus 48 kHz. Latencies reported as 0 |
| Call downlink | Native `AudioTrack`, stream 0 `VOICE_CALL`, flag `DIRECT`, at the negotiated rate, 512-frame buffer, **callback (pull) mode**, after `setDeviceConnectionState(0x400, …, "carplay_source=phone")`. Lands on "Phone Direct Out", played by the HAL's Nuance ECNR capture handler | Default: Java `AudioTrack`, `USAGE_VOICE_COMMUNICATION` tagged car source 53, 48 kHz. With **Car echo canceller for calls (test)**: the same direct output as stock, but Opus is decoded, halved to 24 kHz and **pushed**; since `31cbf3cb` written without blocking, and after 0.5 s without progress the call moves to the default track |
| Call microphone | `AudioRecord` source MIC, input flag `0x200` (Carplay Phone input, ECNR), at the negotiated rate; started **on its own thread at the same time** as the downlink | Default: plain MIC, Android AEC if any (none on this car), software echo suppression. Test switch: MIC with flag `0x200` at 24 kHz, falls back to plain MIC if silent for 1.5 s |
| Audio focus | Its own phone request: `GAIN_TRANSIENT`, usage 2, tag 53, `MODE_IN_CALL`; if refused, asks again after 200 ms; on release abandons and sets `MODE_NORMAL` | One shared request for all CarPlay audio. Music ranks above the phone, so while a music stream exists the request stays a **music** request during the call. `MODE_IN_CALL` is set while a call track plays |
| Ringtone (`alert`) | Stream 2 `RING`, focus usage 6 `NOTIFICATION_RINGTONE`, `MODE_RINGTONE` while it plays: ring bus, ring volume, DSP phone source | Mapped to the navigation channel: plays on the mono navigation bus at navigation volume |
| Calls in the car's own phone system | Self-managed Telecom `PhoneAccount` "CarPlayPhone"; each CarPlay call is mirrored (`addNewIncomingCall` with `CALL_EXTERNAL_RINGER=true`, `addNewUnknownCall` for outgoing), so the car's Telecom, BTCall screen and audio policy see a call; answering or hanging up there presses CarPlay's hook switch | None. Andrew's "Show calls to the car (test)" (0.4.0, `56454484`) was not ported |
| The car's own Bluetooth calls | Vendor `PhoneStateListener.onCallStatusChanged` (mask `0x60000`, type 0 Bluetooth, 2 CarPlay): gives CarPlay audio up during a native call and takes it back after | No listener (also in `56454484`, not ported) |
| Bluetooth hands-free | Left connected; only checked to see whether a native call is on the same iPhone | Released when **Calls over CarPlay only** is on (the default on MG4) |
| Microphone mute from the iPhone | `setMuteUpdate` → `AudioManager.setMicrophoneMute` | Not handled |
| Phone key on the wheel | Telephony HID, hook switch only | Key 291 → hook switch while calls go over CarPlay |
| Call state to the iPhone (modes) | `appStates` phone call from the car's side (native call) | Logged from the iPhone's `modesChanged`; nothing sent for the car's own calls |

## What this means for DiPlay

1. **PCM 24 kHz calls** would remove the Opus decode and the 48 to 24 kHz step on the car path. Tried
   2026-11-18 in the emulator: with only PCM offered for the call the iPhone set up no audio at all, and
   with Opus kept for the microphone it still chose Opus 48 kHz. Not usable over wireless while DiPlay
   offers Opus elsewhere. The car path now runs at the call's own rate if a call ever is 8/16/24 kHz.
2. **Done (test switch "Calls get their own audio focus"): a separate phone focus request** (usage 2, tag 53, `GAIN_TRANSIENT`, 200 ms retry), not ranked
   below music, so the car's audio policy sees a call, not music, for the whole call.
3. **Done (test switch "Ringtone on the car's ring output"): ringtone like stock** (usage 6, `MODE_RINGTONE`): right bus and ring volume.
4. **Start the call microphone before or with the downlink** on the car path. The HAL plays the
   downlink only through the ECNR capture handler, which exists once the `0x200` capture is open;
   DiPlay opens the output about 75 ms before the microphone.
5. **Port Andrew's Telecom mirroring and vendor call listener** (`56454484`) behind their test switches,
   for the car's call screen and for native Bluetooth calls.
6. **Microphone mute** from the iPhone: small, follows stock.

## Emulator, 2026-11-18

The car path (direct output and `0x200` microphone) stalls in the emulator after 14 to 32 packets on
both the 14 Nov swi68.12 build and `swi68`; with the test switch off calls work. The HAL logged
`AudioMixerTDMOut write FAIL -110` on every write before any call, after emulator audio changes on
16-17 Nov. Stock would stall the same way there (its callback would simply stop being called), so
this is most likely the emulator's audio, not DiPlay. Needs a check in the car or on the emulator side.
