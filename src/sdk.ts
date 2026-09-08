/**
 * Android SDK bootstrap.
 *
 * denoapk needs only three things from the SDK — aapt2/d8 to build the shell
 * template once, and zipalign/apksigner to stamp and sign each app. No Gradle,
 * no Android Studio, no AGP (which matters: AGP's supported-JDK window is
 * narrow and this machine runs JDK 25).
 *
 * If ANDROID_HOME / ANDROID_SDK_ROOT already points at an SDK with the pieces
 * we need, use it. Otherwise download the command-line tools into
 * ~/.cache/denoapk/android-sdk and have sdkmanager fetch the rest.
 */

import { join } from "@std/path";
import { exists } from "@std/fs";

export const BUILD_TOOLS_VERSION = "35.0.0";
export const PLATFORM_VERSION = "android-35";

const CMDLINE_TOOLS_URL =
  "https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip";

export interface Sdk {
  root: string;
  aapt2: string;
  d8: string;
  zipalign: string;
  apksigner: string;
  androidJar: string;
}

export function cacheDir(): string {
  const base = Deno.env.get("XDG_CACHE_HOME") ??
    join(Deno.env.get("HOME")!, ".cache");
  return join(base, "denoapk");
}

function layout(root: string): Sdk {
  const bt = join(root, "build-tools", BUILD_TOOLS_VERSION);
  return {
    root,
    aapt2: join(bt, "aapt2"),
    d8: join(bt, "d8"),
    zipalign: join(bt, "zipalign"),
    apksigner: join(bt, "apksigner"),
    androidJar: join(root, "platforms", PLATFORM_VERSION, "android.jar"),
  };
}

async function isComplete(sdk: Sdk): Promise<boolean> {
  for (
    const p of [sdk.aapt2, sdk.d8, sdk.zipalign, sdk.apksigner, sdk.androidJar]
  ) {
    if (!await exists(p)) return false;
  }
  return true;
}

async function run(cmd: string, args: string[], opts: { stdin?: string } = {}) {
  const proc = new Deno.Command(cmd, {
    args,
    stdin: opts.stdin === undefined ? "null" : "piped",
    stdout: "inherit",
    stderr: "inherit",
  }).spawn();

  if (opts.stdin !== undefined) {
    const w = proc.stdin.getWriter();
    await w.write(new TextEncoder().encode(opts.stdin));
    await w.close();
  }
  const { code } = await proc.status;
  if (code !== 0) throw new Error(`${cmd} exited with ${code}`);
}

async function download(url: string, dest: string) {
  console.error(`  downloading ${url.split("/").pop()}`);
  const res = await fetch(url);
  if (!res.ok) throw new Error(`download failed: ${res.status} ${url}`);
  const file = await Deno.open(dest, {
    create: true,
    write: true,
    truncate: true,
  });
  await res.body!.pipeTo(file.writable);
}

/** Locate a usable SDK, downloading one if needed. */
export async function ensureSdk(): Promise<Sdk> {
  for (const envVar of ["ANDROID_HOME", "ANDROID_SDK_ROOT"]) {
    const root = Deno.env.get(envVar);
    if (!root) continue;
    const sdk = layout(root);
    if (await isComplete(sdk)) {
      console.error(`using SDK from ${envVar}: ${root}`);
      return sdk;
    }
    console.error(
      `${envVar}=${root} is missing build-tools ${BUILD_TOOLS_VERSION} or ` +
        `${PLATFORM_VERSION}; falling back to the denoapk cache`,
    );
  }

  const root = join(cacheDir(), "android-sdk");
  const sdk = layout(root);
  if (await isComplete(sdk)) return sdk;

  console.error(`bootstrapping Android SDK into ${root}`);
  await Deno.mkdir(root, { recursive: true });

  // sdkmanager insists on living at cmdline-tools/<channel>/bin/sdkmanager.
  const sdkmanager = join(root, "cmdline-tools", "latest", "bin", "sdkmanager");
  if (!await exists(sdkmanager)) {
    const zip = join(cacheDir(), "cmdline-tools.zip");
    await download(CMDLINE_TOOLS_URL, zip);
    // The archive unpacks to `cmdline-tools/`; move it into place as `latest`.
    const staging = join(root, ".staging");
    await Deno.mkdir(staging, { recursive: true });
    await run("unzip", ["-q", "-o", zip, "-d", staging]);
    await Deno.mkdir(join(root, "cmdline-tools"), { recursive: true });
    await Deno.rename(
      join(staging, "cmdline-tools"),
      join(root, "cmdline-tools", "latest"),
    );
    await Deno.remove(staging, { recursive: true });
    await Deno.remove(zip);
  }

  const packages = [
    `build-tools;${BUILD_TOOLS_VERSION}`,
    `platforms;${PLATFORM_VERSION}`,
  ];
  console.error(`  accepting licenses`);
  await run(sdkmanager, [`--sdk_root=${root}`, "--licenses"], {
    stdin: "y\n".repeat(50),
  });
  console.error(`  installing ${packages.join(" ")}`);
  await run(sdkmanager, [`--sdk_root=${root}`, ...packages]);

  if (!await isComplete(sdk)) {
    throw new Error(`SDK bootstrap finished but ${root} is still incomplete`);
  }
  return sdk;
}

if (import.meta.main) {
  const sdk = await ensureSdk();
  console.log(JSON.stringify(sdk, null, 2));
}
