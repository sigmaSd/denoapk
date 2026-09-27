/**
 * @module handler
 * Deno host helper for denoapk — auto-handles `__denoapk/*` requests
 * so app code doesn't have to.
 *
 * Mirrors what the Android shell does in `Router.java` / `ProxyClient.java` /
 * `ExecClient.java`. Handles:
 * - `GET /__denoapk/runtime.js` — the fetch shim (`runtime/runtime.js`)
 * - `GET /__denoapk/proxy/<encoded-url>` — CORS + forbidden headers bypass
 * - `GET /__denoapk/exec/<json>` / `exec-stream` — subprocess (opt-in)
 *
 * Only `__denoapk/*` is handled — returns `null` otherwise so your app
 * routing runs. `exec` is opt-in (disabled by default) for the wider trust
 * boundary (no allowlist, like `ExecClient.java`).
 *
 * @example
 * ```ts
 * import { handleDenoapkRequest } from "jsr:@sigmasd/denoapk/handler";
 * Deno.serve(async (req) => {
 *   const r = await handleDenoapkRequest(req, { exec: { enabled: true } });
 *   if (r) return r;
 *   return new Response("hello");
 * });
 * ```
 */

const RUNTIME_PATH = "/__denoapk/runtime.js";
const PROXY_PREFIX = "/__denoapk/proxy/";
const EXEC_PREFIX = "/__denoapk/exec/";
const EXEC_STREAM_PREFIX = "/__denoapk/exec-stream/";
const HEADER_PREFIX = "x-denoapk-h-";

const DEFAULT_TIMEOUT_MS = 10_000;
const MAX_TIMEOUT_MS = 60_000;
const PROXY_TIMEOUT_MS = 30_000;

/**
 * Options for {@linkcode handleDenoapkRequest}.
 */
export interface HandleOptions {
  /** Enable `exec` / `exec-stream` — disabled by default (no allowlist, like `ExecClient.java`). */
  exec?: { enabled?: boolean };
}

// Cache runtime.js text after first load (file: vs https: dual path like src/assets.ts)
let runtimeCache: string | null = null;
let runtimeCachePromise: Promise<string> | null = null;

function fileUrlToPath(url: URL): string {
  // Minimal fromFileUrl for file: URLs, avoids @std/path import issues when
  // handler is tested via absolute path outside deno.json import map.
  return decodeURIComponent(url.pathname);
}

function loadRuntimeJs(): Promise<string> {
  if (runtimeCache !== null) return Promise.resolve(runtimeCache);
  if (runtimeCachePromise) return runtimeCachePromise;
  runtimeCachePromise = (async () => {
    const url = new URL("../runtime/runtime.js", import.meta.url);
    let text: string;
    if (url.protocol === "file:") {
      text = await Deno.readTextFile(fileUrlToPath(url));
    } else {
      const res = await fetch(url);
      if (!res.ok) throw new Error(`failed to load runtime.js: ${res.status}`);
      text = await res.text();
    }
    runtimeCache = text;
    return text;
  })();
  return runtimeCachePromise;
}

/**
 * Whether `host` is a private IPv4 literal (10/8, 172.16/12, 192.168/16, 127/8, 169.254/16).
 * Mirrors `NetworkTargets.isPrivateIPv4Literal` — literal check only, no DNS.
 */
export function isPrivateIPv4Literal(host: string): boolean {
  const parts = host.split(".", -1);
  if (parts.length !== 4) return false;
  const octets: number[] = [];
  for (const p of parts) {
    if (p.length === 0 || p.length > 3) return false;
    if (!/^\d+$/.test(p)) return false;
    const n = parseInt(p, 10);
    if (n > 255) return false;
    octets.push(n);
  }
  if (octets[0] === 10) return true;
  if (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31) return true;
  if (octets[0] === 192 && octets[1] === 168) return true;
  if (octets[0] === 127) return true;
  if (octets[0] === 169 && octets[1] === 254) return true;
  return false;
}

