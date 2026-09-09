package dev.denoapk.shell;

import android.util.Log;
import android.webkit.WebResourceResponse;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Runs a native subprocess and reports the result as JSON — a generic
 * "run this process, hand back stdout/stderr/exit code" bridge, not tied to
 * any one command. The page supplies both the binary and its arguments (see
 * runtime.js's denoapk.exec()); this class has no domain knowledge of what
 * it's running, the same way ProxyClient has no knowledge of which API a
 * proxied fetch() is actually calling.
 *
 * ProcessBuilder(List<String>) passes argv directly with no shell involved,
 * so classic shell-injection doesn't apply. `cmd` can be an absolute path or
 * a bare name (e.g. "ping") — a bare name is resolved via $PATH the same way
 * a plain Deno.Command("ping") already was on the desktop host before this
 * capability existed, confirmed to work the same way here (Android's own
 * ProcessBuilder does the $PATH search too, verified on-device — this
 * isn't a shell, so there's no injection risk in doing so, just ordinary
 * execvp-style resolution). This app deliberately runs whatever binary +
 * args the page asks for, with no allowlist — a real, wider trust boundary
 * than ProxyClient's (host-restricted) fetch proxy, chosen deliberately so
 * any future app can reuse this for whatever native primitive it needs
 * without denoapk maintaining a list of "known-safe" commands.
 *
 * shouldInterceptRequest already runs off the UI thread, so the blocking IO
 * here is correct rather than merely tolerated (same as ProxyClient).
 */
final class ExecClient {

  private static final String TAG = "denoapk";
  private static final long DEFAULT_TIMEOUT_MS = 10_000;
  private static final long MAX_TIMEOUT_MS = 60_000;

  private ExecClient() {}

  static WebResourceResponse perform(String encodedRequest) {
    String cmd;
    List<String> args = new ArrayList<>();
    long timeoutMs = DEFAULT_TIMEOUT_MS;
    try {
      String decoded = URLDecoder.decode(encodedRequest, "UTF-8");
      JSONObject req = new JSONObject(decoded);
      cmd = req.optString("cmd", null);
      JSONArray argsJson = req.optJSONArray("args");
      if (argsJson != null) {
        for (int i = 0; i < argsJson.length(); i++) args.add(argsJson.getString(i));
      }
      long requested = req.optLong("timeoutMs", DEFAULT_TIMEOUT_MS);
      if (requested > 0) timeoutMs = Math.min(requested, MAX_TIMEOUT_MS);
    } catch (Exception e) {
      return error(400, "bad exec request: " + e.getMessage());
    }

    if (cmd == null || cmd.isEmpty()) {
      return error(400, "cmd must not be empty");
    }

    List<String> argv = new ArrayList<>();
    argv.add(cmd);
    argv.addAll(args);

    Process process;
    try {
      process = new ProcessBuilder(argv).start();
    } catch (IOException e) {
      // If this ever fires on a real device, the exec-restriction risk
      // flagged during design has materialized — see denoapk's plan notes.
      Log.e(TAG, "failed to start " + cmd, e);
      return error(500, "exec unavailable: " + e.getMessage());
    }

    // Read stdout and stderr concurrently — reading them sequentially risks
    // deadlock if the process writes enough to fill the *other* stream's
    // pipe buffer while blocked waiting for us to drain the first one.
    StreamReader stdout = new StreamReader(process.getInputStream());
    StreamReader stderr = new StreamReader(process.getErrorStream());
    Thread stdoutThread = new Thread(stdout, "denoapk-exec-stdout");
    Thread stderrThread = new Thread(stderr, "denoapk-exec-stderr");
    stdoutThread.start();
    stderrThread.start();

    boolean finished = waitWithHardTimeout(process, timeoutMs);
    joinQuietly(stdoutThread);
    joinQuietly(stderrThread);

    try {
      JSONObject body = new JSONObject();
      body.put("ok", finished && process.exitValue() == 0);
      body.put("exitCode", finished ? process.exitValue() : JSONObject.NULL);
      body.put("stdout", stdout.result());
      body.put("stderr", stderr.result());
      body.put("timedOut", !finished);
      return jsonResponse(body);
    } catch (JSONException e) {
      // Building the response object itself failing means something is
      // structurally wrong, not that the command failed — surface it as a
      // server error rather than a misleading exec result.
      return error(500, "failed to build exec response: " + e.getMessage());
    }
  }

  /** Returns false if the process had to be force-killed instead of exiting on its own. */
  private static boolean waitWithHardTimeout(Process process, long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (isAlive(process)) {
      if (System.currentTimeMillis() >= deadline) {
        process.destroy();
        try {
          process.destroyForcibly();
        } catch (Throwable ignored) {
          // destroyForcibly() only exists since API 26; MIN_SDK here is 24.
          // destroy() above is the best available fallback on older devices.
        }
        return false;
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException ignored) {
      }
    }
    return true;
  }

  private static boolean isAlive(Process process) {
    try {
      process.exitValue();
      return false;
    } catch (IllegalThreadStateException e) {
      return true;
    }
  }

  private static void joinQuietly(Thread t) {
    try {
      t.join(2_000);
    } catch (InterruptedException ignored) {
    }
  }

  private static WebResourceResponse jsonResponse(JSONObject body) {
    byte[] bytes = body.toString().getBytes();
    return new WebResourceResponse(
        "application/json", "utf-8", 200, "OK",
        new HashMap<String, String>(), new ByteArrayInputStream(bytes));
  }

  private static WebResourceResponse error(int status, String message) {
    byte[] body = message.getBytes();
    return new WebResourceResponse(
        "text/plain", "utf-8", status, status == 400 ? "Bad Request" : "Server Error",
        new HashMap<String, String>(), new ByteArrayInputStream(body));
  }

  /** Drains an InputStream to a UTF-8 string on its own thread. */
  private static final class StreamReader implements Runnable {
    private final InputStream in;
    private volatile String result = "";

    StreamReader(InputStream in) {
      this.in = in;
    }

    @Override
    public void run() {
      try {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        result = out.toString("UTF-8");
      } catch (IOException e) {
        // A stream read failing (e.g. the process was force-killed mid-read)
        // just means partial/no output — not worth surfacing as its own error.
      }
    }

    String result() {
      return result;
    }
  }
}
