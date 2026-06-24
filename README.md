# BrontoPlayer

A focused, gentle Android audiobook player for **`.m4b`** (and `.m4a` / `.mp4` audio) files.
Built with Kotlin, Jetpack Compose (Material 3), and Media3/ExoPlayer.

> **Handles audiobooks with more than 255 chapters.** Most "255 chapter" bugs come from the
> Nero `chpl` box, whose chapter count is a single byte. BrontoPlayer reads the MP4
> **chapter text track** (32-bit sample tables) instead, so books with hundreds or thousands of
> chapters load completely. See [The 255-chapter problem](#the-255-chapter-problem).

## Features

- **Library** — import individual files or whole folders via the Storage Access Framework
  (no broad storage permission needed). Cover art, author, narrator and progress at a glance.
- **Player** — large cover, whole-book scrubber, play/pause, configurable rewind/fast-forward,
  previous/next chapter, and resume-from-where-you-left-off.
- **Chapters** — a virtualized list that stays smooth with **thousands** of chapters; tap to jump.
- **Bookmarks** — drop a bookmark anywhere, add a note, jump back later.
- **Playback speed** — 0.5x–3.0x, remembered per book.
- **Skip silence** — automatically shortens long silent gaps (ExoPlayer).
- **Sleep timer** — fixed durations or "end of chapter".
- **Background playback** — a foreground media service keeps audio running with the screen off,
  with audio-focus handling and a wake lock. The app asks for the two permissions that matter:
  notifications (Android 13+) and a **battery-optimization exemption** so the OS does not kill it.
- **Looks the part** — Material 3 with dynamic color (Android 12+) and a hand-tuned "Bronto"
  forest/leather palette, light & dark.

## The 255-chapter problem

MP4/M4B audiobooks can describe chapters in two ways:

| Representation | Count field | Limit |
| --- | --- | --- |
| Nero `chpl` box (`moov/udta/chpl`) | **single byte** | **255 chapters max** |
| QuickTime **chapter text track** (`tref`/`chap` → `text` track) | 32-bit sample tables | effectively unlimited |

`Mp4ChapterParser` **prefers the chapter text track** and only falls back to `chpl` when no text
track exists. It walks the sample tables (`stts`, `stsc`, `stsz`/`stz2`, `stco`/`co64`) to compute
each chapter's title and start time, so chapter count is never capped at 255. It also reads
`co64` 64-bit offsets, multi-chunk layouts, UTF-8/UTF-16 titles, and embedded cover art.

This is verified by unit tests, including synthetic 300- and 2000-chapter files and a check that a
`chpl`-only file is (correctly) limited to 255 while a text-track file is not.

## Project layout

```
BrontoPlayer/
├── m4b/                      Pure-JVM M4B/MP4 chapter parser (no Android deps) + unit tests
│   └── src/main/kotlin/com/brontoplayer/m4b/
├── app/                      Android application
│   └── src/main/java/com/brontoplayer/
│       ├── data/             Room database, repository, DataStore settings, SAF I/O
│       ├── playback/         Media3 MediaSessionService + client connection, sleep timer
│       ├── ui/               Compose screens (library, player, chapters, bookmarks, settings)
│       └── util/             Time formatting, background-permission helpers
├── settings.gradle.kts       Composite build: app + included m4b build
└── gradle/libs.versions.toml Version catalog
```

The parser is a standalone Gradle build included into the app via composite-build dependency
substitution, which keeps it free of Android dependencies and unit-testable on a plain JVM.

## Building

Open the project in a recent Android Studio (the version catalog pins AGP, Kotlin, Media3, etc.),
let it sync, and run the `app` configuration. Or from the command line with an Android SDK present:

```bash
./gradlew :app:assembleDebug
```

### Running the parser tests (no Android SDK required)

The `m4b` module is plain Kotlin/JVM, so its tests run anywhere with a JDK:

```bash
./gradlew -p m4b test
```

## Permissions

| Permission | Why |
| --- | --- |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Run playback as a foreground service |
| `POST_NOTIFICATIONS` | Show the media notification (Android 13+) |
| `WAKE_LOCK` | Keep the CPU awake while playing with the screen off |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Ask the OS not to kill background playback |

Files are read through the Storage Access Framework with persistable URI permissions, so no
broad `READ_*_STORAGE` / `READ_MEDIA_AUDIO` permission is required.

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
