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
```

> **Note:** Last.fm requires a valid API key. Get one at [last.fm/api](https://www.last.fm/api/account/create).

---

## Usage

### Gaana

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

### Amazon Music

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

### Instagram

```bash
# Post URL
GET /v4/loadtracks?identifier=https://www.instagram.com/p/ABC123/

# Reel URL
GET /v4/loadtracks?identifier=https://www.instagram.com/reel/ABC123/

# Audio Page URL
GET /v4/loadtracks?identifier=https://www.instagram.com/reels/audio/123456789/
```

### Last.fm

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

### Pandora

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

---

## Response Examples

### Track Response (Gaana)

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

### Track Response (Amazon Music)

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

### Playlist Response (Album)

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

### Instagram Track Response

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

---

## Build

```bash
./gradlew clean build
```

---

## License

This project is licensed under the [MIT License](LICENSE).