/**
 * Whether a proxy target `protocol://host` is allowed.
 * `https` always, `http` only to a private IPv4 literal — mirrors `NetworkTargets.isAllowed`.
 */
export function isAllowed(protocol: string, host: string): boolean {
  if (protocol === "https") return true;
  if (protocol === "http") return isPrivateIPv4Literal(host);
  return false;
}

function errorResponse(status: number, message: string): Response {
  return new Response(message, {
    status,
    headers: { "content-type": "text/plain; charset=utf-8" },
  });
}

function statusText(status: number): string {
  switch (status) {
    case 200:
      return "OK";
    case 400:
      return "Bad Request";
    case 401:
      return "Unauthorized";
    case 403:
      return "Forbidden";
    case 404:
      return "Not Found";
    case 502:
      return "Bad Gateway";
    default:
      return "Status " + status;
  }
}

// Hop-by-hop / already-decoded headers that must not be forwarded
// (mirrors ProxyClient.java filtering)
function shouldForwardResponseHeader(name: string): boolean {
  const lower = name.toLowerCase();
  if (lower === "content-encoding" || lower === "content-length") return false;
  if (lower.startsWith("access-control-")) return false;
  if (lower === "transfer-encoding" || lower === "connection") return false;
  return true;
}

async function handleProxy(req: Request): Promise<Response> {
  const url = new URL(req.url);
  const rawEncoded = url.pathname.slice(PROXY_PREFIX.length);
  let decoded: string;
  try {
    decoded = decodeURIComponent(rawEncoded);
  } catch {
    return errorResponse(400, "bad proxy target");
  }

  let target: URL;
  try {
    target = new URL(decoded);
  } catch {
    return errorResponse(400, "bad proxy target");
  }

  const protocol = target.protocol.replace(/:$/, "");
  if (!isAllowed(protocol, target.hostname)) {
    return errorResponse(
      400,
      `target not allowed: ${protocol}://${target.hostname}`,
    );
  }

  const forwardHeaders: Record<string, string> = {};
  for (const [name, value] of req.headers) {
    const lower = name.toLowerCase();
    if (lower.startsWith(HEADER_PREFIX)) {
      const real = name.slice(HEADER_PREFIX.length);
      forwardHeaders[real] = value;
    }
  }

  // Forward method + body (GET-only today but forward body if present for future POST)
  const method = req.method;
  let body: BodyInit | undefined;
  if (method !== "GET" && method !== "HEAD") {
    // Clone body as ArrayBuffer to allow fetch
    try {
      const buf = await req.arrayBuffer();
      if (buf.byteLength > 0) body = buf;
    } catch {
      void 0;
    }
  }

  let proxied: Response;
  try {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), PROXY_TIMEOUT_MS);
    try {
      proxied = await fetch(target.href, {
        method,
        headers: forwardHeaders,
        body,
        redirect: "follow",
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timeout);
    }
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    if (msg.includes("abort")) {
      return errorResponse(502, "proxy request timed out");
    }
    return errorResponse(502, "proxy request failed: " + msg);
  }

  const headers = new Headers();
  for (const [k, v] of proxied.headers) {
    if (!shouldForwardResponseHeader(k)) continue;
    headers.set(k, v);
  }

  // Don't set content-length; let Deno chunk it
  const status = proxied.status;
  // Use proxied body as stream to avoid buffering large bodies
  return new Response(proxied.body, {
    status,
    statusText: statusText(status),
    headers,
  });
}

async function handleRuntime(): Promise<Response> {
  const text = await loadRuntimeJs();
  return new Response(text, {
    status: 200,
    headers: { "content-type": "text/javascript; charset=utf-8" },
  });
}

interface ExecRequest {
  cmd?: string;
  args?: unknown;
  timeoutMs?: unknown;
}

function parseExecRequest(
  encoded: string,
): { req: ExecRequest; error?: string } {
  let decoded: string;
  try {
    decoded = decodeURIComponent(encoded);
  } catch (e) {
    return {
      req: {},
      error: "bad exec request: " +
        (e instanceof Error ? e.message : String(e)),
    };
  }
  try {
    const obj = JSON.parse(decoded) as ExecRequest;
    return { req: obj };
  } catch (e) {
    return {
      req: {},
      error: "bad exec request: " +
        (e instanceof Error ? e.message : String(e)),
    };
  }
}

