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
 *
 * The fetches below are all resolved with `new URL(path, root)` rather than
 * rebuilding "https://jsr.io/@scope/pkg/..." from parsed-out pieces — `root`
 * already *is* that URL. `fetch()` doesn't care that it's `https:`, either:
 * it reads `file:` URLs the same way, so this same relative-resolution code
 * would materialize a local checkout too if ever pointed at one. It isn't,
 * because the *enumeration* still differs — JSR publishes a version
 * manifest to enumerate from, a local checkout doesn't have one and would
 * need a directory walk — which is what keeps resolveDenoapkDir() below
 * special-casing `file:` as a separate, cheaper, zero-copy path rather than
 * routing everything through here.
 */

import { dirname, fromFileUrl, join } from "@std/path";
import { exists } from "@std/fs";
import { cacheDir } from "./sdk.ts";

interface VersionManifest {
  manifest: Record<string, { size: number }>;
}

/** Exported for testing only; call `resolveDenoapkDir()` in real code. */
export async function materializeFromJsr(root: URL): Promise<string> {
  // root looks like https://jsr.io/@scope/pkg/<version>/ — pull out just
  // enough to name the cache dir and find the sibling manifest file.
  const m = root.pathname.match(/^\/(@[^/]+\/[^/]+)\/([^/]+)\/$/);
  if (!m) {
    throw new Error(
      `don't know how to locate denoapk's own shell/runtime assets from ${root.href}`,
    );
  }
  const [, pkgPath, version] = m;
  const dest = join(cacheDir(), "pkg", pkgPath.replace("/", "__"), version);
  const marker = join(dest, ".complete");
  if (await exists(marker)) return dest;

  const res = await fetch(new URL(`../${version}_meta.json`, root));
  if (!res.ok) {
    throw new Error(
      `couldn't fetch ${root.href}'s file manifest: ${res.status}`,
    );
  }
  const { manifest } = await res.json() as VersionManifest;

  const paths = Object.keys(manifest).filter((p) =>
    (p.startsWith("/shell/") && !p.startsWith("/shell/test/")) ||
    p.startsWith("/runtime/")
  );
  for (const path of paths) {
    // path is "/shell/foo.java" etc — strip the leading slash so it resolves
    // *under* root instead of replacing its path entirely.
    const fileRes = await fetch(new URL(path.slice(1), root));
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

  return await materializeFromJsr(root);
}
