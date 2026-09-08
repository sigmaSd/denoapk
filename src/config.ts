/**
 * Project configuration.
 *
 * denapk adds no config of its own: everything it needs is already in a
 * `deno desktop` project's deno.json under `desktop.app`.
 */

import { dirname, isAbsolute, join, resolve } from "@std/path";
import { exists } from "@std/fs";

export interface AppConfig {
  /** Project root (the directory holding deno.json). */
  root: string;
  /** Launcher label, e.g. "AI Usage". */
  name: string;
  /** Android package name, e.g. "io.github.sigmasd.aiuse". */
  packageName: string;
  /** Absolute path to a PNG icon, if the project has one. */
  icon?: string;
  /** Absolute path to the directory of web assets to bundle. */
  webDir: string;
  /** Version shown in the package manager. */
  versionName: string;
}

/** Android package names must be at least two dot-separated Java identifiers. */
export function validatePackageName(pkg: string): string | null {
  const parts = pkg.split(".");
  if (parts.length < 2) return "must contain at least one dot";
  for (const part of parts) {
    if (!/^[a-zA-Z][a-zA-Z0-9_]*$/.test(part)) {
      return `segment ${JSON.stringify(part)} is not a Java identifier`;
    }
  }
  return null;
}

/** Turn a project name into something usable as a package segment. */
function slug(name: string): string {
  const s = name.toLowerCase().replace(/[^a-z0-9]/g, "");
  return /^[a-z]/.test(s) ? s : "app" + s;
}

export async function loadConfig(projectDir: string): Promise<AppConfig> {
  const root = resolve(projectDir);
  const configPath = join(root, "deno.json");
  const jsoncPath = join(root, "deno.jsonc");
  const path = await exists(configPath)
    ? configPath
    : await exists(jsoncPath)
    ? jsoncPath
    : null;
  if (!path) {
    throw new Error(`no deno.json or deno.jsonc in ${root}`);
  }

  // deno.jsonc allows comments; strip them the way Deno's own parser would.
  const raw = await Deno.readTextFile(path);
  const json = JSON.parse(
    path.endsWith(".jsonc")
      ? raw.replace(/^\s*\/\/.*$/gm, "").replace(/\/\*[\s\S]*?\*\//g, "")
      : raw,
  );

  const desktop = json.desktop ?? {};
  const app = desktop.app ?? {};

  const name: string = app.name ?? json.name ?? "App";
  const packageName: string = app.identifier ??
    `com.deno.desktop.${slug(name)}`;

  const bad = validatePackageName(packageName);
  if (bad) {
    throw new Error(
      `desktop.app.identifier ${JSON.stringify(packageName)} is not a valid ` +
        `Android package name: ${bad}`,
    );
  }

  let icon: string | undefined;
  const iconRef = app.icons?.linux ?? app.icons?.android;
  const iconPath = typeof iconRef === "string"
    ? iconRef
    : Array.isArray(iconRef)
    ? iconRef[iconRef.length - 1]?.path
    : undefined;
  if (iconPath) {
    const abs = isAbsolute(iconPath) ? iconPath : join(root, iconPath);
    if (await exists(abs)) icon = abs;
    else console.error(`warning: icon ${abs} not found, using the default`);
  }

  const webDir = join(root, "web");
  if (!await exists(join(webDir, "index.html"))) {
    throw new Error(
      `expected ${join(webDir, "index.html")}\n` +
        `denapk packages the static web/ directory of a deno desktop app. ` +
        `If this project still serves its UI from Deno.serve, extract it to ` +
        `web/ first — there is no Deno runtime on the device.`,
    );
  }

  return {
    root,
    name,
    packageName,
    icon,
    webDir,
    versionName: json.version ?? "0.0.0",
  };
}

export { dirname };
