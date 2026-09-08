/**
 * Project configuration.
 *
 * App identity (name/icon/id) comes from `desktop.app`, which `deno desktop`
 * already defines — nothing new needed there. Android-only concepts that
 * `deno desktop` has no notion of (permissions, and future Android-specific
 * settings) live under a separate top-level `android` key instead of being
 * squeezed into `desktop.app`: that object is validated by deno desktop's own
 * JSON Schema, which rejects unknown properties, so anything Android-specific
 * added there would show as an editor warning. `android` is named after the
 * platform it targets, the same way `desktop` is — not after this tool.
 */

import { dirname, isAbsolute, join, resolve } from "@std/path";
import { exists } from "@std/fs";

/**
 * Config-facing permission names to the Android manifest constants they map
 * to. Extend this as more permissions become supported; unknown names in a
 * project's config are warned about, not silently dropped, so a typo or a
 * permission from a newer denoapk doesn't fail invisibly.
 */
export const KNOWN_PERMISSIONS: Record<string, string> = {
  camera: "android.permission.CAMERA",
};

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
  /** Config-facing permission names from `android.permissions`, e.g. ["camera"]. */
  permissions: string[];
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
        `denoapk packages the static web/ directory of a deno desktop app. ` +
        `If this project still serves its UI from Deno.serve, extract it to ` +
        `web/ first — there is no Deno runtime on the device.`,
    );
  }

  const permissions: string[] = Array.isArray(json.android?.permissions)
    ? json.android.permissions
    : [];
  for (const p of permissions) {
    if (!(p in KNOWN_PERMISSIONS)) {
      console.error(
        `warning: android.permissions has unknown entry ${
          JSON.stringify(p)
        } — ignoring it. Known: ${Object.keys(KNOWN_PERMISSIONS).join(", ")}`,
      );
    }
  }

  return {
    root,
    name,
    packageName,
    icon,
    webDir,
    versionName: json.version ?? "0.0.0",
    permissions,
  };
}

export { dirname };
