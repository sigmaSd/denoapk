# denoapk

Package a [`deno desktop`](https://docs.deno.com/runtime/desktop/) app as an
Android APK.

`deno desktop` has no mobile target — but its desktop backend is already "OS
WebView pointed at your web code", so denoapk does the same thing with an
Android WebView.

```
deno install -g -A jsr:@sigmasd/denoapk
```

## End to end

A `deno desktop` app is a `deno.json` with a `desktop.app` block plus a
`web/` directory. That's also all denoapk needs — nothing extra to add.

```json
// deno.json
{
  "desktop": {
    "app": {
      "name": "Hello",
      "identifier": "com.example.hello"
    }
  }
}
```

```html
<!-- web/index.html -->
<!doctype html>
<h1>Hello from denoapk</h1>
```

```
denoapk build .        # -> dist/hello.apk
adb install -r dist/hello.apk
```

That's the whole workflow. `desktop.app.icons.linux` becomes the launcher
icon if set, and `version` becomes `versionName` — both optional.

[sigmaSd/aiuse](https://github.com/sigmaSd/aiuse) is a real app built this
way: `report.ts` and `web/` show the shared `web/` layout, `runtime.js`
hosted from the Deno side (below), and `desktop.app` driving both the
desktop and Android builds from one `deno.json`.

## What it does _not_ do

There is **no Deno runtime on the device**. Backend code does not ship. If
your app serves its UI from `Deno.serve` handlers, extract the UI into a
static `web/` directory first — denoapk will tell you if it can't find one.

## The one thing that isn't obvious

An Android WebView is Chromium — a real browser — so page JavaScript cannot
set `Cookie`, `User-Agent`, `Referer` or `Sec-Fetch-*` (forbidden request
headers), and cross-origin APIs that send no CORS headers are unreadable.
That breaks any app that talks to a third-party API with cookie auth.

`runtime/runtime.js` is injected by the host and monkey-patches `fetch`:
cross-origin requests are rewritten to `/__denoapk/proxy/<encoded-url>` with
their headers moved under an `x-denoapk-h-` prefix, which the browser sends
happily. The shell restores the real names and replays the request from
native code, where neither restriction applies. The shim runs _before_
`fetch`, while `init.headers` is still a plain object, so the forbidden
names are still intact at that point.

App code is unaffected — it writes ordinary `fetch` with ordinary headers.
Host this same file from your Deno side (see cuse's `report.ts`) and the
same web bundle runs in both places.

Currently GET-only: `WebResourceRequest` exposes request headers but not a
POST body. Adding POST means an `addJavascriptInterface` transport behind
the same shim; app code still wouldn't change.

## How it's fast

The Godot approach: do the expensive work once.

`javac` + `d8` build the shell's `classes.dex`, cached under
`~/.cache/denoapk` keyed by a hash of the Java sources. After that,
packaging an app is `aapt2 compile`/`link` plus a zip plus a signature —
**~400ms**.

There is no Gradle and no AGP anywhere in this. That's deliberate: AGP's
supported-JDK window is narrow, and Gradle would add minutes per build for a
project with a handful of Java files and one PNG.

The manifest is generated from a template and compiled by `aapt2`, rather
than binary-patching the AXML string pool in a prebuilt template APK. `aapt2
link` on this resource set is well under a second, so generating correct
source is both simpler and impossible to get subtly wrong.

One detail makes per-app package names safe: the activity's `android:name`
is **fully qualified** (`dev.denoapk.shell.MainActivity`). A leading-dot
name resolves against the manifest `package`, which changes per app —
Android would then look for a class the dex doesn't contain.

## Toolchain

`denoapk doctor` reports status. On first run it downloads the Android
command-line tools into `~/.cache/denoapk/android-sdk` and installs
`build-tools;35.0.0` and `platforms;android-35` (~450MB, once). An existing
`ANDROID_HOME` / `ANDROID_SDK_ROOT` is used if it has those. A JDK is
required; Gradle and Android Studio are not.

APKs are **debug-signed** with a keystore generated at
`~/.cache/denoapk/debug.keystore` — fine for sideloading and `adb install`,
not for distribution. Release signing is not implemented yet.

## Layout

```
src/     cli, config, sdk bootstrap, dex cache, packaging, signing
shell/   the Android shell (Java, android.jar only — no AARs)
runtime/ runtime.js, the fetch shim shared with the Deno host
```
