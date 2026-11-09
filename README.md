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
- Each sets the equalizer, bass boost, surround and loudness (Happy and Party lean on bass)
- Fine-tune any mood and save your own custom moods
- Melo remembers which songs you play in each mood and builds a "mood mix" you can play

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

## Tech

Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer + MediaSession, Room, DataStore, Coil, ML Kit on-device translation and language ID, whisper.cpp v1.9.5 via JNI.
