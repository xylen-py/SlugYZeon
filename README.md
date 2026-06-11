<div align="center">

```
     ███████╗    ██╗       ██╗   ██╗     ██████╗     ██╗   ██╗    ███████╗    ███████╗     ██████╗     ███╗   ██╗
     ██╔════╝    ██║       ██║   ██║    ██╔════╝     ╚██╗ ██╔╝    ╚══███╔╝    ██╔════╝    ██╔═══██╗    ████╗  ██║
     ███████╗    ██║       ██║   ██║    ██║  ███╗     ╚████╔╝       ███╔╝     █████╗      ██║   ██║    ██╔██╗ ██║
     ╚════██║    ██║       ██║   ██║    ██║   ██║      ╚██╔╝       ███╔╝      ██╔══╝      ██║   ██║    ██║╚██╗██║
     ███████║    ███████╗  ╚██████╔╝    ╚██████╔╝       ██║       ███████╗    ███████╗    ╚██████╔╝    ██║ ╚████║
     ╚══════╝    ╚══════╝   ╚═════╝      ╚═════╝        ╚═╝       ╚══════╝    ╚══════╝     ╚═════╝     ╚═╝  ╚═══╝
```

<h2 align="center">
  <span>「 S L U G Y Z E O N 」</span>
</h2>

<p align="center">
  <i>multi-source lavalink plugin — 7 audio sources, zero rate limits, zero credentials needed</i>
</p>

<img src="https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />
<img src="https://img.shields.io/badge/Lavalink-4.0+-7289DA?style=for-the-badge" alt="Lavalink" />
<img src="https://img.shields.io/badge/License-Apache_2.0-764ba2?style=for-the-badge" alt="License" />
<img src="https://img.shields.io/badge/Sources-8-667eea?style=for-the-badge" alt="Sources" />
<img src="https://img.shields.io/badge/HTTP_Deps-Zero-00C853?style=for-the-badge" alt="Zero Deps" />

<br><br>

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=14&pause=1000&color=667EEA&background=0D1117&vCenter=true&center=true&width=500&lines=>+slugyzeon+v4.0.0+loaded;>+spotify+gql+initialized;>+youtube+fallback+ready;> +deezer+api+ready;> +8+sources+registered;>+zero+rate+limits!" alt="Typing SVG" />

</div>

<br>

---

<b>credits & special thanks</b>

<br>

