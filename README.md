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
  <i>multi-source lavalink plugin — 6 audio sources, zero rate limits, zero credentials needed</i>
</p>

<img src="https://img.shields.io/badge/Java-17+-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java" />
<img src="https://img.shields.io/badge/Lavalink-4.0+-7289DA?style=for-the-badge" alt="Lavalink" />
<img src="https://img.shields.io/badge/License-MIT-764ba2?style=for-the-badge" alt="License" />
<img src="https://img.shields.io/badge/Sources-7-667eea?style=for-the-badge" alt="Sources" />
<img src="https://img.shields.io/badge/HTTP_Deps-Zero-00C853?style=for-the-badge" alt="Zero Deps" />

<br><br>

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=14&pause=1000&color=667EEA&background=0D1117&vCenter=true&center=true&width=500&lines=>+slugyzeon+v4.0.0+loaded;>+spotify+gql+initialized;>+youtube+fallback+ready;>+6+sources+registered;>+zero+rate+limits!" alt="Typing SVG" />

</div>

<br>

---

<b>about</b>

<br>

slugyzeon is a production-grade lavalink plugin providing **6 audio sources** for discord music bots . built with java's native `HttpClient`, zero external http dependencies, and spotify's internal graphql api for zero rate limits .

<br>

&nbsp;›&nbsp; **6** audio sources in one plugin<br>
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

<br>

---

<b>architecture</b>

<br><br>

```
user request
    |
    v
SlugYZeonPlugin (spring @service)
    |
    +-- source manager registered?
    |       |
    |       v
    +-- spotify ─── GQL API (api-partner.spotify.com)
    |       |           └── TOTP token (base32 nuance + server time sync)
    |       |           └── persisted query hashes (remote-loaded, auto-updated)
    |       |           └── spclient metadata → free ISRC for every track
    |       |
    +-- YouTube ─── wraps youtube-plugin (enhancer, NOT standalone)
    |       |           └── delegates loadItem() to youtube-plugin
    |       |           └── wraps tracks with direct scraper fallback on playback failure
    |       |           └── fallback chain: watch page scrape → innertube API → mirror search
    |       |
    +-- gaana ───── external api → mirror resolve
    +-- amazon ──── csrf scrape → mirror resolve
    +-- instagram ─ graphql (xdt_shortcode_media) → native mp4
    +-- pandora ──── token api / anon login → mirror resolve
    |
    v
mirror system (ISRC-first → query fallback)
    |
    v
audio playback
```

<br>

---

<b>how spotify works — zero credentials</b>

<br><br>

```
1. fetch nuance json ─── base32-encoded TOTP secret
2. get spotify server time ─── /server-time endpoint
3. generate RFC 6238 TOTP ─── 30s window, sha1, 6 digits
4. exchange TOTP for access token ─── /api/token
5. call GraphQL API ─── api-partner.spotify.com/pathfinder/v2/query
```

> no `clientId`, no `clientSecret`, no `sp_dc` cookie needed . it just works .

<br>

---

<b>how the youtube enhancer works</b>

<br><br>

```
youtube track requested (ytsearch:, URL, etc.)
    |
    v
youtube-plugin loads track (all clients: WEB, ANDROID, iOS, TV)
    |
    v
Plugin wraps track in YouTubeTrack
    |
    v
playback starts:
    |
    +── youtube-plugin plays → SUCCESS (normal playback)
    |
    +── youtube-plugin fails (login required, 403, bot detection)
            |
            v
        Plugin direct scraper fallback:
            |
            +── scrape watch page (extract ytInitialPlayerResponse)
            |       |
            |       +── stream MP4/WebM audio → SUCCESS
            |
            +── try innertube clients (WEB_REMIX, TVHTML5)
            |       |
            |       +── stream MP4/WebM audio → SUCCESS
            |
            +── mirror search (scsearch, etc.)
                    |
                    +── play first match from other source → SUCCESS
                    |
                    +── all exhausted → throw FriendlyException
```

