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
<img src="https://img.shields.io/badge/License-MIT-764ba2?style=for-the-badge" alt="License" />
<img src="https://img.shields.io/badge/Sources-7-667eea?style=for-the-badge" alt="Sources" />
<img src="https://img.shields.io/badge/HTTP_Deps-Zero-00C853?style=for-the-badge" alt="Zero Deps" />

<br><br>

<img src="https://readme-typing-svg.demolab.com?font=Fira+Code&weight=600&size=14&pause=1000&color=667EEA&background=0D1117&vCenter=true&center=true&width=500&lines=>+slugyzeon+v3.0.7+loaded;>+spotify+gql+initialized;>+youtube+fallback+ready;>+7+sources+registered;>+zero+rate+limits!" alt="Typing SVG" />

</div>

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
&nbsp;›&nbsp; **SlugYTube** — wraps youtube-plugin with proxy fallback on failure

<br>

---

<b>sources</b>

<br><br>

| source | prefix | url support | playback |
|--------|--------|-------------|----------|
| **spotify** | `spsearch:` / `sprec:` | tracks, albums, playlists, artists | mirrored |
| **SlugYTube** | wraps youtube-plugin | enhances all youtube playback | proxy fallback (invidious/piped) |
| **gaana** | `gnsearch:` / `gnrec:` | songs, albums, playlists, artists | mirrored |
| **amazon music** | `azsearch:` | tracks, albums, playlists, artists | mirrored |
| **instagram** | — | posts, reels, audio pages | native (mp4 cdn) |
| **last.fm** | `lfsearch:` / `lfrec:` | tracks, artists, albums | mirrored |
| **pandora** | `pdsearch:` / `pdrec:` | tracks, albums, playlists, artists, stations | mirrored |

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
    |       |           └── persisted query hashes (search, track, album, playlist, artist)
    |       |
    +-- SlugYTube ─── wraps youtube-plugin (enhancer, NOT standalone)
    |       |           └── delegates loadItem() to youtube-plugin
    |       |           └── wraps tracks with proxy fallback on playback failure
    |       |           └── fallback chain: invidious → piped → mirror search
    |       |
    +-- gaana ───── external api → mirror resolve
    +-- amazon ──── csrf scrape → mirror resolve
    +-- instagram ─ graphql (xdt_shortcode_media) → native mp4
    +-- lastfm ──── api + html scrape → mirror resolve
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
4. exchange TOTP for access token ─── /accesstoken/plc
5. call GraphQL API ─── api-partner.spotify.com/pathfinder/v2/query
```

> no `clientId`, no `clientSecret`, no `sp_dc` cookie needed . it just works .

<br>

---

<b>how SlugYTube works — youtube enhancer</b>

<br><br>

```
youtube track requested (ytsearch:, URL, etc.)
    |
    v
youtube-plugin loads track (all clients: WEB, ANDROID, iOS, TV)
    |
    v
SlugYTube wraps track in YouTubeTrack
    |
    v
playback starts:
    |
    +── youtube-plugin plays → SUCCESS (normal playback)
    |
    +── youtube-plugin fails (login required, 403, bot detection)
            |
            v
        SlugYTube proxy fallback:
            |
            +── try invidious (5 instances, round-robin)
            |       |
            |       +── stream MP4/WebM audio → SUCCESS
            |
            +── try piped (3 instances)
            |       |
            |       +── stream MP4/WebM audio → SUCCESS
            |
            +── mirror search (scsearch, etc.)
                    |
                    +── play first match from other source → SUCCESS
                    |
                    +── all exhausted → throw FriendlyException
