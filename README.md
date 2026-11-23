# Aerocast

Stream your Android phone's sound to speakers on your Wi-Fi: AirPlay-compatible speakers, DLNA/UPnP renderers, or any browser.

Aerocast captures whatever your phone is playing (music apps, videos, games) and sends it to one or several speakers at once. No root needed.

## Features

- **AirPlay-compatible speakers** (AirPlay 1 / RAOP, lossless ALAC)
- **DLNA / UPnP speakers**: lossless LPCM, or AAC 256 kbps for weak Wi-Fi
- **Listen in a browser**: scan a QR code on any phone, tablet or computer on the same Wi-Fi
- **Speaker groups**: connect several speakers with one tap
- **Multi-room sync delay**: hold a speaker back (0–2 s) so all rooms line up
- **Per-speaker EQ, limiter and stereo/mono**; each speaker's volume, delay, format, EQ and mono setting are remembered
- **Record to file**: save what's playing as an AAC `.m4a`
- **App isolation**: stream all apps, only selected apps, or all except some
- System, light and dark theme

## Requirements

- Android 11 or newer
- Phone and speakers on the same Wi-Fi network

Some apps block audio capture (calls, many games, apps with DRM). Android doesn't let anyone capture those.

## Install

Download `app-release.apk` from the [latest release](https://github.com/hackerharis3/aerocast/releases/latest) and open it on your phone. You may need to allow installing apps from your browser or file manager.

**Browser listening:** on Android, Chrome needs the *Nearby devices* permission to open pages on your local network (Settings → Apps → Chrome → Permissions).

## Tested with

| Receiver | Status |
| --- | --- |
| AirPlay speaker (Libratone Live, unencrypted AirPlay) | Works |
| DLNA renderer (Libratone Live) | Works (LPCM and AAC) |
| Browsers (Chrome on Android) | Works |
| AirPlay speakers that require RSA encryption | Not supported yet |
| Google Cast, Sonos, Denon HEOS, Fire TV, Roku, Samsung, Snapcast | Code exists but untested |

Reports for other speakers are very welcome: open an issue with your speaker model and what happened.

## Build from source

Requirements: JDK 17, Android SDK 35, NDK 28.2.13676358 and CMake 3.22.1.

```sh
./gradlew assembleDebug
```

Release builds are signed only if a `keystore.properties` file exists in the project root:

```properties
storeFile=keystore/your-release-key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

Without it, `./gradlew assembleRelease` produces an unsigned APK.

### How it works

- Kotlin + Jetpack Compose UI
- Audio is captured with Android's `AudioPlaybackCapture` and passed to a C++ engine (ring buffer, per-speaker DSP, ALAC framing)
- An embedded Ktor server streams audio to DLNA renderers and browsers
- AirPlay uses RTSP/RTP with NTP-style timing and retransmits

## License

[GPL-3.0](LICENSE). You can use, change and share Aerocast, as long as shared versions stay open source under the same license.

AirPlay is a trademark of Apple Inc. Aerocast is an independent project and is not affiliated with or endorsed by Apple, Libratone or any speaker maker.