async function handleExec(
  _req: Request,
  encoded: string,
  opts?: HandleOptions,
): Promise<Response> {
  if (!opts?.exec?.enabled) {
    return errorResponse(
      404,
      "exec disabled — pass { exec: { enabled: true } } to handleDenoapkRequest to enable",
    );
  }

  const { req: body, error } = parseExecRequest(encoded);
  if (error) return errorResponse(400, error);

  const cmd = body.cmd;
  if (!cmd || typeof cmd !== "string" || cmd.length === 0) {
    return errorResponse(400, "cmd must not be empty");
  }

  const args: string[] = Array.isArray(body.args)
    ? (body.args as unknown[]).map((a) => String(a))
    : [];

  let timeoutMs = DEFAULT_TIMEOUT_MS;
  if (typeof body.timeoutMs === "number" && body.timeoutMs > 0) {
    timeoutMs = Math.min(body.timeoutMs, MAX_TIMEOUT_MS);
  }

  const argv = [cmd, ...args];

  let process: Deno.ChildProcess;
  try {
    const command = new Deno.Command(argv[0], {
      args: argv.slice(1),
      stdout: "piped",
      stderr: "piped",
      stdin: "null",
    });
    process = command.spawn();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return errorResponse(500, "exec unavailable: " + msg);
  }

  // Read stdout/stderr concurrently, with hard timeout
  const stdoutPromise = new Response(process.stdout).text().catch(() => "");
  const stderrPromise = new Response(process.stderr).text().catch(() => "");
  const statusPromise = process.status.catch(
    () => ({ success: false, code: 1, signal: null } as Deno.CommandStatus),
  );

  let timedOut = false;
  let status: Deno.CommandStatus | null = null;
  let stdout = "";
  let stderr = "";

  try {
    const timeoutPromise = new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error("timeout")), timeoutMs)
    );

    const result = await Promise.race([
      Promise.all([stdoutPromise, stderrPromise, statusPromise]).then((
        [o, e, s],
      ) => ({ o, e, s })),
      timeoutPromise,
    ]) as { o: string; e: string; s: Deno.CommandStatus };

    stdout = result.o;
    stderr = result.e;
    status = result.s;
  } catch {
    timedOut = true;
    try {
      process.kill("SIGTERM");
    } catch {
      void 0;
    }
    try {
      // Give it a moment to exit after SIGTERM before SIGKILL (ExecClient does destroy+destroyForcibly)
      await new Promise((r) => setTimeout(r, 100));
      try {
        process.kill("SIGKILL");
      } catch {
        void 0;
      }
    } catch {
      void 0;
    }
    // Try to get whatever output we can, with a short grace
    try {
      const res = await Promise.race([
        Promise.all([stdoutPromise, stderrPromise, statusPromise]).then((
          [o, e, s],
        ) => ({ o, e, s })),
        new Promise<null>((resolve) => setTimeout(() => resolve(null), 2000)),
      ]) as { o: string; e: string; s: Deno.CommandStatus } | null;
      if (res) {
        stdout = res.o;
        stderr = res.e;
        status = res.s;
      }
    } catch {
      void 0;
    }
  }

  // Ensure process is reaped
  try {
    if (!timedOut && status === null) {
      status = await statusPromise;
    }
  } catch {
    void 0;
  }

  const bodyJson = {
    ok: !timedOut && status !== null && status.success,
    exitCode: timedOut || status === null ? null : status.code,
    stdout: stdout ?? "",
    stderr: stderr ?? "",
    timedOut,
  };

  return new Response(JSON.stringify(bodyJson), {
    status: 200,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function handleExecStream(
  _req: Request,
  encoded: string,
  opts?: HandleOptions,
): Response | Promise<Response> {
  if (!opts?.exec?.enabled) {
    return errorResponse(
      404,
      "exec disabled — pass { exec: { enabled: true } } to handleDenoapkRequest to enable",
    );
  }

  const { req: body, error } = parseExecRequest(encoded);
  if (error) return errorResponse(400, error);

  const cmd = body.cmd;
  if (!cmd || typeof cmd !== "string" || cmd.length === 0) {
    return errorResponse(400, "cmd must not be empty");
  }

  const args: string[] = Array.isArray(body.args)
    ? (body.args as unknown[]).map((a) => String(a))
    : [];

  let process: Deno.ChildProcess;
  try {
    const command = new Deno.Command(cmd, {
      args,
      stdout: "piped",
      stderr: "piped",
      stdin: "null",
    });
    process = command.spawn();
  } catch (e) {
    const msg = e instanceof Error ? e.message : String(e);
    return errorResponse(500, "exec unavailable: " + msg);
  }

  // Merge stdout+stderr like ExecStreamBridge redirectErrorStream(true)
  // Create a stream that yields text chunks as they arrive
  // Client disconnect is handled via ReadableStream.cancel(), not request.signal
  // (avoids Deno's legacy abort warning)
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const pump = async (readable: ReadableStream<Uint8Array>) => {
        const reader = readable.getReader();
        try {
          while (true) {
            const { done, value } = await reader.read();
            if (done) break;
            // Convert bytes to text then back to bytes to ensure utf-8 boundaries?
            // Actually just enqueue raw bytes — TextDecoderStream on client handles it.
            // But to keep text correctness, enqueue as-is
            if (value && value.length > 0) {
              try {
                controller.enqueue(value);
              } catch {
                break;
              }
            }
          }
        } catch {
          void 0;
        } finally {
          try {
            reader.releaseLock();
          } catch {
            void 0;
          }
        }
      };

      // Run both pumps concurrently, close when both done
      await Promise.all([pump(process.stdout), pump(process.stderr)]);
      // Wait for process to exit
      try {
        await process.status;
      } catch {
        void 0;
      }
      try {
        controller.close();
      } catch {
        void 0;
      }
    },
    cancel() {
      try {
        process.kill("SIGTERM");
      } catch {
        void 0;
      }
      try {
        process.kill("SIGKILL");
      } catch {
        void 0;
      }
    },
  });

  return new Response(stream, {
    status: 200,
    headers: {
      "content-type": "text/plain; charset=utf-8",
      "cache-control": "no-store",
    },
  });
}

