# Video Feeds: generic RTSP / HLS / UDP feed manager (GAP-082)

> Draft PR description for `engindearing-projects/OmniTAK-Android`, from the
> `feat/video-feeds` branch of `pey3/OmniTAK-Android`. Not opened yet.

## Why

Today the only way to watch live video in OmniTAK is the UAS picture-in-picture,
which only appears while a MAVLink drone is connected, and the ONVIF screen,
which only talks SOAP to real ONVIF cameras. A common field setup has neither:
a drone app (DJI Fly, for instance) pushes RTMP into a MediaMTX that sits next
to OpenTAKServer, and the team wants to watch that stream on the map. ATAK
handles this with its video tool; OmniTAK had no equivalent (PARITY GAP-082).

## What

**Tools → Video Feeds**, plus an optional "Video" toolbar shortcut (not in the default bar).

- **Feeds on this device:** add, edit and delete. Fields: name, URL, protocol (RTSP, HLS, UDP MPEG-TS, UDP raw H.264), optional username and password, RTSP-over-TCP toggle, low-latency toggle.
  - Pasting `rtsp://user:pass@host/…` moves the credentials into the fields straight away, so the URL box never shows them.
  - An `rtmp://` URL isn't played directly. The editor offers the RTSP address MediaMTX re-serves it on (`rtsp://host:8554/path`).
- **Feeds from the TAK server:** `GET /Marti/api/video` over the existing mTLS client (`TakRestApiClient`), with `GET /Marti/vcm` (VCM XML) as fallback. Refreshes on open and on the refresh button.
- **Feeds on map markers:** any received CoT carrying `__video` (the ATAK format) or the legacy OmniTAK `<video>` element.
  - A **Watch video** button now sits at the top of the marker sheet.
  - "Save to my feeds" turns a marker feed into an editable one, for adding your own login.
- **Share to team:** sends a `b-i-v` video marker at your position (or the map centre) through the connected server.
  - Credentials and token parameters are stripped first.
  - The dialog says so, and says teammates need their own login.

**Player** (`ui/video/VideoFeedController`), shared by the full-screen view, the map PiP and the UAS PiP:

- Media3 RTSP, HLS (through OkHttp) and UDP MPEG-TS. Raw H.264 over UDP goes through the existing `RawH264UdpPlayer`.
- Renders into a `TextureView`, so `onSurfaceTextureUpdated` reports every frame that actually reaches the screen, whatever the transport. That signal drives the health state machine (`data/video/FeedHealth.kt`, pure Kotlin, unit-tested):
  - **CONNECTING**, **LIVE**, **STALE**, **RECONNECTING**, **ERROR**, with the time since the last frame.
  - LIVE turns STALE after 3 s without a new frame. The frozen picture is dimmed and labelled "FROZEN — last frame N s ago" behind an amber border.
  - After 12 s it reconnects, with exponential backoff (1 s … 30 s) and no retry limit.
  - Errors a retry can't fix park in ERROR with a Retry button: wrong password, plain HTTP blocked, unsupported codec.
  - A frozen frame never reads as live, and a phone clock change can't hide staleness (monotonic clock).
- Muted by default, keeps the screen on while visible, pauses when the app goes to the background.
- Snapshot to `Pictures/OmniTAK` (MediaStore, no permission needed).
- Stats line: resolution, fps, bitrate, and for HLS the distance behind live.
- **Full screen:** immersive, follows device rotation, 56 dp glove-sized controls in a bottom row.
  - Leaving the app during full-screen playback enters Android picture-in-picture (`supportsPictureInPicture` on `MainActivity`; no new permission).
- **Map PiP:** draggable, resizable from a corner handle, drawn above the map controls. It needs no MAVLink.
- **UAS PiP:** keeps its signature and controls, but now uses the shared player. The old badge said "LIVE" unconditionally.

## Security notes

- **Feed passwords** live in a dedicated `EncryptedSharedPreferences` file (`video_feed_credentials`), the same pattern as `SecureCredentialStore`.
  - Token query parameters such as `jwt=`, `token=` and `api_key=` are split off the URL and stored there too.
  - The DataStore JSON never carries either.
  - With no usable Keystore, secrets are kept in memory only, never written in clear. The editor says so.
- **Display:** URLs on screen go through `VideoFeedUrls.redact` (`***@host`, `jwt=***`).
  - Password fields are always masked and never pre-filled.
  - Log lines carry no URLs.
- **CoT:** every `__video` we emit uses `VideoFeedUrls.shareable`, which drops user-info and token parameters.
  - On receive, `CoTParser` strips them too, so a peer's password is never shown or persisted with the marker.
- **HLS over HTTPS:** reuses `TakTls` (new `forOkHttp`) when the host is a configured TAK server, so the server's enrollment CA pin applies, as on the CoT and Marti planes. Other hosts use system trust.
  - Plain `http://` stays blocked by the existing network security config. The editor and the player now say so instead of retrying forever.

