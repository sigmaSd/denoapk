#!/usr/bin/env -S deno run --allow-all
/**
 * Runs the shell's JVM tests.
 *
 * Router is kept free of Android types precisely so this can run on a plain
 * JDK — no device, no emulator, no android.jar.
 */

import { dirname, fromFileUrl, join } from "@std/path";

const root = dirname(dirname(fromFileUrl(import.meta.url)));
const out = await Deno.makeTempDir({ prefix: "denoapk-shell-test-" });

try {
  const compile = await new Deno.Command("javac", {
    args: [
      "--release",
      "17",
      "-nowarn",
      "-d",
      out,
      join(root, "shell/java/dev/denoapk/shell/Router.java"),
      join(root, "shell/test/RouterTest.java"),
    ],
    stdout: "inherit",
    stderr: "inherit",
  }).output();
  if (compile.code !== 0) Deno.exit(compile.code);

  const run = await new Deno.Command("java", {
    args: ["-cp", out, "RouterTest"],
    stdout: "inherit",
    stderr: "inherit",
  }).output();
  Deno.exit(run.code);
} finally {
  await Deno.remove(out, { recursive: true }).catch(() => {});
}
