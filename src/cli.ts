#!/usr/bin/env -S deno run --allow-all
/**
 * denoapk — package a `deno desktop` app as an Android APK.
 *
 * `deno desktop` has no mobile target and no plans published for one, but its
 * default backend is already "OS WebView pointed at your web code". This does
 * the same thing with an Android WebView, so a project whose UI is a static
 * web/ directory can ship to a phone.
 *
 * Usage:
 *   denoapk build [projectDir] [-o out.apk]
 *   denoapk doctor
 */

import { dirname, fromFileUrl, join, resolve } from "@std/path";
import { parseArgs } from "@std/cli/parse-args";
import { ensureSdk } from "./sdk.ts";
import { loadConfig } from "./config.ts";
import { buildApk } from "./build.ts";
import { debugKeystorePath, verify } from "./sign.ts";

const DENOAPK_DIR = dirname(dirname(fromFileUrl(import.meta.url)));

function usage(): never {
  console.error(`denoapk — package a deno desktop app as an Android APK

Usage:
  denoapk build [projectDir] [-o <out.apk>]   build an APK (default: .)
  denoapk doctor                              show toolchain status

Configuration comes from the project's deno.json \`desktop.app\` block:
  name        -> launcher label
  identifier  -> Android package name
  icons.linux -> launcher icon

The project's web/ directory is bundled as the app's assets. Backend code is
not packaged — there is no Deno runtime on the device.`);
  Deno.exit(1);
}

async function cmdBuild(args: string[]) {
  const flags = parseArgs(args, { string: ["output"], alias: { o: "output" } });
  const projectDir = resolve(String(flags._[0] ?? "."));

  const app = await loadConfig(projectDir);
  const output = flags.output
    ? resolve(flags.output)
    : join(app.root, "dist", `${app.packageName.split(".").pop()}.apk`);

  console.error(`packaging ${app.name} (${app.packageName})`);
  console.error(`  assets  ${app.webDir}`);
  console.error(`  icon    ${app.icon ?? "(shell default)"}`);

  const sdk = await ensureSdk();
  const started = performance.now();
  await buildApk({ sdk, app, denoapkDir: DENOAPK_DIR, output });
  const ms = Math.round(performance.now() - started);

  const size = (await Deno.stat(output)).size;
  console.error(
    `\nbuilt ${output} (${(size / 1024).toFixed(0)} KB) in ${ms}ms`,
  );
  console.error(`install with: adb install -r ${output}`);
}

async function cmdDoctor() {
  console.log("java:      " + await which("java"));
  console.log("adb:       " + await which("adb"));
  console.log("keystore:  " + debugKeystorePath());
  const sdk = await ensureSdk();
  console.log("sdk root:  " + sdk.root);
  for (const [name, path] of Object.entries(sdk)) {
    if (name === "root") continue;
    console.log(`  ${name.padEnd(11)} ${path}`);
  }
}

async function which(bin: string): Promise<string> {
  const { code, stdout } = await new Deno.Command("which", {
    args: [bin],
    stdout: "piped",
    stderr: "null",
  }).output();
  return code === 0 ? new TextDecoder().decode(stdout).trim() : "(not found)";
}

if (import.meta.main) {
  const [cmd, ...rest] = Deno.args;
  switch (cmd) {
    case "build":
      await cmdBuild(rest);
      break;
    case "doctor":
      await cmdDoctor();
      break;
    case "verify": {
      const sdk = await ensureSdk();
      console.log(await verify(sdk, resolve(rest[0])));
      break;
    }
    default:
      usage();
  }
}