/**
 * Handle a `__denoapk/*` request if it is one, otherwise return `null`
 * to let the app's own handler run.
 *
 * Handles:
 *   /__denoapk/runtime.js
 *   /__denoapk/proxy/<encoded-url>
 *   /__denoapk/exec/<encoded-json>          (opt-in)
 *   /__denoapk/exec-stream/<encoded-json>   (opt-in)
 */
export function handleDenoapkRequest(
  req: Request,
  opts?: HandleOptions,
): Promise<Response | null> {
  const url = new URL(req.url);
  const path = url.pathname;

  if (path === RUNTIME_PATH) {
    return handleRuntime();
  }
  if (path.startsWith(PROXY_PREFIX)) {
    if (path.length <= PROXY_PREFIX.length) {
      return Promise.resolve(errorResponse(400, "bad proxy target"));
    }
    return handleProxy(req);
  }
  if (path.startsWith(EXEC_STREAM_PREFIX)) {
    const encoded = path.slice(EXEC_STREAM_PREFIX.length);
    if (!encoded) {
      return Promise.resolve(errorResponse(400, "bad exec request"));
    }
    return Promise.resolve(handleExecStream(req, encoded, opts));
  }
  if (path.startsWith(EXEC_PREFIX)) {
    const encoded = path.slice(EXEC_PREFIX.length);
    if (!encoded) {
      return Promise.resolve(errorResponse(400, "bad exec request"));
    }
    return handleExec(req, encoded, opts);
  }

  return Promise.resolve(null);
}
