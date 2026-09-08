/**
 * Per-app packaging: resources, assets, alignment, signature.
 *
 * The manifest is generated from a template rather than binary-patched. That
 * choice matters: rewriting the compiled AXML string pool in place is the
 * classic way to do this (it's what Godot's export templates do), but
 * `aapt2 link` on a two-file resource set takes well under a second, so
 * generating correct source and letting aapt2 compile it is both faster to
 * write and impossible to get subtly wrong.
 *
 * The activity's android:name is fully qualified in the template. A
 * leading-dot name resolves against the manifest package, so per-app package
 * names would otherwise send Android looking for a class the dex doesn't have.
 */

import { basename, dirname, join, relative } from "@std/path";
import { copy, emptyDir, exists } from "@std/fs";
import { walk } from "@std/fs/walk";
import type { Sdk } from "./sdk.ts";
import { type AppConfig, KNOWN_PERMISSIONS } from "./config.ts";
import { ensureDex } from "./dex.ts";
import { alignAndSign } from "./sign.ts";

const MIN_SDK = "24";
const TARGET_SDK = "35";

async function run(cmd: string, args: string[], cwd?: string) {
  const { code, stdout, stderr } = await new Deno.Command(cmd, {
    args,
    cwd,
    stdout: "piped",
    stderr: "piped",
  }).output();
  if (code !== 0) {
    throw new Error(
      `${cmd} ${args.slice(0, 3).join(" ")} exited with ${code}\n` +
        new TextDecoder().decode(stdout) +
        new TextDecoder().decode(stderr),
    );
  }
}

