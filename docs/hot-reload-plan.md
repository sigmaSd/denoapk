# Hot reload for denoapk

## Context

Editing a `web/` file today means `denoapk build` → `adb install -r` → relaunch
for every change — slow enough to discourage UI iteration on a real device. The
goal: edit `web/` on the desktop, see it live on the phone, without rebuilding
or reinstalling for every edit.

This was discussed at length before planning. The naive version — point the
WebView at an external dev server, the way Capacitor/Ionic/Tauri all do it for
their live-reload modes — has a real, documented cost those tools accept: the
page's origin changes from what production uses (their own secure custom scheme)
to the dev server's plain `http://`, and `localStorage`/`crypto.subtle` (which
need a secure context) can behave differently in dev than they do in the shipped
build.

denoapk's architecture avoids that tradeoff entirely, because of how it already
works: `MainActivity.java`'s `shouldInterceptRequest` only intercepts requests
to the fixed host `appassets.androidplatform.net` (`Router.ASSET_HOST`) — the
WebView's origin **never has to change** for hot reload to work. Only _where the
bytes for a `Kind.ASSET` request come from_ changes: a live HTTP fetch to a dev
server instead of bundled APK assets. `Router.java`'s routing logic itself
doesn't change at all — confirmed by re-reading `RouterTest.java`: every routing
decision is already orthogonal to where `ASSET` bytes are sourced from.

## Design

### The one thing to de-risk before writing anything else

`WebView.reload()` should re-fire `shouldInterceptRequest` for every resource (a
reload is a real navigation), but WebView's own HTTP cache sits logically in
front of that and could still serve previously-cached response bytes without
ever reaching the handler — untested territory, since nothing in this codebase
has called `reload()` before. This is the single assumption everything else is
built on top of, so verify it first, in isolation, with no other new code:

Temporarily add `WebView.setWebContentsDebuggingEnabled(true)`, make `asset()`
return visibly different content on alternating calls (a counter in the HTML is
enough), attach `chrome://inspect` from desktop Chrome to the on-device WebView,
and confirm in the Network panel that (a) `shouldInterceptRequest` fires again
on `reload()`, (b) the page content actually changes, and (c) adding
`Cache-Control: no-store` to the response headers is what makes it a real "from
network" fetch rather than a cached one. `WebResourceResponse`'s 6-arg
constructor already supports custom headers — already used this way twice in
this codebase (`ProxyClient.perform`, `ExecClient.jsonResponse`) — so this is a
one-line addition once confirmed, not new plumbing.

### Keeping the origin unchanged: `DevAssetClient` + manifest meta-data

`denoapk build --dev <devServerUrl>` (new flag) makes `manifestFor()` add a
`<meta-data android:name="dev.denoapk.DEV_SERVER_URL"
android:value="${devServerUrl}" />`
under `<application>`, and forces `android:usesCleartextTraffic="true"`
unconditionally (a dev build is never meant to be distributed, so this isn't
gated behind the project's own `lan-cleartext` opt-in — the CLI prints a loud
"DEV BUILD — do not distribute" line on this path).

`MainActivity.onCreate` reads that meta-data via
`getPackageManager().getApplicationInfo(getPackageName(),
PackageManager.GET_META_DATA).metaData`
— null-check `.metaData` itself first, since it's `null` (not an empty `Bundle`)
whenever a manifest declares zero `<meta-data>` elements, i.e. every normal
build. If present (dev mode), `Kind.ASSET` requests (not `Kind.RUNTIME` —
denoapk's own runtime.js stays bundled; it's not part of the app's `web/` and
the existing "both hosts always agree on the wire format" reasoning in build.ts
applies here too) are served by a new `DevAssetClient.fetch()` doing a native
`HttpURLConnection` GET against `devServerUrl + path` instead of
`getAssets().open(...)` — same relay shape as `ProxyClient`, gated through the
same `NetworkTargets.isAllowed()` check, with `Cache-Control: no-store` on every
response. On dev-server-unreachable, return a distinct loud 502 ("could not
reach `<url>` — is `denoapk dev` still running?"), not the same silent 404 a
genuinely-missing file gets.

This is all native Java doing plain HTTP calls — not page JS — so none of it is
subject to the WebView's mixed-content policy (confirmed: this app never calls
`setMixedContentMode`, so it's on the platform default,
`MIXED_CONTENT_NEVER_ALLOW`, which blocks _all_ insecure sub-resource loads —
fetch, XHR, WebSocket alike — from a secure page; this session already hit this
exact wall once with `new WebSocket("ws://...")` on pingmonitor. Native sockets
aren't subject to that renderer-level policy at all, which is _why_ doing this
natively rather than from page JS is the right call, not just a style choice).

### Reload signal: a long-poll, not a WebSocket

Same reasoning applies to the reload signal — a WebSocket from page JS to a
plain `http://` dev server would hit the identical mixed-content wall.
`DevReloadWatcher` (new class) runs on its own daemon thread, long-polling
`GET <devServerUrl>/__denoapk/dev/wait-for-change?since=<version>` (the CLI's
dev server holds the request open — a standard long-poll pattern — until
`Deno.watchFs` bumps a version counter, or times out around 25s and responds
with the unchanged version either way). On a new version, calls
`webView.post(webView::reload)` — the same background-thread-to-UI-thread
handoff `ExecStreamBridge.push()` already established.

Teardown needs a new `onDestroy()` override that both flips a `volatile
running`
flag _and_ calls `.disconnect()` on the in-flight `HttpURLConnection` kept in a
field — a flag alone doesn't unblock a thread parked in a blocking socket read,
`Thread.interrupt()` doesn't either. No `onPause`/`onStop` handling: a thread
blocked in `read()` costs no CPU, and the added complexity of foreground-aware
reconnect isn't worth it for a dev-only tool.

