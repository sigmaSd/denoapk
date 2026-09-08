import { assertEquals, assertRejects, assertStringIncludes } from "@std/assert";
import { join } from "@std/path";
import { loadConfig, validatePackageName } from "./config.ts";

async function project(
  denoJson: Record<string, unknown>,
  files: Record<string, string> = { "web/index.html": "<!doctype html>" },
): Promise<string> {
  const dir = await Deno.makeTempDir({ prefix: "denoapk-test-" });
  await Deno.writeTextFile(
    join(dir, "deno.json"),
    JSON.stringify(denoJson, null, 2),
  );
  for (const [rel, body] of Object.entries(files)) {
    const path = join(dir, rel);
    await Deno.mkdir(join(path, ".."), { recursive: true });
    await Deno.writeTextFile(path, body);
  }
  return dir;
}

Deno.test("package names must be dotted Java identifiers", () => {
  assertEquals(validatePackageName("io.github.sigmasd.aiuse"), null);
  assertEquals(validatePackageName("com.example.app"), null);
  // No dot at all.
  assertStringIncludes(validatePackageName("app")!, "at least one dot");
  // A segment starting with a digit is not a Java identifier, and aapt2 would
  // reject the manifest much later with a far worse error.
  assertStringIncludes(validatePackageName("com.1example.app")!, "identifier");
  assertStringIncludes(validatePackageName("com.my-app.x")!, "identifier");
  assertStringIncludes(validatePackageName("com..app")!, "identifier");
});

Deno.test("reads name, identifier and icon from desktop.app", async () => {
  const dir = await project({
    name: "ignored",
    version: "0.2.0",
    desktop: {
      app: {
        name: "AI Usage",
        identifier: "io.github.sigmasd.aiuse",
        icons: { linux: "./assets/icon.png" },
      },
    },
  }, {
    "web/index.html": "<!doctype html>",
    "assets/icon.png": "not really a png",
  });

  const app = await loadConfig(dir);
  assertEquals(app.name, "AI Usage");
  assertEquals(app.packageName, "io.github.sigmasd.aiuse");
  assertEquals(app.versionName, "0.2.0");
  assertEquals(app.icon, join(dir, "assets/icon.png"));
});

Deno.test("falls back to the project name when desktop.app is absent", async () => {
  const dir = await project({ name: "my-app" });
  const app = await loadConfig(dir);
  assertEquals(app.name, "my-app");
  // Must still be a legal package name — the slug drops the hyphen.
  assertEquals(app.packageName, "com.deno.desktop.myapp");
  assertEquals(validatePackageName(app.packageName), null);
});

Deno.test("a name starting with a digit still yields a legal package", async () => {
  const dir = await project({ name: "2048" });
  const app = await loadConfig(dir);
  assertEquals(validatePackageName(app.packageName), null);
});

Deno.test("rejects an identifier Android would not accept", async () => {
  const dir = await project({
    desktop: { app: { name: "X", identifier: "notdotted" } },
  });
  await assertRejects(() => loadConfig(dir), Error, "not a valid Android");
});

Deno.test("explains itself when there is no web/ directory", async () => {
  const dir = await project({ desktop: { app: { name: "X" } } }, {});
  const err = await assertRejects(() => loadConfig(dir), Error);
  assertStringIncludes(err.message, "index.html");
  assertStringIncludes(err.message, "no Deno runtime on the device");
});

Deno.test("a missing icon is a warning, not a failure", async () => {
  const dir = await project({
    desktop: { app: { name: "X", icons: { linux: "./nope.png" } } },
  });
  const app = await loadConfig(dir);
  assertEquals(app.icon, undefined);
});