function xmlEscape(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;")
    .replace(/>/g, "&gt;").replace(/"/g, "&quot;");
}

function manifestFor(app: AppConfig): string {
  // Only permissions the project actually opted into via `android.permissions`
  // in deno.json get declared — the shell's permission-grant code (see
  // MainActivity.java) checks the OS-level grant state at runtime regardless,
  // but an undeclared permission is always denied by Android before that code
  // ever runs, so this line is what actually gates the behavior per app.
  const extraPermissions = app.permissions
    .map((p) => KNOWN_PERMISSIONS[p])
    .filter((p): p is string => !!p)
    .map((name) => `    <uses-permission android:name="${name}" />`)
    .join("\n");

  // See config.ts's KNOWN_CAPABILITIES — this one controls an attribute, not
  // a <uses-permission> element, so it's read from the same array directly
  // rather than through the name-to-constant KNOWN_PERMISSIONS map.
  const cleartext = app.permissions.includes("lan-cleartext");

  return `<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="${xmlEscape(app.packageName)}"
    android:versionCode="1"
    android:versionName="${xmlEscape(app.versionName)}">

    <uses-sdk android:minSdkVersion="${MIN_SDK}" android:targetSdkVersion="${TARGET_SDK}" />
    <uses-permission android:name="android.permission.INTERNET" />
${extraPermissions ? extraPermissions + "\n" : ""}

    <application
        android:label="${xmlEscape(app.name)}"
        android:icon="@mipmap/ic_launcher"
        android:usesCleartextTraffic="${cleartext}"
        android:allowBackup="true"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <!--
          The shell shows a single full-bleed WebView. The default theme's
          ActionBar would draw the app label as a second, native header on
          top of the page's own — NoActionBar removes it rather than trying
          to size around it.
        -->

        <activity
            android:name="dev.denoapk.shell.MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden|smallestScreenSize|screenLayout|density|uiMode">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
`;
}

/**
 * Files that exist in a web/ directory but can never be loaded by a browser.
 *
 * A `deno desktop` project keeps its TypeScript next to the bundle it builds,
 * and shipping the sources (and their test fixtures) into a distributed APK is
 * both dead weight and a small information leak. Nothing here is a runtime
 * asset by definition: no browser loads TypeScript.
 */
function isPackageable(path: string): boolean {
  const name = basename(path);
  if (/\.(ts|tsx|mts|cts)$/.test(name)) return false;
  if (/(^|[._-])test\.[a-z]+$/.test(name) || /_test\.[a-z]+$/.test(name)) {
    return false;
  }
  if (name.endsWith(".map")) return false;
  return true;
}

/** Copy web/ into the APK's asset staging area, skipping non-runtime files. */
async function copyWebAssets(from: string, to: string): Promise<number> {
  let count = 0;
  for await (const entry of walk(from, { includeDirs: false })) {
    if (!isPackageable(entry.path)) continue;
    const rel = relative(from, entry.path);
    const dest = join(to, rel);
    await Deno.mkdir(dirname(dest), { recursive: true });
    await copy(entry.path, dest, { overwrite: true });
    count++;
  }
  if (count === 0) throw new Error(`no packageable files under ${from}`);
  return count;
}

/**
 * Warn when index.html points at something that isn't there — almost always a
 * stale or missing bundle, which would otherwise only show up as a blank
 * screen on the device.
 */
async function checkReferences(webDir: string) {
  const html = await Deno.readTextFile(join(webDir, "index.html"));
  for (const m of html.matchAll(/(?:src|href)="([^"]+)"/g)) {
    const ref = m[1];
    if (/^(https?:)?\/\//.test(ref) || ref.startsWith("data:")) continue;
    // Host-provided paths are served by the shell, not from assets.
    if (ref.startsWith("/__denoapk/")) continue;
    const path = join(webDir, ref.replace(/^\.?\//, "").split(/[?#]/)[0]);
    if (!await exists(path)) {
      console.error(
        `warning: index.html references ${ref}, which does not exist.\n` +
          `         If that is a build output, run the project's bundle task first.`,
      );
    }
  }
}

export interface BuildOptions {
  sdk: Sdk;
  app: AppConfig;
  /** denoapk's own directory (holds shell/ and runtime/). */
  denoapkDir: string;
  /** Where to write the finished APK. */
  output: string;
}

export async function buildApk(opts: BuildOptions): Promise<string> {
  const { sdk, app, denoapkDir, output } = opts;
  const shellDir = join(denoapkDir, "shell");

  await checkReferences(app.webDir);

  const work = await Deno.makeTempDir({ prefix: "denoapk-" });
  try {
    // 1. The shell's dex — cached across builds, so usually a no-op.
    const dex = await ensureDex(sdk, shellDir);

    // 2. Resources: the shell's res/ plus this app's icon.
    const res = join(work, "res");
    await copy(join(shellDir, "res"), res);
    if (app.icon) {
      await copy(app.icon, join(res, "mipmap", "ic_launcher.png"), {
        overwrite: true,
      });
    }
    const compiled = join(work, "res.zip");
    await run(sdk.aapt2, ["compile", "--dir", res, "-o", compiled]);

    // 3. Link against the generated manifest, producing a resources-only APK.
    const manifest = join(work, "AndroidManifest.xml");
    await Deno.writeTextFile(manifest, manifestFor(app));
    const base = join(work, "base.apk");
    await run(sdk.aapt2, [
      "link",
      "-o",
      base,
      "-I",
      sdk.androidJar,
      "--manifest",
      manifest,
      "--min-sdk-version",
      MIN_SDK,
      "--target-sdk-version",
      TARGET_SDK,
      compiled,
    ]);

    // 4. Add the dex and the web payload. aapt2 emits a zip, so the rest is
    //    just adding entries to it.
    const staging = join(work, "staging");
    await emptyDir(staging);
    await copy(dex, join(staging, "classes.dex"));

    const assets = join(staging, "assets");
    const copied = await copyWebAssets(app.webDir, join(assets, "www"));
    console.error(`  packaged ${copied} web files`);
    // The shim is denoapk's, not the app's, so both hosts always agree on the
    // wire format even if the app's checkout is older.
    await copy(
      join(denoapkDir, "runtime", "runtime.js"),
      join(assets, "runtime.js"),
    );

    // `zip -X` keeps out extra fields that would fight zipalign.
    await run(
      "zip",
      ["-q", "-X", "-r", base, "classes.dex", "assets"],
      staging,
    );

    // 5. Align, then sign — never the other way round.
    await Deno.mkdir(join(output, ".."), { recursive: true }).catch(() => {});
    await alignAndSign(sdk, base, output);
    return output;
  } finally {
    await Deno.remove(work, { recursive: true }).catch(() => {});
  }
}

export { basename, exists };
