# TV Remote (Android)

[![Build APK](https://github.com/MhamzaM/android-tv-remote/actions/workflows/build.yml/badge.svg)](https://github.com/MhamzaM/android-tv-remote/actions/workflows/build.yml)

An Android phone app that works as a remote for **Android TV / Google TV** and **Roku TV**, which covers TCL's smart TVs:

| TCL TV type | How the app talks to it |
|---|---|
| TCL Google TV / Android TV (C-series, P-series, QM, etc.) | Android TV Remote protocol v2, the same one Google's own "Google TV" phone app uses (TLS, ports 6466/6467, one-time pairing code) |
| TCL Roku TV (S-series, 4/5/6-Series Roku) | Roku External Control Protocol (HTTP, port 8060) |

It also works with other brands on those platforms (Sony, Hisense, Philips, Chromecast with Google TV, Hisense Roku, etc.).

## Features
- Finds TVs on your Wi-Fi automatically (mDNS for Google TV, SSDP for Roku), or add one by IP. The add dialog can detect the TV type for you.
- One-time pairing with the 6-character code shown on the TV (Google TV / Android TV).
- Power, D-pad + OK, Back, Home, Menu/Options, Input, Settings, Search
- Volume / Channel rockers (hold to repeat), Mute, media keys (rewind, play/pause, fast-forward, Roku instant replay)
- The phone's own volume buttons control the TV while the remote is open
- Number pad, direct input switching (TV/Antenna, HDMI 1–4, AV)
- App launcher: YouTube, Netflix, Prime Video, Disney+ and more on Google TV (plus "open by package name"). On Roku it lists the channels installed on the TV.
- Keyboard: type into search boxes on the TV
- Remembers your TVs and reconnects to the last one at launch
- Haptic feedback, dark UI, written in Kotlin + Jetpack Compose with no third-party libraries

## Download
Every push is built automatically by GitHub Actions. Open the [Actions tab](https://github.com/MhamzaM/android-tv-remote/actions), pick the latest successful "Build APK" run, and download **TVRemote-apk** (sign-in required). Tagged versions are published on the [Releases page](https://github.com/MhamzaM/android-tv-remote/releases), where anyone can download them.

## Build & install
1. Install **Android Studio** (Ladybug 2024.2 or newer).
2. **File › Open** and pick this `TVRemote` folder. Let Gradle sync (it downloads the Android SDK 35 and dependencies the first time).
3. Plug in your phone with USB debugging on and press **Run ▶**. Or use **Build › Build App Bundle(s) / APK(s) › Build APK(s)** and copy `app/build/outputs/apk/debug/app-debug.apk` to the phone.

Requires Android 8.0+ on the phone.

## Using it with a TCL TV
- Put the phone and the TV on the **same Wi-Fi network**.
- **TCL Google TV / Android TV**: tap the TV in the list. A code appears on the TV; type it in. You only pair once.
- **TCL Roku TV**: on the TV go to **Settings › System › Advanced system settings › Control by mobile apps** and set network access to **Default** or **Permissive**. If you skip this, the app shows a "TV blocked the command" message.
- TV not found? Look up its IP address (Google TV: *Settings › Network & Internet › your network*; Roku: *Settings › Network › About*) and use **Add by IP**.
- Power on from standby only works if the TV keeps its network connection in standby. On TCL Google TV, turn on the *Quick start* / network standby option.

## Project layout
```
app/src/main/java/com/tvremote/
  proto/Proto.kt            minimal protobuf encoder/decoder + length framing
  atv/SelfSignedCert.kt     builds the client certificate (no BouncyCastle needed)
  atv/AtvIdentity.kt        stores the certificate, creates the TLS context
  atv/AtvPairing.kt         pairing (port 6467): code -> SHA-256 secret
  atv/AtvRemote.kt          remote session (port 6466): keys, text, app links, pings, volume/power state
  roku/RokuRemote.kt        Roku ECP over HTTP
  discovery/TvDiscovery.kt  mDNS + SSDP discovery, IP probe
  data/                     models, key mapping, saved TVs
  ui/                       Compose screens + ViewModel
```

## Not supported (yet)
- Older TCL Linux smart TVs (pre-Android "TCL Smart TV" / nScreen) and TCL Fire TV models use other protocols.
- Voice search (needs a microphone audio stream; the protocol supports it but it isn't implemented).
- IR blaster control for non-smart TVs.
