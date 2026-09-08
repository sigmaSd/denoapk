/**
 * Where denoapk's own shell/ and runtime/ directories actually live.
 *
 * When denoapk runs from a local checkout (`import.meta.url` is a `file:`
 * URL — developing denoapk itself, or `deno run --allow-all src/cli.ts`
 * against a git clone), those directories are already real files right next
 * to this module: nothing to do.
 *
 * When denoapk runs as a `deno install -g`'d JSR package, `import.meta.url`
 * is an `https://jsr.io/...` specifier instead — there is no local `shell/`
 * or `runtime/` on disk in that shape at all. That's a real problem, not
 * just an inconvenience: build.ts hands shell/java/*.java to javac and
 * shell/res to aapt2 as subprocess arguments, and neither tool can read a
 * remote URL. This fetches every file JSR actually published under those two
 * directories — enumerated from the package's own version manifest, not a
 * hardcoded list, so it can't silently go stale as files are added or
 * removed — and writes them into a cache directory keyed by exact package
 * version, once.
 */

import { dirname, fromFileUrl, join } from "@std/path";
import { exists } from "@std/fs";
import { cacheDir } from "./sdk.ts";

interface VersionManifest {
  manifest: Record<string, { size: number }>;
}

/** Exported for testing only; call `resolveDenoapkDir()` in real code. */
export async function materializeFromJsr(
  scope: string,
  pkg: string,
  version: string,
): Promise<string> {
  const dest = join(cacheDir(), "pkg", `${scope}__${pkg}`, version);
  const marker = join(dest, ".complete");
  if (await exists(marker)) return dest;

  const base = `https://jsr.io/@${scope}/${pkg}`;
  const res = await fetch(`${base}/${version}_meta.json`);
  if (!res.ok) {
    throw new Error(
      `couldn't fetch @${scope}/${pkg}@${version}'s file manifest: ${res.status}`,
    );
  }
  const { manifest } = await res.json() as VersionManifest;

  const paths = Object.keys(manifest).filter((p) =>
    (p.startsWith("/shell/") && !p.startsWith("/shell/test/")) ||
    p.startsWith("/runtime/")
  );
  for (const path of paths) {
    const fileRes = await fetch(`${base}/${version}${path}`);
    if (!fileRes.ok) {
      throw new Error(`couldn't fetch ${path}: ${fileRes.status}`);
    }
    const target = join(dest, path);
    await Deno.mkdir(dirname(target), { recursive: true });
    await Deno.writeFile(target, new Uint8Array(await fileRes.arrayBuffer()));
  }

  await Deno.writeTextFile(marker, "");
  return dest;
}

/** The directory holding this project's own `shell/` and `runtime/`. */
export async function resolveDenoapkDir(): Promise<string> {
  // Resolved relative to this module (src/assets.ts), one level up — works
  // for both a local file: URL and a remote jsr.io one, giving back
  // whichever form import.meta.url is actually in.
  const root = new URL("..", import.meta.url);

  if (root.protocol === "file:") {
    return fromFileUrl(root);
  }

  const m = root.href.match(
    /^https:\/\/jsr\.io\/@([^/]+)\/([^/]+)\/([^/]+)\/$/,
  );
  if (!m) {
    throw new Error(
      `don't know how to locate denoapk's own shell/runtime assets from ${root.href}`,
    );
  }
  const [, scope, pkg, version] = m;
  return await materializeFromJsr(scope, pkg, version);
}
