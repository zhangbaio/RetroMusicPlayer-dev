# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Retro Music Player is a Material You Design local music player for Android. It's a multi-module Android application built with Kotlin and Java, following MVVM architecture with Koin dependency injection.

## Build Commands

```bash
# Build APKs (two flavors available)
./gradlew assembleNormal      # Google Play version (with Cast, billing)
./gradlew assembleFdroid      # F-Droid version (FOSS variant)

# Build variants
./gradlew assembleDebug       # Debug build
./gradlew assembleRelease     # Release build

# Quality checks
./gradlew lint                # Run lint
./gradlew build               # Build and run tests
```

## Build Configuration

- **Compile SDK**: 35
- **Min SDK**: 24 (Android 7.0)
- **Target SDK**: 36 (Android 15)
- **Java**: 21
- **Kotlin**: 2.1.21
- **Namespace**: `code.name.monkey.retromusic`
- **Version**: 6.6.0 (versionCode 10660)

Two product flavors exist:
- **normal**: Includes Google Play services, Cast framework, billing, and web server
- **fdroid**: Open source variant without proprietary dependencies

## Architecture

### Module Structure

- **app**: Main application module containing all UI, business logic, and services
- **appthemehelper**: Shared theming library for Material Design 3 support

### Key Architecture Components

#### MVVM + Repository Pattern

The app uses a layered architecture with repositories as the data layer:

```
MainActivity (Activity)
    ↓
Fragments (View)
    ↓
ViewModels (ViewModel)
    ↓
Repository Interface
    ↓
RealRepository (Implementation)
    ↓
Specialized Repositories (SongRepository, AlbumRepository, etc.)
```

**Repository Layer** (`app/src/main/java/code/name/monkey/retromusic/repository/`):
- `Repository` interface + `RealRepository` implementation (main facade)
- `SongRepository`, `AlbumRepository`, `ArtistRepository` - media content queries
- `GenreRepository`, `PlaylistRepository` - specialized content
- `RoomRepository` - local database operations
- `TopPlayedRepository`, `LastAddedRepository` - smart playlists
- `SearchRepository` - search functionality

**Dependency Injection** (`MainModule.kt`):
- Uses Koin 3.5.6 for DI
- Modules: `mainModule`, `dataModule`, `autoModule`, `viewModules`, `networkModule`, `roomModule`
- All repositories are singletons bound to their interfaces

#### Navigation

- AndroidX Navigation Component 2.9.0
- Navigation graphs: `main_graph.xml`, `library_graph.xml`, `settings_graph.xml`
- SafeArgs for type-safe navigation
- MainActivity manages navigation with bottom nav

#### Database

- Room database (`RetroDatabase`) version 24
- Entities: `PlaylistEntity`, `SongEntity`, `HistoryEntity`, `PlayCountEntity`
- DAOs: `PlaylistDao`, `HistoryDao`, `PlayCountDao`
- Migration support in `RoomMigrations.kt`

#### Service Layer

`MusicService` extends `MediaBrowserServiceCompat`:
- Handles music playback using ExoPlayer
- Manages MediaSession for Android Auto/lock screen controls
- Notification system for playback controls
- Runs as a foreground service

#### UI Structure

- **Activities**: `MainActivity` (main entry), `LockScreenActivity`, activities for settings, tag editor, etc.
- **Fragments**: Organized by feature (albums, artists, genres, playlists, player themes)
- **Base Classes**: `AbsMusicServiceFragment`, `AbsPlayerFragment` provide common functionality
- **Player Fragments**: Multiple now-playing themes (normal, flat, fit, blur, material, etc.) in `fragments/player/`

### Package Structure

```
app/src/main/java/code/name/monkey/retromusic/
├── activities/          # Activity classes
│   ├── base/           # Base activity classes (AbsTheme, AbsMusicService, etc.)
│   └── tageditor/      # Tag editor activities
├── fragments/          # Fragment classes (screens)
│   ├── base/           # Base fragment classes
│   ├── player/         # Now playing screen implementations (10+ themes)
│   ├── settings/       # Settings fragments
│   ├── home/           # Home screen
│   ├── albums/         # Album browsing
│   ├── artists/        # Artist browsing
│   └── playlists/      # Playlist management
├── repository/         # Data repositories
├── db/                # Room database entities & DAOs
├── service/           # MusicService (foreground playback service)
├── model/             # Data models (Song, Album, Artist, Genre, etc.)
├── helper/            # Helper classes (MusicPlayerRemote, ShuffleHelper, etc.)
├── network/           # Last.fm API integration
├── preferences/       # Preference screens
├── util/              # Utility classes
└── views/             # Custom views
```

## Key Dependencies

- **ExoPlayer** 1.6.1: Media playback
- **Room** 2.7.1: Local database
- **Navigation** 2.9.0: In-app navigation
- **Lifecycle** 2.9.1: ViewModel and LiveData
- **Koin** 3.5.6: Dependency injection
- **Material Design** 1.12.0: UI components
- **Glide** 4.15.1: Image loading
- **Retrofit** 3.0.0: API calls (Last.fm)
- **Coroutines** 1.10.2: Async operations
- **JAudioTagger**: Audio metadata reading

## Important Files

- `app/build.gradle.kts`: App module build configuration
- `app/src/main/java/code/name/monkey/retromusic/MainModule.kt`: Koin DI setup
- `app/src/main/java/code/name/monkey/retromusic/repository/Repository.kt`: Main repository interface
- `app/src/main/java/code/name/monkey/retromusic/service/MusicService.kt`: Music playback service
- `app/src/main/java/code/name/monkey/retromusic/activities/MainActivity.kt`: Main entry point
- `gradle/libs.versions.toml`: Version catalog for dependencies
- `retro.properties`: App signing configuration (not in repo)

## Development Notes

- Uses Kotlin DSL for Gradle
- View binding enabled throughout
- Material You theming on Android 12+
- Implements 10+ different now playing screen themes
- Supports Android Auto
- Chromecast support (normal flavor only)
- Synced lyrics support with visual effects
- Gapless playback
- Tag editing capabilities
