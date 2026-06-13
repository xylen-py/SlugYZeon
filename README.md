[![](https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://java.com)
[![](https://img.shields.io/badge/Lavalink-4.0+-7289DA?style=for-the-badge)](https://github.com/lavalink-devs/Lavalink)
[![](https://img.shields.io/badge/License-Apache_2.0-764ba2?style=for-the-badge)](LICENSE)
[![](https://img.shields.io/badge/Sources-8-667eea?style=for-the-badge)](#sources)
[![](https://img.shields.io/badge/HTTP_Deps-Zero-00C853?style=for-the-badge)](#features)

# SlugYZeon

> [!NOTE]
> Multi-source lavalink plugin featuring 7 audio sources, zero rate limits, and zero credentials needed. Built entirely with Java's native `HttpClient`.

## Summary

* [Sources](#sources)
    * [Features](#features)
    * [What is Mirroring?](#what-is-mirroring)
* [Lavalink Usage](#lavalink-usage)
    * [Configuration](#configuration)
* [Supported URLs and Queries](#supported-urls-and-queries)
* [Credits](#credits)

# Sources

| Source         | Features                                         | Playback                     |
|----------------|--------------------------------------------------|------------------------------|
| Spotify        | tracks, albums, playlists, artists               | [Mirror](#what-is-mirroring) |
| YouTube        | enhances all youtube playback                    | Direct Scraper Fallback      |
| Gaana          | songs, albums, playlists, artists                | Native Stream (HLS)          |
| Amazon Music   | tracks, albums, playlists, artists               | [Mirror](#what-is-mirroring) |
| Instagram      | posts, reels, audio pages                        | Native Stream (MP4 CDN)      |
| Pandora        | tracks, albums, playlists, artists, stations     | [Mirror](#what-is-mirroring) |
| Deezer         | tracks, albums, playlists, artists               | Native Stream (Decrypted)    |

### Features

- **Mirror System** — ISRC-first resolution with automatic query fallback for mirrored sources.
- **Spotify GraphQL API** — 0 credentials, remote hash loading, up to 343 tracks/call, bypasses rate limits.
- **YouTube Enhancer** — Direct watch page scraping, innertube fallback, bypassing age/consent walls.
- **Gaana Native Streaming** — Fully persistent HLS chunk buffering directly from Akamai CDN.
- **Instagram Native Playback** — Auto-scraped tokens with token rotation, supports posts, reels, audio pages.
- **Deezer Native Streaming** — Direct CDN playback with on-the-fly blowfish decryption.
- **Rich Metadata** — Returns extended playlists, ISRC codes, album/artist URLs, and preview URLs.
- **Zero HTTP Dependencies** — Relies entirely on Java's native `HttpClient` for maximal performance.
- **Seamless Integration** — Plugs directly into standard Lavalink 4.0+ via spring boot.

> [!IMPORTANT]
> ### What is Mirroring?
>
> Mirroring is the process of taking the metadata resolved from one source and using it to retrieve a playable `AudioTrack` from another.
>
> For example, SlugYZeon cannot directly play from Spotify, or any source marked as `Mirror` playback, so it automatically falls back to searching YouTube for the track's ISRC or Title.

## Lavalink Usage

This plugin requires Lavalink `v4` or greater.

To install this plugin, add the following into your `application.yml`:

```yaml
lavalink:
    plugins:
        - dependency: "com.github.xylen-py.SlugYZeon:slugyzeon-plugin:VERSION"
          repository: https://jitpack.io
          snapshot: false
```

### Configuration

> [!WARNING]
> The `plugins` object MUST be at the root of your YAML configuration file.

```yaml
plugins:
  slugyzeon:
    sources:
      # Set to true to enable the specific source
      gaana: false
      amazonmusic: false
      instagram: false
      pandora: false
      spotify: false
      youtube: false
      deezer: false
    spotify:
      # clientId & clientSecret are completely optional. By default, TOTP generation is used for zero rate limits.
      # clientId: "your client id"
      # clientSecret: "your client secret"
      # spDc: "your sp dc cookie" # the sp_dc cookie used for accessing account-level features. (Go to open.spotify.com, Application Tab -> Cookies -> sp_dc)
      countryCode: "US" # the country code for filtering artist top tracks
      playlistLoadLimit: 6 # The number of pages at 100 tracks each
      albumLoadLimit: 6 # The number of pages at 50 tracks each
      resolveArtistsInSearch: true # Whether to resolve artists in track search results
      localFiles: false # Enable local files support
    youtube:
      mirrorProviders: # Fallback sources to search when the official youtube-plugin fails (e.g. age-restriction)
        - "ytmsearch:%QUERY%"
        - "scsearch:%QUERY%"
    gaana:
      apiUrl: "https://gaana-plugin-api.vercel.app/api" # The API proxy required to resolve Gaana HLS manifests
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
      searchLimit: 25
    amazonmusic:
      countryCode: "IN" # Region lock code for Amazon Music
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
    pandora:
      tokenApiUrl: "" # External API URL to auto-refresh CSRF tokens (REQUIRED if hosting outside the US)
      # csrfToken: "your csrftoken" # Manual CSRF cookie from pandora.com (Only works if node is hosted inside the US)
      preferTokenApi: true # Prioritize using the token API over the manual CSRF token
      searchLimit: 6
    deezer:
      apiUrl: "https://deezer-plugin-api.vercel.app/api" # The API proxy required to bypass Deezer region-blocking
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
      searchLimit: 25
      quality: "128" # The format/quality to stream. Available: "128", "320", or "FLAC"
```

---

## Supported URLs and Queries

### Spotify

```bash
# search
GET /v4/loadtracks?identifier=spsearch:Shape of You

# recommendations
GET /v4/loadtracks?identifier=sprec:seed_tracks=trackId&limit=10

# url support
GET /v4/loadtracks?identifier=https://open.spotify.com/track/7qiZfU4dY1lWllzX7mPBI3
GET /v4/loadtracks?identifier=https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX
GET /v4/loadtracks?identifier=https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M
GET /v4/loadtracks?identifier=https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ
```

### YouTube Fallback

```bash
# search (replaces ytsearch when youtube enabled)
GET /v4/loadtracks?identifier=ytsearch:brown munde

# youtube music search
GET /v4/loadtracks?identifier=ytmsearch:brown munde

# url support
GET /v4/loadtracks?identifier=https://www.youtube.com/watch?v=FM2ykrYbzqg
GET /v4/loadtracks?identifier=https://www.youtube.com/shorts/ABC123
GET /v4/loadtracks?identifier=https://youtu.be/FM2ykrYbzqg
```

### Gaana

```bash
# search
GET /v4/loadtracks?identifier=gnsearch:Tum Hi Ho

# recommendations
GET /v4/loadtracks?identifier=gnrec:bollywood

# url support
GET /v4/loadtracks?identifier=https://gaana.com/song/tum-hi-ho
GET /v4/loadtracks?identifier=https://gaana.com/album/aashiqui-2
GET /v4/loadtracks?identifier=https://gaana.com/playlist/gaana-dj-hindi-top-50-1
GET /v4/loadtracks?identifier=https://gaana.com/artist/arijit-singh
```

### Amazon Music

```bash
# search
GET /v4/loadtracks?identifier=azsearch:Shape of You

# url support
GET /v4/loadtracks?identifier=https://music.amazon.com/tracks/B07QGZ1GJ6
GET /v4/loadtracks?identifier=https://music.amazon.com/albums/B07QGZX5BX
GET /v4/loadtracks?identifier=https://music.amazon.com/playlists/B07QGZ1GJ6
GET /v4/loadtracks?identifier=https://music.amazon.com/artists/B001GBY2LE
```

### Instagram

```bash
# post
GET /v4/loadtracks?identifier=https://www.instagram.com/p/ABC123/

# reel
GET /v4/loadtracks?identifier=https://www.instagram.com/reel/ABC123/

# audio page
GET /v4/loadtracks?identifier=https://www.instagram.com/reels/audio/123456789/
```

### Pandora

```bash
# search
GET /v4/loadtracks?identifier=pdsearch:Bohemian Rhapsody

# recommendations
GET /v4/loadtracks?identifier=pdrec:TRxxxxxx

# url support
GET /v4/loadtracks?identifier=https://www.pandora.com/artist/queen/bohemian-rhapsody/TRxxxxxx
GET /v4/loadtracks?identifier=https://www.pandora.com/artist/queen/a-night-at-the-opera/ALxxxxxx
GET /v4/loadtracks?identifier=https://www.pandora.com/playlist/PLxxxxxx
GET /v4/loadtracks?identifier=https://www.pandora.com/station/STxxxxxx
```

### Deezer

```bash
# search
GET /v4/loadtracks?identifier=dzsearch:Starboy

# recommendations (charts)
GET /v4/loadtracks?identifier=dzrec:top

# url support
GET /v4/loadtracks?identifier=https://www.deezer.com/track/142734142
GET /v4/loadtracks?identifier=https://www.deezer.com/album/14279764
GET /v4/loadtracks?identifier=https://www.deezer.com/playlist/53362031
GET /v4/loadtracks?identifier=https://www.deezer.com/artist/4050205
```

---

## Build

```bash
./gradlew clean build
```

> Built plugin jar is output to `plugin/build/libs/`

---

## Credits

- **[xylen-py](https://github.com/xylen-py)** — For both plugin APIs & sources for Deezer & Gaana.
- **[saraansx](https://github.com/saraansx)** — For help with Spotify integration.
- **[lavalink-devs](https://github.com/lavalink-devs/lavalink-plugin-template)** — For providing the official Lavalink plugin template.
- **[topi314 / LavaSrc](https://github.com/topi314/LavaSrc)** — For the foundational mirroring architecture and code structure.
- **[bongo-devs / jiosaavn-plugin](https://github.com/bongo-devs/jiosaavn-plugin)** — For the architecture of external proxy API sources.

---

## Disclaimer

This plugin is provided for **educational and research purposes only**. It is a learning project to understand audio streaming, API development, and Lavalink plugin architecture. Use responsibly and respect each platform's terms of service. The authors are not responsible for any misuse.

---

## License

Licensed under the **Apache License 2.0**.

- You **can** use, modify, and distribute this software.
- You **can** use it in commercial projects.
- You **must** include the license notice, state changes, and provide original copyright.

See [LICENSE](LICENSE) for full details.

---

<div align="center">

<br>

<b>built by <a href="https://github.com/xylen-py">xylen</a> — draxity engine</b>

<br>

`.1xylen SlugYZeon v4.0.0 - Lavalink`

<br><br>

</div>