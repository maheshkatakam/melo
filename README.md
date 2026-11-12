# Melo

A personal, fully offline music player for Android that tunes the sound to your mood and gives you English lyrics, even for regional songs.

## Install on your phone

1. On your phone, open this repo's **Releases** page (sign in to GitHub, since the repo is private).
2. Download the newest `Melo-1.0.x.apk`.
3. Open it and allow "Install unknown apps" for your browser or Files app when Android asks.
4. Later updates install right over the old version and keep your playlists, favorites and lyrics.

Every push to `main` builds a new APK automatically (Actions tab → "Build Melo APK").

## What it does

**Playing music**
- Finds every song on the phone; "Add songs" copies files in from anywhere (Downloads, USB, WhatsApp…)
- Songs, Albums, Artists, Folders and Playlists tabs, sorting, and fast search
- Queue with shuffle, repeat, play next, swipe to remove
- Favorites, Recently played, Top songs, Recently added
- Lock screen and notification controls, Bluetooth/headset buttons, pauses when headphones unplug
- Sleep timer with gentle fade-out (or stop at end of song)
- Speed, pitch and skip-silence controls
- Remembers your queue and position after restarts
- Hide folders you don't want (voice notes, ringtones) and ignore short clips
- Open audio files from a file manager with "Open with Melo"

**Mood sound modes**
- Normal, Happy, Party, Workout, Chill, Romantic, Mellow, Focus and Sleep
- Each mood is tuned for how you'd actually listen in it. Party: deep kick, clean and loud, extra wide, every song evened out. Focus: vocals step back, steady and quieter. Sleep: dark, soft, nothing sudden. And so on; the app describes each one.
- Runs on Melo's own sound engine (tone, bass punch, stereo width, spatial, room, warmth, volume evening, and a limiter that prevents distortion), so moods sound the same on every phone. Normal leaves the audio completely untouched.
- Fine-tune any mood and save your own custom moods
- Melo remembers which songs you play in each mood and builds a "mood mix" you can play

**3D sound and quality (all free, all on the phone)**
- **3D sound for headphones** (the 3D button on the player): Melo re-stages any stereo song as it plays. In every slice of time and frequency it separates distinct sounds from ambience, places the sounds on a wide stage in front of you and the ambience at your sides, behind and above, and renders it through measured ear responses (MIT KEMAR) so it seems to come from outside your head. Works on any headphones, with any mood. It switches itself off on speakers.
- **Real surround files**: 5.1 and 7.1 files (FLAC, Opus, AAC…) play as virtual speakers around you. That's the free, legal alternative to Atmos files.
- **Phone spatial audio (experimental)**: on phones that have it, Melo can hand the song over as 5.1 so the phone does the 3D part, which is what enables head tracking on supported earbuds.
- **Rebuild missing treble**: low-bitrate files have everything above ~16 kHz cut off. Melo measures each song and fills the top back in by copying the band below it upward (spectral band replication), in time with the music. A best guess, not the original.
- **Hi-res direct**: optionally play 24-bit files with no processing at full precision.
- None of this is Dolby Atmos or true lossless; nothing can create those from a normal file. It's the closest honest equivalent.

**Real quality check**
- The player shows each song's format (for example "MP3 · 320 kbps · 44.1 kHz").
- Song details → **Check real quality** listens to the song and tells you what it really is. Many downloads labelled 320 kbps were converted up from 128 kbps; Melo spots that from the missing high frequencies.
- Melo can't turn a normal file into lossless or Atmos (nothing can; the detail isn't in the file). It plays every file at its own quality.

**Pictures**
- Uses the cover stored inside the song file, or `cover.jpg` / `folder.jpg` next to it
- If a song has no picture, it gets a generated gradient cover, and you can choose any image yourself
- The player's colours follow the song's picture

**Lyrics, in English, offline**
- Reads lyrics stored inside MP3, FLAC, OGG/Opus and M4A files, and `.lrc` files next to songs (timed lyrics scroll with the music)
- You can also import an `.lrc` file or paste lyrics
- Lyrics stored in another language are translated on the phone (one-time ~30 MB language pack per language; Hindi, Telugu, Tamil, Kannada, Marathi, Bengali, Gujarati, Urdu and many more)
- For songs with no lyrics at all, an on-device AI (OpenAI's Whisper "small" model running through whisper.cpp) listens to the song and writes the lyrics directly in English, with timing. It works for ~99 languages, needs no internet, and takes a few minutes per song. It's best-effort: loud music over the vocals causes mistakes.
- **Get lyrics for all songs in one tap** (card on the Home screen): works through your whole library in the background, most-played songs first, with progress and a Stop button in the notification. It carries on if you close Melo, picks up again after a restart, and by default only runs while the phone is charging (switch it off on the card). Songs with no singing are skipped next time.
- Or turn on **Make lyrics as I listen** in Settings so each song you play gets lyrics automatically.

**Identify songs around you (free, needs internet)**
- Tap **Identify a song** (Home screen, the mic button, the Quick Settings tile, or long-press the Melo icon). Melo listens for up to 12 seconds and usually answers within 4–8.
- Completely free, no account or key: Melo makes an audio fingerprint on the phone and looks it up in Shazam's catalogue (great coverage of Bollywood and South Indian music). Only the fingerprint (a few KB) is sent, never the audio.
- This uses Shazam's unofficial interface, the same way the open-source [SongRec](https://github.com/marin-m/SongRec) and [ShazamIO](https://github.com/shazamio/ShazamIO) do, so it could break if Shazam changes it. The official route always works too: identify in the Shazam app and tap **Share › Save to Melo**.
- Everything lands in **Discovered** (Library tab). When a discovered song later shows up in your library, Melo adds it to Favorites, and uses the found cover if the file has no picture.
- No internet? Melo keeps the fingerprint and identifies the song automatically when you're back online.

## Honest limits

- **Pictures**: without internet there's no way to look up the correct cover for a song that doesn't already have one. Use "Choose picture" for those.
- **AI lyrics** are a transcription, not the official lyrics. Expect the gist, with some wrong words, especially for fast or heavily produced songs.
- **Mood modes** use Android's built-in audio effects. Some phones (e.g. with Dolby Atmos switched on) or headphones with their own EQ can change the result.
- AI lyrics need a 64-bit phone from roughly 2018 or newer. On older phones the option is hidden.

## Building it yourself

The GitHub workflow does everything. Locally: Android Studio (Ladybug or newer), JDK 17, NDK 27.2 and CMake 3.22.1, then download the model:

```
curl -L -o app/src/main/assets/models/ggml-small-q5_1.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin
./gradlew assembleRelease
```

Release signing uses `signing/melo-release.jks` (encrypted). Its password is the `MELO_KEYSTORE_PASSWORD` repository secret; locally, set `MELO_KEYSTORE_PATH`, `MELO_KEYSTORE_PASSWORD`, `MELO_KEY_ALIAS=melo` and `MELO_KEY_PASSWORD` (same password) in the environment.

## Credits

- Fingerprinting is a Kotlin port of [shazamio-core](https://github.com/shazamio/shazamio-core) (MIT, Copyright (c) 2024 dotX12); see `licenses/shazamio-core-MIT.txt`.
- 3D sound uses the MIT KEMAR HRTF measurements (Bill Gardner and Keith Martin, MIT Media Lab, 1994); see `licenses/KEMAR-HRTF.txt`.
- On-device lyrics AI: [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (MIT) with OpenAI's Whisper model.
- Font: Poppins (SIL Open Font License).

## Tech

Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSession, Room, DataStore, Coil, ML Kit on-device translation and language ID, whisper.cpp v1.9.5 via JNI.
