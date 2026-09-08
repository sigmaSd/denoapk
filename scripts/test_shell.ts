#!/usr/bin/env -S deno run --allow-all
/**
 * Runs the shell's JVM tests.
 *
 * Router.java has no Android imports at all, so it (and RouterTest) compile
 * and run on a bare JDK. Permissions.java references
 * `android.webkit.PermissionRequest.RESOURCE_VIDEO_CAPTURE` — a real constant
 * value even in the stub android.jar (verified: it resolves under plain java,
 * not just javac), so it needs the SDK's android.jar on the classpath to
 * compile and run, but still needs no device/emulator.
 */

import { dirname, fromFileUrl, join } from "@std/path";
import { ensureSdk } from "../src/sdk.ts";

const root = dirname(dirname(fromFileUrl(import.meta.url)));
const out = await Deno.makeTempDir({ prefix: "denoapk-shell-test-" });
const sdk = await ensureSdk();

try {
  const compile = await new Deno.Command("javac", {
    args: [
      "--release",
      "17",
      "-nowarn",
      "-cp",
      sdk.androidJar,
      "-d",
      out,
      join(root, "shell/java/dev/denoapk/shell/Router.java"),
      join(root, "shell/java/dev/denoapk/shell/Permissions.java"),
      join(root, "shell/test/RouterTest.java"),
      join(root, "shell/test/PermissionsTest.java"),
    ],
    stdout: "inherit",
    stderr: "inherit",
  }).output();
  if (compile.code !== 0) Deno.exit(compile.code);

  for (const cls of ["RouterTest", "PermissionsTest"]) {
    const run = await new Deno.Command("java", {
      args: ["-cp", `${out}:${sdk.androidJar}`, cls],
      stdout: "inherit",
      stderr: "inherit",
    }).output();
    if (run.code !== 0) Deno.exit(run.code);
  }
} finally {
  await Deno.remove(out, { recursive: true }).catch(() => {});
}
