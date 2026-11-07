# MG4 SWI68 firmware

Checked against `SWI68-29958-1300R59` (Android 9, `ro.build.mt2712.version=SWI68-29958-1300R59`),
read from the USB OTA `payload.bin` (system and vendor images rebuilt from the payload, ext4
dumped, system apps decompiled with jadx 1.5.6). Not yet tried in a car.

SWI68 runs the same eh32 system apps as SWI165, so `Mg4FirmwareProfile` gives it the SWI165
profile. Before that, SWI68 was unknown firmware: hybrid Bluetooth audio, whose A2DP check sent the
driver to Bluetooth settings every time CarPlay opened (the iPhone drops A2DP during wireless
CarPlay).

## What DiPlay relies on, and what SWI68 has

| DiPlay code | Expects (from SWI165) | SWI68 R59 | |
|---|---|---|---|
| `Mg4BluetoothSource` | `com.saicmotor.service.media/.MediaService`, exported; `BT_MUSIC_ACTION`, `PLAY_STATUS_ACTION` | Same; exported, no permission | Match |
| | `IBtMusicBinderInterface` play 2, getPlayState 9, getBluetoothState 11; transactions 1-14 only | play 2, getPlayState 9, getBluetoothState 11; 1-14 only | Match |
| | `IPlayStatusBinderInterface` getCurrentPlayer 1, getCurrentMediaSource 9 | 1, 9 | Match |
| | play tags the Bluetooth source `key_car_source_type=5` | `A2dpAudioFocusHandler`: 5 | Match |
| `Mg4CarAudioSource`, call track | AllGo `CarPlayAudioManager`: phone 53 with usage 2 and `MODE_IN_CALL`; media 50, navigation 52 | Phone 53 usage 2, `MODE_IN_CALL`; media 50 usage 1; navigation 52 usage 12; Siri 51, ring 54, spoken 55, alert 56 | Match |
| `Mg4ClusterNavigation` | `com.saicmotor.adapterservice/.services.MapService`, exported; `IMapService` 22, 29, 30, 31, 38, 39 | Exported, no permission; isMapNavigating 22, guideStatusChange 29, guideInfosChange(int, int, String) 30, roadInfoChange 31, remainingTimesChange 38, remainingDistanceChange 39 | Match |
| `CarPlayMediaKeys` | `com.android.systemui.ACTION_HARD_KEY_EVENT`, `KEY_CODE` 4 previous, 5 next | SystemUI `PhysicalKeysBroadcastReceiver`: raw 299 -> 4, 300 -> 5 in the wheel's default mode | Match |
| | Raw `com.saic.keyevent.hardkey.report`, extras `android.intent.extra.hardkey.{keycode,down,longpress}`; 291 phone, 287 voice | Same action, extras, 291 and 287 | Match |
| `Mg4WheelSpeed` gear | `vehiclecondition`, `IVehicleConditionService` getCarGear 5; 1 P, 2 R, 3 N, 4 D | Same service and code. 2 is reverse in SystemUI (custom and phone keys ignored). DiAuto's EVHardware: read confirmed on SWI68, P = 1 | Match (N and D not shown in code) |
| Overlay video default | Stock AllGo draws into a `SurfaceView` | `CarPlayActivity` uses `SurfaceView` | Match |
| Dark mode | `screen_day_night_mode`, `SAIC_DAY_NIGHT_MODE_FROM_CAR` | Both present (framework, CarService, SystemUI) | Match |
| `Mg4A2dpBrowser` (fallback) | `com.android.bluetooth.a2dpsink.mbs.A2dpMediaBrowserService` | Declared by `Bluetooth_nfore_eh32` | Match |
| Stock launcher | `com.saicmotor.hmi.launcher` | Same | Match |

## Notes

- The wheel's custom-key mode (`getSwcFunctionChangeSwa`, set in the car's settings) changes the
  reformatted codes: mode 1 sends previous 9 and next 10, mode 2 sends 14 and 13. DiPlay only
  listens for 4 and 5, so in modes 1 and 2 next and previous may not reach CarPlay. The SWI165
  notes only cover the default mode.
- System apps are signed with the AOSP platform key (SHA-1
  `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`), the key the build script signs
  with.
- DiAuto-MG4 warns that SWI68 R46 lets only one app at a time hold a Bluetooth connection, fixed
  by R63. R59 sits in between; wireless CarPlay starting on this car shows the bootstrap works.
