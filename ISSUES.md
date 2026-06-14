# Issues Fixed - SlugYZeon

## Critical

### 1. `ExtendedAudioSourceManager.java` — Unreliable stream detection
- **File:** `main/src/main/java/com/slugyzeon/plugin/ExtendedAudioSourceManager.java:37`
- **Problem:** `InputStream.available()` was used to check if extended track data exists. This method returns an estimate of bytes that can be read *without blocking*, not the total available data. It can return 0 spuriously, causing all extended metadata to be silently lost.
- **Fix:** Removed the `available()` guard. Wrapped reads in try-catch; on `IOException` returns default (null) values.

### 2. `DeezerAudioSourceManager.java` — Empty encodeTrack
- **File:** `main/src/main/java/com/slugyzeon/plugin/deezer/DeezerAudioSourceManager.java:406`
- **Problem:** `encodeTrack()` body was completely empty. All Deezer track metadata (album name, album URL, artist URL, artist artwork, preview URL) was lost on serialization. The decode side also didn't match — it created a bare `DeezerAudioTrack` with all null metadata.
- **Fix:** `encodeTrack()` now writes all fields via `DataFormatTools.writeNullableText()`. `decodeTrack()` reads them back and passes them to the full `DeezerAudioTrack` constructor.

### 3. `DeezerPersistentHttpStream.java` — Byte-by-byte read loop
- **File:** `main/src/main/java/com/slugyzeon/plugin/deezer/DeezerPersistentHttpStream.java:93`
- **Problem:** The `read(byte[], int, int)` method called `read()` in a tight loop for every single byte, causing extreme CPU overhead during audio streaming.
- **Fix:** Now checks for available buffered data first and copies it in bulk. Falls back to single-byte only for remaining bytes.

### 4. `GaanaAudioTrack.java` — Nested executeProcessingLoop deadlock
- **File:** `main/src/main/java/com/slugyzeon/plugin/gaana/GaanaAudioTrack.java:52`
- **Problem:** `process()` called `executor.executeProcessingLoop()` which inside called `adtsTrack.process(executor)` — nesting the same executor's processing loop, causing thread starvation / deadlock.
- **Fix:** Simplified to use `processDelegate(adtsTrack, executor)` which is the standard Lavaplayer delegation pattern.

### 5. `GaanaHlsInputStream.java` — Thread-safety of downloadThread
- **File:** `main/src/main/java/com/slugyzeon/plugin/gaana/GaanaHlsInputStream.java:37`
- **Problem:** `downloadThread` field was not `volatile`. The constructor starts a thread and `close()` could run before the field was visible to the closing thread, causing NPE on `downloadThread.interrupt()`.
- **Fix:** Made `downloadThread` volatile.

### 6. `DefaultMirroringAudioTrackResolver.java` — Wrong operator precedence
- **File:** `main/src/main/java/com/slugyzeon/plugin/mirror/DefaultMirroringAudioTrackResolver.java:75`
- **Problem:** `if (item instanceof AudioPlaylist && list.isEmpty() || item == NO_TRACK)` — `&&` binds tighter than `||`, so this evaluated as `(instanceof && isEmpty) || (NO_TRACK)`. A non-playlist `NO_TRACK` would bypass the instanceof check and could cause ClassCastException.
- **Fix:** Added parentheses: `if ((instanceof && isEmpty) || item == NO_TRACK)`.

### 7. `SpotifyAudioSourceManager.java` — Null manager passed to loadItem
- **File:** `main/src/main/java/com/slugyzeon/plugin/spotify/SpotifyAudioSourceManager.java:214`
- **Problem:** `resolveShareUrl()` called `loadItem(null, ref)` which passes null as `AudioPlayerManager`. The token refresh code path relies on the manager reference and would NPE.
- **Fix:** Changed to `loadItem(this.getAudioPlayerManager(), ref)`.

### 8. `YouTubeSourceManager.java` — Missing registerSourceManager call
- **File:** `main/src/main/java/com/slugyzeon/plugin/youtube/YouTubeSourceManager.java:75`
- **Problem:** `attachToYouTube()` replaces the source in the internal list via reflection but never calls `manager.registerSourceManager(this)`. Lavalink may iterate registered sources elsewhere and miss YouTube.
- **Fix:** Added `manager.registerSourceManager(this)` after replacing the source.

---

## Medium

### 9. YouTubeTrack fallback — Overly strict identifier filter
- **File:** `main/src/main/java/com/slugyzeon/plugin/youtube/YouTubeTrack.java:124,136`
- **Problem:** Fallback search skipped results where `track.getIdentifier().equals(this.videoId)`, meaning if the search returned the exact same video, it was discarded. This could exhaust all fallbacks unnecessarily.
- **Fix:** Removed the `!equals(videoId)` check — any `InternalAudioTrack` result is now accepted.

### 10. makeShallowClone — Metadata loss in Spotify/Pandora/Amazon tracks
- **Files:**
  - `main/src/main/java/com/slugyzeon/plugin/spotify/SpotifyAudioTrack.java:29`
  - `main/src/main/java/com/slugyzeon/plugin/pandora/PandoraAudioTrack.java:29`
  - `main/src/main/java/com/slugyzeon/plugin/amazonmusic/AmazonMusicAudioTrack.java:29`
- **Problem:** All three used the single-arg constructor in `makeShallowClone()`, which passes `null` for every extended field (album name, album URL, artist URL, artist artwork, preview URL, isPreview).
- **Fix:** Changed to use the full-arg constructor, preserving all metadata on clone.

### 11. Empty catch blocks — Silent failure everywhere
- **Files:** `YouTubeProxyHandler.java`, `YouTubeTrack.java`, `SpotifyAudioSourceManager.java`, `DeezerApiHandler.java`, `InstagramApiHandler.java`
- **Problem:** Multiple empty catch blocks (`catch (Exception ignored) {}`) swallowed all errors silently, making debugging impossible.
- **Fix:** Added `log.debug()` calls with meaningful context in all critical catch blocks.

---

## Minor

### 12. Hardcoded YouTube API keys
- **File:** `main/src/main/java/com/slugyzeon/plugin/youtube/clients/InnerTubeClient.java:6-7`
- Two Google API keys hardcoded. These are public client-side keys but should be rotated if abused.

### 13. InstagramConfig — Empty config class
- **File:** `plugin/src/main/java/com/slugyzeon/plugin/config/InstagramConfig.java`
- Class has no fields despite hardcoded Instagram API parameters.

### 14. search_tracks.json — Empty file
- Zero-byte JSON file with no clear purpose.

### 15. YouTubeSourceManager — Reflection hack
- `findSourceList()` uses `setAccessible(true)` on private fields of `AudioPlayerManager`. Fragile across library versions.
