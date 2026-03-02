# SlugYZeoN Plugin

[![MIT License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-blue)](https://openjdk.org/)
[![Lavalink](https://img.shields.io/badge/Lavalink-4.0+-blue)](https://github.com/lavalink-devs/Lavalink)

A production-quality **Lavalink plugin** providing additional audio sources for your music bot. Built with a mirror system for metadata-based playback, Java's built-in HttpClient, and zero external HTTP dependencies.

> **Educational & Research Purpose Only**: This project is created **solely for educational and research purposes**. It is a learning project to understand audio streaming, API development, and Lavalink plugin architecture. Use responsibly and respect each platform's terms of service. The authors are not responsible for any misuse of this project.

---

## Sources

| Source | Prefix | URL Support | Playback |
|---|---|---|---|
| **Gaana** | `gnsearch:` / `gnrec:` | Songs, Albums, Playlists, Artists | Mirrored |
| **Amazon Music** | `azsearch:` | Tracks, Albums, Playlists, Artists | Mirrored |
| **Instagram** | - | Posts, Reels, Audio Pages | Native (MP4 CDN) |
| **Last.fm** | `lfsearch:` / `lfrec:` | Tracks, Artists, Albums | Mirrored |
| **Pandora** | `pdsearch:` / `pdrec:` | Tracks, Albums, Playlists, Artists, Stations | Mirrored |
| **Spotify** | `spsearch:` / `sprec:` | Tracks, Albums, Playlists, Artists | Mirrored |

---

## Features

- **Mirror System** — ISRC-first resolution with automatic YouTube fallback
- **Rich Metadata** — Album name/URL, artist URL/artwork, playlist type, artwork upscaling
- **Extended Playlists** — Type enum (ALBUM, PLAYLIST, ARTIST, SEARCH, etc.), artwork, author, total tracks
- **Zero External HTTP Deps** — Uses Java's built-in `HttpClient` for all API calls
- **Thread-Safe Caching** — ReentrantLock based config/CSRF caching with auto invalidation
- **Retry Logic** — Amazon Music CSRF config retries up to 3 times, Instagram auto-reinitializes
- **FriendlyException** — Clean error propagation to Lavalink clients

---

## Installation

Add to your Lavalink `application.yml`:

```yaml
lavalink:
    plugins:
        - dependency: "com.github.xylen-py.SlugYZeon:slugyzeon-plugin:VERSION"
          repository: https://jitpack.io
          snapshot: false
```

---

## Configuration

```yaml
plugins:
  slugyzeon:
    sources:
      gaana: true
      amazonmusic: true
      instagram: true
      lastfm: true
      pandora: true
      spotify: false
    gaana:
      apiUrl: "https://gaana-plugin-api.vercel.app/api"
      streamQuality: "high"
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
      searchLimit: 25
    amazonmusic:
      countryCode: "IN"
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
    lastfm:
      apiKey: "YOUR_LASTFM_API_KEY"
      searchLimit: 10
      albumLoadLimit: 50
      artistLoadLimit: 10
    pandora:
      tokenApiUrl: "https://get.1lucas1apk.fun/pandora/gettoken"
      csrfToken: ""
      preferTokenApi: true
      searchLimit: 6
    spotify:
      clientId: ""
      clientSecret: ""
      spDc: ""
      countryCode: "US"
      customTokenEndpoint: "https://spotify-gettoken.vercel.app/api/token"
      playlistLoadLimit: 6
      albumLoadLimit: 6
      resolveArtistsInSearch: true
      localFiles: false
```

<details>
<summary>Pandora Configuration</summary>

| Option | Default | Description |
|---|---|---|
| `tokenApiUrl` | `https://get.1lucas1apk.fun/pandora/gettoken` | Custom token API endpoint for Pandora auth |
| `csrfToken` | `""` | Optional pre-configured CSRF token. If empty, fetched automatically |
| `preferTokenApi` | `true` | When `true`, token API is tried first; when `false`, anonymous login is tried first |
| `searchLimit` | `6` | Max search results returned |

Pandora uses a two-tier token strategy: the token API endpoint provides quick auth, while anonymous login serves as a fallback.

</details>

<details>
<summary>Spotify Configuration</summary>

| Option | Default | Description |
|---|---|---|
| `clientId` | `""` | Optional Spotify OAuth client ID |
| `clientSecret` | `""` | Optional Spotify OAuth client secret |
| `spDc` | `""` | Optional `sp_dc` cookie for account-level features |
| `countryCode` | `US` | Country code for regional content / artist top tracks |
| `customTokenEndpoint` | `""` | Custom token API URL (overrides TOTP generation) |
| `playlistLoadLimit` | `6` | Max playlist pages to load (100 tracks per page) |
| `albumLoadLimit` | `6` | Max album pages to load (50 tracks per page) |
| `resolveArtistsInSearch` | `true` | Batch-fetch artist images when searching tracks |
| `localFiles` | `false` | Include local file tracks from playlists |

**Token Priority:**
1. **Client Credentials** — If `clientId` and `clientSecret` are set, OAuth token is used first
2. **Custom Token API** — If `customTokenEndpoint` is set, that endpoint is called
3. **TOTP Generation** — Scrapes Spotify homepage JS for secret, generates HMAC-SHA1 TOTP
4. **Built-in Free API** — Falls back to `https://spotify-gettoken.vercel.app/api/token`

> **Note:** Spotify works entirely free without any credentials. All config fields are optional. Set `spotify: true` under sources to enable it.

</details>

---

## Usage

<details>
<summary>Gaana</summary>

```bash
# Search
GET /v4/loadtracks?identifier=gnsearch:Tum Hi Ho

# Recommendations
GET /v4/loadtracks?identifier=gnrec:bollywood

# Song URL
GET /v4/loadtracks?identifier=https://gaana.com/song/tum-hi-ho

# Album URL
GET /v4/loadtracks?identifier=https://gaana.com/album/aashiqui-2

# Playlist URL
GET /v4/loadtracks?identifier=https://gaana.com/playlist/gaana-dj-hindi-top-50-1

# Artist URL
GET /v4/loadtracks?identifier=https://gaana.com/artist/arijit-singh
```

</details>

<details>
<summary>Amazon Music</summary>

```bash
# Search
GET /v4/loadtracks?identifier=azsearch:Shape of You

# Track URL
GET /v4/loadtracks?identifier=https://music.amazon.com/tracks/B07QGZ1GJ6

# Album URL
GET /v4/loadtracks?identifier=https://music.amazon.com/albums/B07QGZX5BX

# Playlist URL
GET /v4/loadtracks?identifier=https://music.amazon.com/playlists/B07QGZ1GJ6

# Artist URL
GET /v4/loadtracks?identifier=https://music.amazon.com/artists/B001GBY2LE
```

</details>

<details>
<summary>Instagram</summary>

```bash
# Post URL
GET /v4/loadtracks?identifier=https://www.instagram.com/p/ABC123/

# Reel URL
GET /v4/loadtracks?identifier=https://www.instagram.com/reel/ABC123/

# Audio Page URL
GET /v4/loadtracks?identifier=https://www.instagram.com/reels/audio/123456789/
```

</details>

<details>
<summary>Last.fm</summary>

```bash
# Search
GET /v4/loadtracks?identifier=lfsearch:Creep Radiohead

# Recommendations (similar tracks)
GET /v4/loadtracks?identifier=lfrec:Radiohead - Creep

# Track URL
GET /v4/loadtracks?identifier=https://www.last.fm/music/Radiohead/_/Creep

# Album URL
GET /v4/loadtracks?identifier=https://www.last.fm/music/Radiohead/OK+Computer

# Artist URL (top tracks)
GET /v4/loadtracks?identifier=https://www.last.fm/music/Radiohead
```

</details>

<details>
<summary>Pandora</summary>

```bash
# Search
GET /v4/loadtracks?identifier=pdsearch:Bohemian Rhapsody

# Recommendations (similar tracks)
GET /v4/loadtracks?identifier=pdrec:TRxxxxxx

# Track URL
GET /v4/loadtracks?identifier=https://www.pandora.com/artist/queen/bohemian-rhapsody/TRxxxxxx

# Album URL
GET /v4/loadtracks?identifier=https://www.pandora.com/artist/queen/a-night-at-the-opera/ALxxxxxx

# Playlist URL
GET /v4/loadtracks?identifier=https://www.pandora.com/playlist/PLxxxxxx

# Station URL
GET /v4/loadtracks?identifier=https://www.pandora.com/station/STxxxxxx

# Artist URL
GET /v4/loadtracks?identifier=https://www.pandora.com/artist/queen/ARxxxxxx
```

</details>

<details>
<summary>Spotify</summary>

```bash
# Search
GET /v4/loadtracks?identifier=spsearch:Shape of You

# Recommendations
GET /v4/loadtracks?identifier=sprec:seed_tracks=trackId&limit=10

# Track URL
GET /v4/loadtracks?identifier=https://open.spotify.com/track/7qiZfU4dY1lWllzX7mPBI3

# Album URL
GET /v4/loadtracks?identifier=https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX

# Playlist URL
GET /v4/loadtracks?identifier=https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M

# Artist URL (top tracks)
GET /v4/loadtracks?identifier=https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ
```

</details>

---

## Response Examples

<details>
<summary>Track Response (Gaana)</summary>

```json
{
  "loadType": "track",
  "data": {
    "encoded": "QAABJgMAClR...",
    "info": {
      "identifier": "tum-hi-ho",
      "isSeekable": false,
      "author": "Arijit Singh",
      "length": 265000,
      "isStream": false,
      "position": 0,
      "title": "Tum Hi Ho",
      "uri": "https://gaana.com/song/tum-hi-ho",
      "artworkUrl": "https://a10.gaanacdn.com/gn_img/albums/size_l_1529306478.webp",
      "isrc": "INS041315026",
      "sourceName": "gaana"
    },
    "pluginInfo": {
      "albumName": "Aashiqui 2",
      "albumUrl": "https://gaana.com/album/aashiqui-2",
      "artistUrl": "https://gaana.com/artist/arijit-singh",
      "artistArtworkUrl": "https://a10.gaanacdn.com/gn_img/artists/size_m_1529306478.webp"
    }
  }
}
```

</details>

<details>
<summary>Track Response (Amazon Music)</summary>

```json
{
  "loadType": "track",
  "data": {
    "encoded": "QAABJgMAClR...",
    "info": {
      "identifier": "B07QGZ1GJ6",
      "isSeekable": false,
      "author": "Ed Sheeran",
      "length": 233000,
      "isStream": false,
      "position": 0,
      "title": "Shape of You",
      "uri": "https://music.amazon.com/tracks/B07QGZ1GJ6",
      "artworkUrl": "https://m.media-amazon.com/images/I/61k3Bx7AB5L._SL500_.jpg",
      "isrc": "GBAHS1600463",
      "sourceName": "amazonmusic"
    },
    "pluginInfo": {
      "albumName": "Divide (Deluxe)",
      "albumUrl": "https://music.amazon.com/albums/B07QGZX5BX",
      "artistUrl": "https://music.amazon.com/artists/B001GBY2LE"
    }
  }
}
```

</details>

<details>
<summary>Track Response (Spotify)</summary>

```json
{
  "loadType": "track",
  "data": {
    "encoded": "QAABJgMAClR...",
    "info": {
      "identifier": "7qiZfU4dY1lWllzX7mPBI3",
      "isSeekable": false,
      "author": "Ed Sheeran",
      "length": 233713,
      "isStream": false,
      "position": 0,
      "title": "Shape of You",
      "uri": "https://open.spotify.com/track/7qiZfU4dY1lWllzX7mPBI3",
      "artworkUrl": "https://i.scdn.co/image/ab67616d0000b273ba5db46f4b838ef6027e6f96",
      "isrc": "GBAHS1600463",
      "sourceName": "spotify"
    },
    "pluginInfo": {
      "albumName": "÷ (Deluxe)",
      "albumUrl": "https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX",
      "artistUrl": "https://open.spotify.com/artist/6eUKZXaKkcviH0Ku9w2n3V",
      "artistArtworkUrl": null
    }
  }
}
```

</details>

<details>
<summary>Playlist Response (Album)</summary>

```json
{
  "loadType": "playlist",
  "data": {
    "info": {
      "name": "Aashiqui 2",
      "selectedTrack": -1
    },
    "pluginInfo": {
      "type": "ALBUM",
      "url": "https://gaana.com/album/aashiqui-2",
      "artworkUrl": "https://a10.gaanacdn.com/gn_img/albums/size_l_1529306478.webp",
      "author": "Various Artists",
      "totalTracks": 12
    },
    "tracks": [
      {
        "encoded": "QAABJgMAClR...",
        "info": {
          "identifier": "tum-hi-ho",
          "author": "Arijit Singh",
          "length": 265000,
          "title": "Tum Hi Ho",
          "uri": "https://gaana.com/song/tum-hi-ho",
          "artworkUrl": "https://a10.gaanacdn.com/gn_img/albums/size_l_1529306478.webp",
          "sourceName": "gaana"
        }
      }
    ]
  }
}
```

</details>

<details>
<summary>Track Response (Instagram)</summary>

```json
{
  "loadType": "track",
  "data": {
    "encoded": "QAABJgMAClR...",
    "info": {
      "identifier": "CxYZ123abc",
      "isSeekable": false,
      "author": "username",
      "length": 30000,
      "isStream": false,
      "position": 0,
      "title": "Instagram Reel Caption",
      "uri": "https://www.instagram.com/reel/CxYZ123abc/",
      "artworkUrl": "https://scontent.cdninstagram.com/v/t51.2885-15/image.jpg",
      "sourceName": "instagram"
    }
  }
}
```

</details>

---

## Build

```bash
./gradlew clean build
```

The built plugin JAR will be in `plugin/build/libs/`.

---

## License

This project is licensed under the [MIT License](LICENSE).
