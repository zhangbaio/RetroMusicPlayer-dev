<p align="center">
  <a href="https://retromusic.app">
    <img src="app\src\main\ic_launcher-web.png" height="128">
    <h1 align="center">Retro Music Player 🎵</h1>
  </a>
</p>
<p align="center">
  <a href="https://github.com/RetroMusicPlayer/RetroMusicPlayer" style="text-decoration:none" area-label="Android">
    <img src="https://img.shields.io/badge/Platform-Android-green.svg">
  </a>
  <a href="https://github.com/RetroMusicPlayer/RetroMusicPlayer/actions/workflows/android.yml" style="text-decoration:none" area-label="Build Status">
    <img src="https://github.com/RetroMusicPlayer/RetroMusicPlayer/actions/workflows/android.yml/badge.svg">
  </a>
  <a href="https://github.com/RetroMusicPlayer/RetroMusicPlayer" style="text-decoration:none" area-label="Min API: 24">
    <img src="https://img.shields.io/badge/minSdkVersion-24-green.svg">
  </a>
  <a href="https://github.com/RetroMusicPlayer/RetroMusicPlayer/blob/master/LICENSE.md" style="text-decoration:none" area-label="License: GPL v3">
    <img src="https://img.shields.io/badge/License-GPL%20v3-blue.svg">
  </a>
</p>

---

## 🆕 Material You Design Music Player for Android music lovers

This is an enhanced fork of Retro Music Player with **WebDAV support** for streaming music from cloud storage services.

---

## ✨ New Feature: WebDAV Support

Stream your music collection directly from WebDAV-compatible cloud storage services!

### Supported Services
- Alist (recommended)
- 115 Cloud
- Nextcloud
- ownCloud
- Any WebDAV-compatible server

### How to Configure WebDAV

1. Open the app and go to **Settings** > **WebDAV**
2. Tap **Add Configuration** to create a new WebDAV connection
3. Enter the following information:
   - **Name**: A friendly name for this connection
   - **Server URL**: Your WebDAV server URL (e.g., `http://your-server:5244/dav`)
   - **Username**: Your WebDAV username
   - **Password**: Your WebDAV password
   - **Music Folder**: The path to your music folder on the server
4. Tap **Test Connection** to verify your settings
5. Save the configuration and tap **Sync** to scan for music files

### WebDAV Features
- Secure credential storage using Android Keystore encryption
- Support for HTTP Basic Authentication
- Automatic handling of server redirects (302)
- Retry mechanism for rate-limited servers (429)
- Crossfade playback support for WebDAV songs
- Songs are merged with local library for seamless browsing

---

## 📱 Screenshots

### App Themes
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="200"/> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.jpg" width="200"/> | <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.jpg" width="200"/> |
|:---:|:---:|:---:|
|Clearly white| Kinda dark | Just black|

### Player screen
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.jpg" width="200"/>| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.jpg" width="200"/>| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/6.jpg" width="200"/>| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/7.jpg" width="200"/>| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/8.jpg" width="200"/>|
|:---:|:---:|:---:|:---:|:---:|
| Home | Songs | Albums | Artists | Settings |

### 10+ Now playing themes
| <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.jpg" width="200"/>	|<img src="screenshots/fit.jpg" width="200"/>|   <img src="screenshots/flat.jpg" width="200"/>  	|    <img src="screenshots/color.jpg" width="200"/> 	|     <img src="screenshots/material.jpg" width="200"/>	|
|:-----:	|:-----:	|:-----:	|:-----:	|:-----:	|
| Normal 	| Fit 	| Flat 	| Color 	| Material 	|

| <img src="screenshots/classic.jpg" width="200"/>	|<img src="screenshots/adaptive.jpg" width="200"/>|   <img src="screenshots/blur.jpg" width="200"/>  	|    <img src="screenshots/tiny.jpg" width="200"/> 	|     <img src="screenshots/peek.jpg" width="200"/>	|
|:-----:	|:-----:	|:-----:	|:-----:	|:-----:	|
| Classic 	| Adaptive 	| Blur 	| Tiny 	| Peek 	|

---

## 🧭 Navigation never been made easier
Self-explanatory interface without overloaded menus.

## 🎨 Colorful
You can choose between three different main themes: Clearly White, Kinda
Dark and Just Black for AMOLED displays. Select your favorite accent
color from a color palette.

## 🏠 Home
Where you can view your recently/top played artists, albums and
favorite songs.

## 📦 Included Features

### Cloud & Streaming
- **WebDAV support** - Stream music from cloud storage (Alist, 115, Nextcloud, etc.)
- Secure credential encryption
- Chromecast support

### Playback
- Choose from 10+ now playing themes
- Gapless playback
- Crossfade support
- Volume controls
- Sleep timer

### Library Management
- Browse by songs, albums, artists, playlists and genre
- Smart Auto Playlists - Recently played, most played and history
- Folder support - Play songs by folder
- Tag editor
- Create, edit and import playlists
- Playing queue with reorder

### Customization
- Base 3 themes (Clearly White, Kinda Dark and Just Black)
- Wallpaper accent picker on Android 8.1+
- Material You support on Android 12+
- Monet themed icon support on Android 13+
- Home screen widgets
- Carousel effect for album covers

### Integration
- Android Auto support
- Headset/Bluetooth support
- Lock screen playback controls
- Driving Mode

### Lyrics
- Lyrics screen (download and sync with music)
- Synced lyrics with visual effects

### Other
- Music duration filter
- 30+ languages support
- User profile

---

## 🛠️ Build

### Requirements
- Android Studio Hedgehog or newer
- JDK 21
- Android SDK 35

### Build Commands

```bash
# Build debug APK
./gradlew assembleNormalDebug

# Build release APK
./gradlew assembleNormalRelease

# Build F-Droid variant (without Google Play services)
./gradlew assembleFdroidDebug
```

### Product Flavors
- **normal**: Full version with Google Play services, Cast, and billing
- **fdroid**: Open source variant without proprietary dependencies

---

## 📋 Technical Details

| Property | Value |
|----------|-------|
| Min SDK | 24 (Android 7.0) |
| Target SDK | 36 (Android 15) |
| Compile SDK | 35 |
| Language | Kotlin |
| Architecture | MVVM |
| DI | Koin |
| Database | Room |
| Player | ExoPlayer (Media3) |

---

## 🗂️ License

Retro Music Player is released under the GNU General Public License v3.0
(GPLv3), which can be found [here](LICENSE.md)

---

## 🙏 Credits

Based on [Retro Music Player](https://github.com/RetroMusicPlayer/RetroMusicPlayer) by Hemanth Savarla.

WebDAV support added by contributors.

> **Note**: This is a local music player app with WebDAV streaming support.
> It doesn't support music downloading from streaming services.