## OpenTAKServer specifics (verified against OTS 1.7.14 source)

- **Wrong port in feed links:** `/Marti/api/video` builds HLS links from the request origin, so a client calling on 8443 gets `https://host:8443/hls/<path>/index.m3u8?jwt=…`. Stock OTS nginx only proxies `/hls/` on 443; 8443 is Marti-only and answers 400 there. `VideoFeedUrls.fixOtsHlsLink` points such links at 443.
- **Unstable uuids:** OTS mints new feed uuids on every call, so server feeds are keyed by server + alias.
- **Publishing logins:** OTS lists only streams published with an OTS login. A localhost publish doesn't appear.

## Also in this PR

- **`buildUasPliEvent`:** emits the ATAK-standard `<__video>` (with `ConnectionEntry`) instead of `<video>`, which ATAK/iTAK don't read. `UASManager` now passes the RTSP URL, so the drone marker advertises its feed (credentials stripped).
- **`rebuildEvent`:** keeps a marker's video when the marker is edited and re-sent.
- **`RawH264UdpPlayer`:** fixes a race where `stop()` could run while the reader thread was creating the MediaCodec. That leaked a codec bound to the Surface.
- **Debug builds:** use `applicationIdSuffix ".dev"` and the label "OmniTAK Dev", so testers can keep the store build installed alongside. This is a separate commit and easy to drop.

## Dependencies

- `androidx.media3:media3-exoplayer-hls:1.4.1`: Apache-2.0, same Media3 version as the existing modules.
- `androidx.media3:media3-datasource-okhttp:1.4.1`: Apache-2.0. OkHttp 4.12 was already a dependency.

The RTMP and SRT extensions were left out on purpose. Media3 RTMP pulls in native LGPL librtmp, and Media3 has no SRT source. RTMP sources are reachable through the RTSP re-serve that MediaMTX and OpenTAKServer provide.

## Testing

**Unit tests (JVM):** 58 new, all passing.

- `VideoFeedUrlsTest`: credential split, redaction, token handling, OTS link fix, UDP forms, validation.
- `FeedHealthMachineTest`: every transition, plus late-frame, backoff and fatal cases.
- `MartiVideoParserTest`: OTS JSON shape, VCM XML, inactive and raw feeds, garbage input.
- `CotVideoTest`: `__video` variants, legacy `<video>`, round trip, stripping on send and receive, UAS PLI, `rebuildEvent`.
- `VideoFeedStoreTest`: real DataStore in a temp folder, with a fake credential store.

**Whole suite:** 1533 tests. The 3 failures predate this branch and depend on the machine's locale: `CoordFormatterTest`, `TileMathTest` and `RangeRingsTest` expect `1.0` and get `1,0` on a French-locale JVM.

**Emulator (Pixel 3a, API 34)** against MediaMTX 1.21.3 and ffmpeg on the host:

- **Feed handling:**
  - RTSP with credentials: LIVE at 1280×720, 25 fps.
  - Credentials pasted into the URL moved to the fields.
  - Feeds and the encrypted password survived reinstall.
  - Wrong password: ERROR after a single attempt.
- **Staleness:**
  - Publisher frozen (process suspended, socket kept open): STALE at 4 s, then teardown and reconnect.
  - Publisher back: LIVE again on its own about 8 s later.
  - UDP MPEG-TS sender killed: STALE at 8 s, never LIVE.
- **Other transports:**
  - UDP MPEG-TS: LIVE.
  - HLS: LIVE with bitrate and live-offset readout (test build allowing cleartext to the emulator host only; not part of this PR). On the real build, `http://` HLS shows a clear ERROR.
- **Map and markers:**
  - Map PiP without any drone: drag, snapshot saved, expand to full screen.
  - Home during full-screen playback: Android PiP window keeps playing with its badge.
  - `__video` marker from a plain-TCP CoT fixture: listed in Video Feeds; marker sheet → Watch video → PiP LIVE.
  - Share to team: the captured CoT was ATAK-style `__video` with no username or password.

**Not tested:**

- `/Marti/api/video` and `/vcm` against a live server with a real client certificate. Only the parsers are tested, against the OTS 1.7.14 source.
- HLS from OpenTAKServer through the CA pin.
- Raw H.264 over UDP.
- A real drone feed into the UAS PiP.
- **Lint:** `lintDebug` reports 19 errors, all on lines that predate this branch (MissingPermission, NewApi for `URLEncoder.encode(String, Charset)`, UnsafeOptInUsageError in the ONVIF screen, ProduceStateDoesNotAssignValue). None are in code this PR adds. The warnings were not triaged.

## Screens

Not attached. Emulator screenshots are available on request.