Because `manifestFor()`'s `android:configChanges="orientation|..."` already
means `MainActivity` isn't destroyed/recreated on rotation, this watcher only
ever starts once per activity lifetime in practice — no risk of double-starting
it from config-change churn.

### `denoapk dev [projectDir]` — new CLI command, new `src/dev.ts`

A plain `Deno.serve()` serving `app.webDir` directly off disk — unbundled, no
`isPackageable()` TS-filtering (that's distribution hygiene for real builds,
irrelevant to local dev serving) — plus the long-poll endpoint (an in-memory
version counter, `Deno.watchFs(app.webDir)` bumping it on a trailing-edge
debounce so one save doesn't fire the reload multiple times for several fs
events). No MIME-type table needed on the Deno side: the phone already decides
content-type itself via `Router.mimeOf()` for bundled assets today, and the dev
path reuses that exact call — serving raw bytes from `dev.ts` avoids a second
MIME table drifting out of sync with the Java one. A basic path-containment
check (reject anything resolving outside `app.webDir`) before serving, matching
this codebase's existing care about this class of thing (`NetworkTargets`'s
IPv4-literal-only logic for a comparable reason) — this server has no auth,
LAN-only and ephemeral is the only real containment.

Prints the LAN address (a new `pickLanAddress()`-style helper — cuse already has
one, but it's project-specific in `host/pairing.ts`, not part of denoapk's own
package, so this needs its own copy) plus the exact `denoapk build --dev <url>`
follow-up command to copy-paste.

**Fixed default port, not `Deno.serve`'s usual `port: 0`** — deliberately
different from cuse's pairing-server precedent, whose ephemeral one-time port
was for security reasons (a one-time credential handoff) that don't apply here.
The dev URL is baked into the APK at build time; a stable port means the same
dev-mode APK build keeps working across many `denoapk dev` restarts as long as
the LAN IP doesn't change, instead of needing a full rebuild+reinstall every
session just because the port changed. Default to something unlikely to collide
with common front-end dev ports already running on the same machine (avoid
3000/5173/8080/8000 — e.g. 8787), with a `--port` override for the rare
collision.

## Build-and-verify order

1. **De-risk `reload()`/caching first, no new feature code** (see above) — the
   one assumption everything else depends on.
2. **`DevAssetClient` + manifest meta-data plumbing, static-only** — stand up
   just the static half of `dev.ts` (or even a one-off
   `python3 -m
   http.server` against `web/` for this step alone), build with
   `--dev
   http://<lan-ip>:<port>`, install, confirm the app loads over the
   dev path and `usesCleartextTraffic="true"` actually takes effect (a build
   without it would throw a cleartext-not-permitted error at the
   `HttpURLConnection` layer — confirm you see success, not that error).
3. **`GET_META_DATA`/aapt2 round-trip** — log the `devServerUrl` `MainActivity`
   actually reads at startup and confirm it exactly matches what `--dev` was
   given (this manifest is compiled by `aapt2
   link`, not Gradle, worth
   confirming the meta-data element survives that path faithfully).
4. **Add the long-poll watcher** — wire `Deno.watchFs` + debounce +
   `/wait-for-change` server-side, `DevReloadWatcher` client-side. Edit a file,
   time the wall-clock to visible reload (sub-second to low-seconds is the bar —
   this is the first genuinely new latency in the design, worth timing rather
   than assuming acceptable).
5. **Verify teardown** — force-stop/relaunch several times in a row, confirm
   each `onDestroy()` promptly unblocks and exits the poll thread (a temporary
   log line is enough to see this) rather than leaking one blocked thread per
   relaunch.
6. **Regression-check the untouched paths** — `deno task test:shell` unchanged,
   and one plain (non-`--dev`) `denoapk build` confirming no `<meta-data>` is
   emitted and `usesCleartextTraffic` is governed exactly as before by
   `lan-cleartext` alone. Confirms this is fully additive, not a behavior change
   for existing users/apps.
7. **Mixed-content `fetch()` check, last, for documentation completeness only**
   — from remote DevTools console on the real origin, confirm
   `fetch('http://<lan-ip>:<port>/...')` is blocked the same way the WebSocket
   case already was. Nothing in the design depends on this (native Java does all
   the dev-mode HTTP), but it's cheap and closes the one thing not independently
   re-verified from the earlier WebSocket finding.

## Critical files

- `shell/java/dev/denoapk/shell/DevAssetClient.java` (new) — dev-mode
  `Kind.ASSET` fetch, modeled on `ProxyClient.java`
- `shell/java/dev/denoapk/shell/DevReloadWatcher.java` (new) — long-poll
  - `webView.reload()`, modeled on `ExecStreamBridge.java`'s thread/push shape
- `shell/java/dev/denoapk/shell/MainActivity.java` — read the meta-data,
  start/stop the watcher, branch `Kind.ASSET` dispatch
- `src/build.ts` — `manifestFor()` gains an optional `devServerUrl` param
  (meta-data element + forced cleartext); `BuildOptions`/`buildApk()` thread it
  through
- `src/dev.ts` (new) — the `denoapk dev` command: static server, long-poll
  endpoint, `pickLanAddress()`
- `src/cli.ts` — `build --dev <url>` flag, new `dev` subcommand, usage text

Untouched, deliberately: `Router.java` (routing logic is already orthogonal to
asset source), `ExecClient.java`/`ExecStreamBridge.java` (exec/execStream keep
running natively on-device in dev mode, unaffected — a real advantage over
pointing the whole WebView at a dev server, which would make those accidentally
run on the desktop instead of the phone during testing).
