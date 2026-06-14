# Changelog

## [1.0.1] - 2026-06-14

### Fixed

#### Critical
- **ExtendedAudioSourceManager**: Replaced unreliable `InputStream.available()` check with try-catch for EOF-safe decoding
- **DeezerAudioSourceManager**: Implemented empty `encodeTrack()` — Deezer track metadata is now properly serialized/deserialized
- **DeezerPersistentHttpStream**: Fixed `read(byte[],int,int)` from O(n) byte-by-byte loop to bulk-copy from buffer
- **GaanaAudioTrack**: Removed deadlock-prone nested `executeProcessingLoop()` — uses `processDelegate()` instead
- **GaanaHlsInputStream**: Made `downloadThread` volatile to prevent NPE on concurrent `close()`
- **DefaultMirroringAudioTrackResolver**: Fixed `&&`/`||` operator precedence bug that could cause ClassCastException
- **SpotifyAudioSourceManager**: Fixed NPE in `resolveShareUrl()` by passing proper manager reference
- **YouTubeSourceManager**: Added missing `registerSourceManager()` call in `attachToYouTube()`

#### Medium
- **YouTubeTrack**: Removed overly strict identifier filter in fallback search that skipped valid results
- **SpotifyAudioTrack**: `makeShallowClone()` now preserves all extended metadata (album, artist, preview)
- **PandoraAudioTrack**: `makeShallowClone()` now preserves all extended metadata
- **AmazonMusicAudioTrack**: `makeShallowClone()` now preserves all extended metadata
- **YouTubeProxyHandler**: Added debug logging to previously silent catch blocks
- **YouTubeTrack**: Added debug logging to proxy stream and mirror search failures
- **SpotifyAudioSourceManager**: Added debug logging for remote hash fetch failures
- **DeezerApiHandler**: Added debug logging for API request failures
- **InstagramApiHandler**: Improved error logging in initialization

#### Minor
- **ExtendedAudioSourceManager**: Removed unused `DataInputStream` import
