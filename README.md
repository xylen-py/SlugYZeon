# SlugYZeoN Plugin

A custom Lavalink plugin providing additional audio sources.

## Sources

| Source | Search Prefix | Playback |
|---|---|---|
| **Gaana** | `gnsearch:` | Direct stream / YouTube Music fallback |
| **Amazon Music** | `azsearch:` | YouTube Music mirror |
| **Instagram** | `igsearch:` | Native (MP4 from CDN) |
| **Last.fm** | `lfsearch:` | YouTube Music mirror (requires API key) |

## Installation

Add to your Lavalink `application.yml`:

```yaml
lavalink:
    plugins:
        - dependency: com.github.xylen-py:SlugYZeon:1.3.2
          repository: https://jitpack.io
          snapshot: false
```

## Configuration

```yaml
plugins:
  slugyzeon:
    gaana:
      enabled: true
      apiUrl: "https://gaana.1lucas1apk.fun/api"
      streamQuality: "high"
    amazonmusic:
      enabled: true
      countryCode: "IN"
    instagram:
      enabled: true
    lastfm:
      enabled: true
      apiKey: "YOUR_LASTFM_API_KEY"
      maxSearchResults: 10
```

## Build

```bash
./gradlew clean build
```

The plugin JAR will be in `build/libs/`.
