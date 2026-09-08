/**
 * Builds the shell's classes.dex — the only slow step, so it is cached.
 *
 * This is the Godot trick: the expensive compile happens once, and packaging
 * an app afterwards is just resource linking plus a zip. No Gradle, no AGP
 * (whose supported-JDK window is narrow — this machine runs JDK 25), just
 * javac and d8 against android.jar.
 *
 * The cache key is a hash of the Java sources, so editing the shell rebuilds
 * automatically and nothing else does.
 */

import { join } from "@std/path";
import { exists } from "@std/fs";
import { walk } from "@std/fs/walk";
import { cacheDir, type Sdk } from "./sdk.ts";

/** javac's --release for the shell. d8 desugars whatever this produces. */
const JAVA_RELEASE = "17";

async function run(cmd: string, args: string[], cwd?: string) {
  const { code, stdout, stderr } = await new Deno.Command(cmd, {
    args,
    cwd,
    stdout: "piped",
    stderr: "piped",
  }).output();
  if (code !== 0) {
    throw new Error(
      `${cmd} exited with ${code}\n` +
        new TextDecoder().decode(stdout) +
        new TextDecoder().decode(stderr),
    );
  }
}

async function sourceFiles(dir: string): Promise<string[]> {
  const out: string[] = [];
  for await (const e of walk(dir, { exts: [".java"], includeDirs: false })) {
    out.push(e.path);
  }
  return out.sort();
}

async function hashSources(files: string[]): Promise<string> {
  const parts: Uint8Array[] = [];
  for (const f of files) {
    parts.push(new TextEncoder().encode(f));
    parts.push(await Deno.readFile(f));
  }
  const total = parts.reduce((n, p) => n + p.length, 0);
  const joined = new Uint8Array(total);
  let at = 0;
  for (const p of parts) {
    joined.set(p, at);
    at += p.length;
  }
  const digest = await crypto.subtle.digest("SHA-256", joined);
  return Array.from(new Uint8Array(digest).slice(0, 8))
    .map((b) => b.toString(16).padStart(2, "0")).join("");
}

/**
 * Compile the shell to a dex file, reusing the cached one when the sources
 * are unchanged. Returns the path to classes.dex.
 */
export async function ensureDex(sdk: Sdk, shellDir: string): Promise<string> {
  const javaDir = join(shellDir, "java");
  const sources = await sourceFiles(javaDir);
  if (sources.length === 0) {
    throw new Error(`no .java sources under ${javaDir}`);
  }

  const key = await hashSources(sources);
  const outDir = join(cacheDir(), "dex", key);
  const dex = join(outDir, "classes.dex");
  if (await exists(dex)) return dex;

  console.error(`building shell dex (${sources.length} sources, key ${key})`);
  const classes = join(outDir, "classes");
  await Deno.mkdir(classes, { recursive: true });

  await run("javac", [
    "--release",
    JAVA_RELEASE,
    "-nowarn",
    "-classpath",
    sdk.androidJar,
    "-d",
    classes,
    ...sources,
  ]);

  const classFiles: string[] = [];
  for await (
    const e of walk(classes, { exts: [".class"], includeDirs: false })
  ) {
    classFiles.push(e.path);
  }

  await run(sdk.d8, [
    "--lib",
    sdk.androidJar,
    "--min-api",
    "24",
    "--output",
    outDir,
    ...classFiles,
  ]);

  await Deno.remove(classes, { recursive: true });
  if (!await exists(dex)) throw new Error(`d8 produced no ${dex}`);
  return dex;
}