**acknowledgments**<br>
&nbsp;›&nbsp; **[PerformanC / NodeLink](https://github.com/PerformanC/NodeLink)** — for the Amazon Music `BOT_UA` test key.<br>
&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;*(Note: This plugin does not use any code from NodeLink and is built entirely on the LavaSrc architecture. The user agent was a leftover test component that remained in the production code, and proper credit is provided here.)*<br>

<br>

**credits**<br>
&nbsp;›&nbsp; **[xylen-py](https://github.com/xylen-py)** — for both plugin apis & sources for deezer & gaana<br>
&nbsp;›&nbsp; **[saraansx](https://github.com/saraansx)** — for help with Spotify integration<br>

<br>

**special thanks**<br>
&nbsp;›&nbsp; **[lavalink-devs](https://github.com/lavalink-devs/lavalink-plugin-template)** — for providing the official Lavalink plugin template<br>
&nbsp;›&nbsp; **[topi314 / LavaSrc](https://github.com/topi314/LavaSrc)** — for the foundational mirroring architecture and code structure<br>
&nbsp;›&nbsp; **[bongo-devs / jiosaavn-plugin](https://github.com/bongo-devs/jiosaavn-plugin)** — for the architecture of external proxy API sources<br>
&nbsp;›&nbsp; **[notdeltaxd](https://github.com/notdeltaxd)** & **[1Lucas1apk](https://github.com/1Lucas1apk)** — for the pandora source token and api architecture

<br>

---

<b>about</b>

<br>

slugyzeon is a production-grade lavalink plugin providing **7 audio sources** for discord music bots . built with java's native `HttpClient`, zero external http dependencies, and spotify's internal graphql api for zero rate limits .

<br>

&nbsp;›&nbsp; **7** audio sources in one plugin<br>
&nbsp;›&nbsp; **0** external http dependencies<br>
&nbsp;›&nbsp; **0** credentials needed for spotify<br>
&nbsp;›&nbsp; **GQL** api bypasses spotify rate limits<br>
&nbsp;›&nbsp; **TOTP** token generation — works free forever<br>
&nbsp;›&nbsp; **YouTube Enhancer** — wraps youtube-plugin with direct scraper fallback on failure

<br>

---

<b>sources</b>

<br><br>

| source | prefix | url support | isrc mirroring | playback |
|--------|--------|-------------|----------------|----------|
| **spotify** | `spsearch:` / `sprec:` | tracks, albums, playlists, artists | yes (spclient) | mirrored |
| **youtube** | wraps youtube-plugin | enhances all youtube playback | — | direct scraper fallback |
| **gaana** | `gnsearch:` / `gnrec:` | songs, albums, playlists, artists | yes | mirrored |
| **amazon music** | `azsearch:` | tracks, albums, playlists, artists | partial | mirrored |
| **instagram** | — | posts, reels, audio pages | — | native (mp4 cdn) |
| **pandora** | `pdsearch:` / `pdrec:` | tracks, albums, playlists, artists, stations | yes | mirrored |
| **deezer** | `dzsearch:` / `dzrec:` | tracks, albums, playlists, artists | — | native stream (decrypted) |

<br>

---

> **Note:** The YouTube enhancer requires the official [Lavalink YouTube Source Plugin](https://github.com/lavalink-devs/youtube-source) to be loaded in order to function.

---

<b>installation</b>

<br><br>

```yaml
lavalink:
    plugins:
        - dependency: "com.github.xylen-py.SlugYZeon:slugyzeon-plugin:VERSION"
          repository: https://jitpack.io
          snapshot: false
```

<br>

---

<b>configuration</b>

<br><br>

```yaml
plugins:
  slugyzeon:
    sources:
      gaana: false
      amazonmusic: false
      instagram: false
      pandora: false
      spotify: false
      youtube: false
      deezer: false
    spotify:
      clientId: ""            # optional — not needed for free usage
      clientSecret: ""        # optional — not needed for free usage
      spDc: ""                # optional — sp_dc cookie for account features
      countryCode: "US"
      nuanceUrl: ""           # custom nuance json url (uses built-in by default)
      playlistLoadLimit: 6
      albumLoadLimit: 6
      resolveArtistsInSearch: true
      localFiles: false
    youtube:
      mirrorProviders:
        - "ytmsearch:%QUERY%"
        - "scsearch:%QUERY%"
    gaana:
      apiUrl: "https://gaana-plugin-api.vercel.app/api"
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
      searchLimit: 25
    amazonmusic:
      countryCode: "IN"
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
    pandora:
      tokenApiUrl: ""
      csrfToken: ""
      preferTokenApi: true
      searchLimit: 6
    deezer:
      apiUrl: "https://deezer-plugin-api.vercel.app/api"
      playlistLoadLimit: 50
      albumLoadLimit: 50
      artistLoadLimit: 50
      searchLimit: 25
```

<details>
  <summary><b>&nbsp;›&nbsp; spotify config</b></summary>
  <br>

  ```yaml
  spotify:
    clientId: ""            # optional — not needed
    clientSecret: ""        # optional — not needed
    spDc: ""                # optional — sp_dc cookie
    countryCode: "US"
    nuanceUrl: ""           # custom nuance endpoint
    playlistLoadLimit: 6
    albumLoadLimit: 6
    resolveArtistsInSearch: true
    localFiles: false
  ```

  | field | default | description |
  |-------|---------|-------------|
  | `clientId` / `clientSecret` | `""` | optional oauth credentials . not needed — totp works free |
  | `spDc` | `""` | optional `sp_dc` cookie for account-level features |
  | `countryCode` | `US` | regional content / artist top tracks |
  | `nuanceUrl` | `""` | custom nuance json url . uses built-in url by default |

  **token priority:**
  1. **TOTP generation** — base32 secret from nuance, synced with spotify server time, RFC 6238
  2. **client credentials** — if `clientId` + `clientSecret` set, used for REST API calls

  **ISRC resolution:**
  1. **spclient metadata** — `spclient.wg.spotify.com/metadata/4/track/{hexId}` → free, no credentials
  2. **GQL externalIds** — fallback if spclient returns it
  3. **query fallback** — `ytsearch:Title Artist` if no ISRC available

  > everything is optional . spotify works entirely free without any credentials .
</details>

<details>
  <summary><b>&nbsp;›&nbsp; youtube fallback config</b></summary>
  <br>

  ```yaml
  youtube:
    mirrorProviders:
      - "ytmsearch:%QUERY%"
      - "scsearch:%QUERY%"
  ```

  | field | default | description |
  |-------|---------|-------------|
  | `mirrorProviders` | ytmsearch, scsearch | last-resort search on other sources |

  > requires the standard `youtube-plugin` to be loaded . it acts as a fallback when the original plugin fails .
</details>

<details>
  <summary><b>&nbsp;›&nbsp; gaana config</b></summary>
  <br>

  ```yaml
  gaana:
    apiUrl: "https://gaana-plugin-api.vercel.app/api"
    playlistLoadLimit: 50
    albumLoadLimit: 50
    artistLoadLimit: 50
    searchLimit: 25
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; amazon music config</b></summary>
  <br>

  ```yaml
  amazonmusic:
    countryCode: "IN"
    playlistLoadLimit: 50
    albumLoadLimit: 50
    artistLoadLimit: 50
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; pandora config</b></summary>
  <br>

  ```yaml
  pandora:
    tokenApiUrl: ""
    csrfToken: ""
    preferTokenApi: true
    searchLimit: 6
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; deezer config</b></summary>
  <br>

  ```yaml
  deezer:
    apiUrl: "https://deezer-plugin-api.vercel.app/api"
    playlistLoadLimit: 50
    albumLoadLimit: 50
    artistLoadLimit: 50
    searchLimit: 25
    quality: "128"      # 128, 320, or FLAC
  ```

  | field | default | description |
  |-------|---------|-------------|
  | `apiUrl` | `https://deezer-plugin-api.vercel.app/api` | deezer plugin api base url |
  | `playlistLoadLimit` | `50` | max tracks per playlist load |
  | `albumLoadLimit` | `50` | max tracks per album load |
  | `artistLoadLimit` | `50` | max tracks per artist load |
  | `searchLimit` | `25` | max search results |
  | `quality` | `128` | stream quality (`128`, `320`, or `FLAC`) |
</details>

<br>

---

<b>usage</b>

<br><br>

<details>
  <summary><b>&nbsp;›&nbsp; spotify</b></summary>
  <br>

  ```bash
  # search
  GET /v4/loadtracks?identifier=spsearch:Shape of You

  # recommendations
  GET /v4/loadtracks?identifier=sprec:seed_tracks=trackId&limit=10

  # track url
  GET /v4/loadtracks?identifier=https://open.spotify.com/track/7qiZfU4dY1lWllzX7mPBI3

  # album url
  GET /v4/loadtracks?identifier=https://open.spotify.com/album/1ATL5GLyefJaxhQzSPVrLX

  # playlist url
  GET /v4/loadtracks?identifier=https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M

  # artist url (top tracks)
  GET /v4/loadtracks?identifier=https://open.spotify.com/artist/1Xyo4u8uXC1ZmMpatF05PJ
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; youtube fallback</b></summary>
  <br>

  ```bash
  # search (replaces ytsearch when youtube enabled)
  GET /v4/loadtracks?identifier=ytsearch:brown munde

  # youtube music search
  GET /v4/loadtracks?identifier=ytmsearch:brown munde

  # video url
  GET /v4/loadtracks?identifier=https://www.youtube.com/watch?v=FM2ykrYbzqg

  # shorts url
  GET /v4/loadtracks?identifier=https://www.youtube.com/shorts/ABC123

  # youtu.be url
  GET /v4/loadtracks?identifier=https://youtu.be/FM2ykrYbzqg
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; gaana</b></summary>
  <br>

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
</details>

<details>
  <summary><b>&nbsp;›&nbsp; amazon music</b></summary>
  <br>

  ```bash
  # search
  GET /v4/loadtracks?identifier=azsearch:Shape of You

  # url support
  GET /v4/loadtracks?identifier=https://music.amazon.com/tracks/B07QGZ1GJ6
  GET /v4/loadtracks?identifier=https://music.amazon.com/albums/B07QGZX5BX
  GET /v4/loadtracks?identifier=https://music.amazon.com/playlists/B07QGZ1GJ6
  GET /v4/loadtracks?identifier=https://music.amazon.com/artists/B001GBY2LE
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; instagram</b></summary>
  <br>

  ```bash
  # post
  GET /v4/loadtracks?identifier=https://www.instagram.com/p/ABC123/

  # reel
  GET /v4/loadtracks?identifier=https://www.instagram.com/reel/ABC123/

  # audio page
  GET /v4/loadtracks?identifier=https://www.instagram.com/reels/audio/123456789/
  ```
</details>

<details>
  <summary><b>&nbsp;›&nbsp; pandora</b></summary>
  <br>

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
</details>

<details>
  <summary><b>&nbsp;›&nbsp; deezer</b></summary>
  <br>

  ```bash
  # search
  GET /v4/loadtracks?identifier=dzsearch:Starboy

  # recommendations (charts)
  GET /v4/loadtracks?identifier=dzrec:top

  # track url
  GET /v4/loadtracks?identifier=https://www.deezer.com/track/142734142

  # album url
  GET /v4/loadtracks?identifier=https://www.deezer.com/album/14279764

  # playlist url
  GET /v4/loadtracks?identifier=https://www.deezer.com/playlist/53362031

  # artist url (top tracks)
  GET /v4/loadtracks?identifier=https://www.deezer.com/artist/4050205
  ```
</details>

<br>

---

<b>features</b>

<br><br>

&nbsp;›&nbsp; **mirror system** — ISRC-first resolution with automatic query fallback for mirrored sources<br>
&nbsp;›&nbsp; **spotify graphql api** — 0 credentials, remote hash loading, up to 343 tracks/call, bypasses rate limits<br>
&nbsp;›&nbsp; **youtube enhancer** — direct watch page scraping, innertube fallback, bypassing age/consent walls<br>
&nbsp;›&nbsp; **instagram native playback** — auto-scraped tokens with token rotation, supports posts, reels, audio pages<br>
&nbsp;›&nbsp; **deezer native streaming** — direct CDN playback with on-the-fly blowfish decryption<br>
&nbsp;›&nbsp; **rich metadata** — returns extended playlists, ISRC codes, album/artist URLs, and preview URLs<br>
&nbsp;›&nbsp; **zero http dependencies** — relies entirely on Java's native `HttpClient` for maximal performance<br>
&nbsp;›&nbsp; **seamless integration** — plugs directly into standard Lavalink 4.0+ via spring boot


<br>

---

<b>build</b>

<br><br>

```bash
./gradlew clean build
```

> built plugin jar in `plugin/build/libs/`

<br>

---

<b>disclaimer</b>

<br><br>

this plugin is provided for **educational and research purposes only** . it is a learning project to understand audio streaming, api development, and lavalink plugin architecture . use responsibly and respect each platform's terms of service . the authors are not responsible for any misuse .

<br>

---

<b>license</b>

<br><br>

licensed under the **Apache License 2.0** .

&nbsp;›&nbsp; you **can** use, modify, and distribute this software<br>
&nbsp;›&nbsp; you **can** use it in commercial projects<br>
&nbsp;›&nbsp; you **must** include the license notice, state changes, and provide original copyright

see [LICENSE](LICENSE) for full details .

<br>

---

<div align="center">

<br>

<b>built by <a href="https://github.com/xylen-py">xylen</a> — draxity engine</b>

<br>

`.1xylen SlugYZeon v4.0.0 - Lavalink`

<br><br>

</div>