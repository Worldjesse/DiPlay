# Compatibility

This public preview is an independent receiver, not an Apple-certified CarPlay accessory. The experimental bundled accessory identity is extractable and its future acceptance is not guaranteed.

| Area | Current scope |
| --- | --- |
| Head unit | Android 8.0 (API 26)+ APK; wireless Wi-Fi Direct path needs Android 10+ |
| Architectures | `arm64-v8a`, `armeabi-v7a`, `x86_64`. Intel x86 head units are a supported target, not an accident |
| Phone | Standard, non-jailbroken iPhone with CarPlay enabled; device/iOS compatibility varies |
| Physical evidence | Previous private builds: wired and wireless picture, touch and audio confirmed on the development car with iPhone XS / iOS 18.7.10 |
| Vehicle platforms | BYD DiLink (HUD, instrument cluster and vehicle data); GWM Lemon on Harman / iFlytek HiLife (CarPlay only, see below) |
| Current release | DiLink5.1: HUD/street names and Car hotspot confirmed; Wi-Fi Direct improved, occasional audio cutouts remain |
| Wi-Fi | Prefer 5 GHz without an established station connection; align to a supported existing station channel; explicit 2.4 GHz fallback for firmware that rejects 5 GHz or automatic channel selection |
| Video | Default H.264 / 30 fps; 60 fps and HEVC increase device-specific demands |

## Vehicle platforms

The CarPlay receive path is vendor-neutral: iAP2 pairing, the AirPlay session, RTSP streaming, HID
input and audio all speak to the iPhone, not to the car. What differs between head units is the
navigation output around that path, and whether vehicle data can be read at all. A
`VehiclePlatform` answers exactly those two questions; the core path never asks them.

| Platform | Navigation output | Vehicle data |
| --- | --- | --- |
| BYD DiLink | Windshield HUD over SOME/IP, instrument cluster, DiLink 5 standalone receiver | Speed, gear, battery, range over the autoservice binder via ADB |
| GWM Lemon (Harman / iFlytek HiLife) | None. Navigation stays inside the projected window | Not read |
| Unrecognised head unit | None | Not read |

An unrecognised head unit is a normal outcome, not an error: CarPlay over USB still works, and
DiPlay hides the vendor switches rather than showing settings that do nothing. The detected
platform id, head-unit firmware and ABI list are logged once per CarPlay session.

## BYD HUD and car hotspot

See [BYD navigation](BYD_NAVIGATION.md) for the exact verified firmware and lifecycle limits. Car
hotspot now starts CarPlay on the development car using scoped IPv6. The phone must join the
configured car hotspot. Neither result guarantees support on every firmware. Mixed community
reports exist across DiLink generations; this is not a certified model support list.

### GWM Lemon specifics

These seats run Android 8.1 on Intel x86, so:

- **Wired USB is the recommended path** and is unaffected by the Android version.
- **Wi-Fi Direct is not offered**, because configuring a P2P group with an SSID and WPA2 passphrase
  only arrived in API 29. The built-in car hotspot is the only wireless option, and the ADB-assisted
  hotspot controls stay hidden because they are a BYD path.
- **Vehicle data is not read.** Unlike DiLink, Lemon exposes no autoservice binder an unprivileged
  app can reach over ADB, and its steering-wheel and cluster integrations are not documented. The
  iPhone therefore uses its own GPS for dead reckoning and treats the car as one that reports
  nothing, which is its normal behaviour on any such car. DiPlay does not guess at service names:
  implementing this means reverse-engineering a real Lemon head unit and filling in
  `GwmLemonPlatform`.

## Known limitations

- Some units stutter, particularly under higher video load. A 2.4 GHz link alone does not prove the cause: interference, firmware and decoder stalls can all contribute. Try Default icons, 30 fps and a lower resolution, then attach a report.
- Some iOS/head-unit combinations do not visibly apply icon and text size. Reconnection is implemented; that does not guarantee the iPhone chooses the requested layout.
- A radio that supports joining a 5 GHz network may still reject a 5 GHz Wi-Fi Direct group. The capability flag is diagnostic, not proof of group-owner support.
- Automatic startup depends on the car's firmware and startup permissions.
- USB requires a data port and correct host/device-role behavior.
- Calls, Siri, background reconnection, long journeys and future iOS releases need broader testing.

Reports record requested and actual frequencies, station association state, fallback failures and remembered-configuration events. Wi-Fi credentials and protocol payloads are excluded. A successful hotspot is not itself a successful CarPlay session.

Android references: [SupplicantState](https://developer.android.com/reference/android/net/wifi/SupplicantState), [explicit P2P operating frequency](https://developer.android.com/reference/android/net/wifi/p2p/WifiP2pConfig.Builder#setGroupOperatingFrequency(int)).
