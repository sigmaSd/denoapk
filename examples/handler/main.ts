/**
 * Minimal deno desktop + denoapk example using handleDenoapkRequest.
 *
 * Before (manual):
 *   Deno.serve(async (req) => {
 *     const url = new URL(req.url);
 *     if (url.pathname === "/__denoapk/runtime.js") return new Response(runtimeJs, ...);
 *     if (url.pathname.startsWith("/__denoapk/proxy/")) { // decode, fetch, filter headers }
 *     if (url.pathname.startsWith("/__denoapk/exec/")) { // Deno.Command }
 *     // app logic
 *   })
 *
 * After (auto):
 */
import { handleDenoapkRequest } from "@sigmasd/denoapk/handler";

const port = parseInt(Deno.env.get("PORT") ?? "8000");
Deno.serve({
  port,
  onListen: ({ port }) => console.log(`Listening on http://localhost:${port}/`),
}, async (req) => {
  // Auto-handles:
  //   /__denoapk/runtime.js            -> runtime.js shim
  //   /__denoapk/proxy/<url>           -> CORS + forbidden headers bypass (https always, http only private 10/172.16/192.168/127/169.254)
  //   /__denoapk/exec/<json>           -> Deno.Command (opt-in)
  //   /__denoapk/exec-stream/<json>    -> streaming (opt-in)
  // Returns null for everything else so your app logic runs.
  const denoapkRes = await handleDenoapkRequest(req, {
    exec: { enabled: true }, // opt-in: disabled by default for the wider trust boundary (no allowlist)
  });
  if (denoapkRes) return denoapkRes;

  const url = new URL(req.url);

  // Example API your web code can call same-origin
  if (url.pathname === "/api/hello") {
    return Response.json({
      message: "hello from Deno",
      time: new Date().toISOString(),
    });
  }

  // Serve web/ statically (simple, no build step for this example)
  try {
    const path = url.pathname === "/" ? "/index.html" : url.pathname;
    const file = await Deno.readFile(`./web${path}`);
    const mime = path.endsWith(".html")
      ? "text/html"
      : path.endsWith(".js")
      ? "text/javascript"
      : path.endsWith(".css")
      ? "text/css"
      : "application/octet-stream";
    return new Response(file, { headers: { "content-type": mime } });
  } catch {
    return new Response("not found", { status: 404 });
  }
});