> The enhancer only activates when the youtube-plugin FAILS . normal playback is untouched . requires `youtube-plugin` to be loaded .

<br>

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
      tokenApiUrl: "https://get.1lucas1apk.fun/pandora/gettoken"
      csrfToken: ""
      preferTokenApi: true
      searchLimit: 6
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
    tokenApiUrl: "https://get.1lucas1apk.fun/pandora/gettoken"
    csrfToken: ""
    preferTokenApi: true
    searchLimit: 6
  ```
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

<br>

---

<b>features</b>

<br><br>

<details>
  <summary><b>&nbsp;›&nbsp; mirror system</b></summary>
  <br>
  &nbsp;›&nbsp; <b>ISRC-first</b> resolution for highest accuracy<br>
  &nbsp;›&nbsp; automatic <b>query fallback</b> (artist + title search)<br>
  &nbsp;›&nbsp; configurable provider chain<br>
  &nbsp;›&nbsp; works across all mirrored sources (spotify, gaana, amazon, pandora)
</details>

<details>
  <summary><b>&nbsp;›&nbsp; spotify graphql api</b></summary>
  <br>
  &nbsp;›&nbsp; uses <code>api-partner.spotify.com/pathfinder/v2/query</code><br>
  &nbsp;›&nbsp; persisted query hashes for <b>search, track, album, playlist, artist</b><br>
  &nbsp;›&nbsp; <b>remote hash loading</b> — GQL hashes fetched from remote URL, always up to date<br>
  &nbsp;›&nbsp; <b>300 tracks</b> per album in single call (vs 50 with REST)<br>
  &nbsp;›&nbsp; <b>343 tracks</b> per playlist in single call<br>
  &nbsp;›&nbsp; multi-path artist name + duration resolution
</details>

<details>
  <summary><b>&nbsp;›&nbsp; youtube direct playback</b></summary>
  <br>
  &nbsp;›&nbsp; <b>direct watch page scraping</b> bypassing consent/age walls<br>
  &nbsp;›&nbsp; <b>innertube API fallback</b> (WEB_REMIX, TVHTML5) without credentials<br>
  &nbsp;›&nbsp; last-resort mirror search on youtube music / soundcloud<br>
  &nbsp;›&nbsp; handles both <b>MP4</b> and <b>WebM</b> audio containers natively<br>
  &nbsp;›&nbsp; zero dependency on third party proxies or instances
</details>

<details>
  <summary><b>&nbsp;›&nbsp; instagram native playback</b></summary>
  <br>
  &nbsp;›&nbsp; auto-scrapes <b>CSRF, AppID, LSD</b> tokens from homepage<br>
  &nbsp;›&nbsp; auto-<b>reinitializes</b> on token expiry<br>
  &nbsp;›&nbsp; supports <b>posts, reels, audio pages</b><br>
  &nbsp;›&nbsp; DASH manifest parsing for music assets<br>
  &nbsp;›&nbsp; carousel (sidecar) support
</details>

<details>
  <summary><b>&nbsp;›&nbsp; rich metadata</b></summary>
  <br>
  &nbsp;›&nbsp; album name, album url, artist url, artist artwork<br>
  &nbsp;›&nbsp; <b>ISRC codes</b> attached to every track for precise mirroring<br>
  &nbsp;›&nbsp; extended playlists with type enum (ALBUM, PLAYLIST, ARTIST, RECOMMENDATIONS)<br>
  &nbsp;›&nbsp; preview url for 30s clips<br>
  &nbsp;›&nbsp; artwork upscaling
</details>


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

licensed under the **MIT license** .

&nbsp;›&nbsp; you **can** use, modify, and distribute this software<br>
&nbsp;›&nbsp; you **can** use it in commercial projects<br>
&nbsp;›&nbsp; you **must** include the license notice

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