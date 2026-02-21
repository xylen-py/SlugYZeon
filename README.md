# SlugYZeoN Plugin

A custom Lavalink plugin providing additional audio sources for your music bot.

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
        - dependency: com.github.xylen-py.SlugYZeon:slugyzeon-plugin:v1.3.2
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

> **Note:** Last.fm requires a valid API key to be enabled. Get one at [last.fm/api](https://www.last.fm/api/account/create).

## Build

```bash
./gradlew clean build
```

The plugin JAR will be in `plugin/build/libs/`.