```

> SlugYTube only activates when the youtube-plugin FAILS . normal playback is untouched . requires `youtube-plugin` to be loaded .

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
      gaana: true
      amazonmusic: true
      instagram: true
      lastfm: true
      pandora: true
      spotify: true
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
      invidiousInstances:
        - "https://invidious.fdn.fr"
        - "https://vid.puffyan.us"
        - "https://invidious.nerdvpn.de"
        - "https://inv.nadeko.net"
        - "https://invidious.privacyredirect.com"
      pipedInstances:
        - "https://pipedapi.kavin.rocks"
        - "https://api.piped.yt"
        - "https://pipedapi.in.projectsegfau.lt"
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
  1. **client credentials** — if `clientId` + `clientSecret` set, oauth token used first
  2. **TOTP generation** — base32 secret from nuance, synced with spotify server time, RFC 6238

  > everything is optional . spotify works entirely free without any credentials .
</details>

<details>
  <summary><b>&nbsp;›&nbsp; youtube fallback config</b></summary>
  <br>

  ```yaml
  youtube:
    invidiousInstances:
      - "https://invidious.fdn.fr"
      - "https://vid.puffyan.us"
      - "https://invidious.nerdvpn.de"
      - "https://inv.nadeko.net"
      - "https://invidious.privacyredirect.com"
    pipedInstances:
      - "https://pipedapi.kavin.rocks"
      - "https://api.piped.yt"
      - "https://pipedapi.in.projectsegfau.lt"
    mirrorProviders:
      - "ytmsearch:%QUERY%"
      - "scsearch:%QUERY%"
  ```

  | field | default | description |
  |-------|---------|-------------|
  | `invidiousInstances` | 5 public instances | invidious api endpoints for stream + metadata |
  | `pipedInstances` | 3 public instances | piped api endpoints as secondary fallback |
  | `mirrorProviders` | ytmsearch, scsearch | last-resort search on other sources |

  > replaces the standard youtube-plugin . disable `youtube-plugin` when using this .
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
  <summary><b>&nbsp;›&nbsp; last.fm config</b></summary>
  <br>

  ```yaml
  lastfm:
    apiKey: "YOUR_LASTFM_API_KEY"
    searchLimit: 10
    albumLoadLimit: 50
    artistLoadLimit: 10
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
  <summary><b>&nbsp;›&nbsp; last.fm</b></summary>
  <br>

  ```bash
  # search
  GET /v4/loadtracks?identifier=lfsearch:Creep Radiohead

  # recommendations
  GET /v4/loadtracks?identifier=lfrec:Radiohead - Creep

  # url support
  GET /v4/loadtracks?identifier=https://www.last.fm/music/Radiohead/_/Creep
  GET /v4/loadtracks?identifier=https://www.last.fm/music/Radiohead/OK+Computer
  GET /v4/loadtracks?identifier=https://www.last.fm/music/Radiohead
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
  &nbsp;›&nbsp; works across all mirrored sources (spotify, gaana, amazon, lastfm, pandora)
</details>

<details>
  <summary><b>&nbsp;›&nbsp; spotify graphql api</b></summary>
  <br>
  &nbsp;›&nbsp; uses <code>api-partner.spotify.com/pathfinder/v2/query</code><br>
  &nbsp;›&nbsp; persisted query hashes for <b>search, track, album, playlist, artist</b><br>
  &nbsp;›&nbsp; <b>300 tracks</b> per album in single call (vs 50 with REST)<br>
  &nbsp;›&nbsp; <b>343 tracks</b> per playlist in single call<br>
  &nbsp;›&nbsp; multi-path artist name + duration resolution
</details>

<details>
  <summary><b>&nbsp;›&nbsp; youtube proxy playback</b></summary>
  <br>
  &nbsp;›&nbsp; <b>5 invidious</b> + <b>3 piped</b> instances with round-robin rotation<br>
  &nbsp;›&nbsp; automatic failover between proxy instances<br>
  &nbsp;›&nbsp; last-resort mirror search on youtube music / soundcloud<br>
  &nbsp;›&nbsp; handles both <b>MP4</b> and <b>WebM</b> audio containers<br>
  &nbsp;›&nbsp; no oauth, no potoken, no login needed
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

`.1xylen SlugYZeon v3 - Lavalink`

<br><br>

</div>