import { assert, assertEquals, assertStringIncludes } from "@std/assert";
import { ensureRuntimeShim } from "./build.ts";

Deno.test("a present shim tag is left byte-identical", () => {
  const html =
    `<!doctype html><html><head><script src="/__denoapk/runtime.js"></script></head><body></body></html>`;
  const { html: out, injected } = ensureRuntimeShim(html);
  assertEquals(out, html);
  assertEquals(injected, false);
});

Deno.test("a tag with a query string counts as present", () => {
  const html = `<head><script src="/__denoapk/runtime.js?v=2"></script></head>`;
  const { html: out, injected } = ensureRuntimeShim(html);
  assertEquals(out, html);
  assertEquals(injected, false);
});

Deno.test("a missing tag is inserted immediately after <head>", () => {
  const { html: out, injected } = ensureRuntimeShim(
    `<!doctype html><html><head><title>t</title><script src="app.js"></script></head><body><h1>hi</h1></body></html>`,
  );
  assertEquals(injected, true);
  assertStringIncludes(out, `<script src="/__denoapk/runtime.js"></script>`);
  assert(
    out.indexOf("/__denoapk/runtime.js") < out.indexOf("app.js"),
    "shim must load before app scripts",
  );
  // One tag only, and re-running is a no-op.
  assertEquals(out.split("/__denoapk/runtime.js").length - 1, 1);
  assertEquals(ensureRuntimeShim(out).injected, false);
});

Deno.test("matching <head> is case-insensitive", () => {
  const { html: out, injected } = ensureRuntimeShim(
    `<HTML><HEAD><script src="app.js"></script></HEAD><BODY></BODY></HTML>`,
  );
  assertEquals(injected, true);
  assert(out.indexOf("/__denoapk/runtime.js") < out.indexOf("app.js"));
});

Deno.test("a headless document gets the tag prepended", () => {
  const { html: out, injected } = ensureRuntimeShim(`<h1>hi</h1>`);
  assertEquals(injected, true);
  assert(out.startsWith("<!-- injected by denoapk -->"));
});
