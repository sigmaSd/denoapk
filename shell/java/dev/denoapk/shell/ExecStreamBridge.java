package dev.denoapk.shell;

import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Streams a subprocess's output to the page by pushing it proactively, via
 * WebView.evaluateJavascript(), instead of the page pulling it via fetch().
 *
 * This exists because fetch()-based streaming (an earlier ExecStreamClient /
 * EXEC_STREAM route, both removed) turned out not to work: verified on-device
 * that WebResourceResponse's InputStream is read to EOF internally before
 * anything reaches the page's fetch() Response — an unbounded process (e.g.
 * continuous ping) would never deliver a single byte. addJavascriptInterface
 * is a genuinely different mechanism (the same pattern denoapk's README
 * already names as the way to add a POST transport): native code calls into
 * the page instead of the page pulling from native code, so there's no
 * "buffer until EOF" step to get stuck on.
 *
 * @JavascriptInterface annotations are required since API 17 (MIN_SDK here
 * is 24) — without it a method is not reachable from JS at all, which is
 * what keeps this from exposing anything beyond the two methods below.
 */
final class ExecStreamBridge {

  private static final String TAG = "denoapk";
  private final WebView webView;
  private final Map<String, Process> running = new ConcurrentHashMap<>();
  private final AtomicLong nextId = new AtomicLong(1);

  ExecStreamBridge(WebView webView) {
    this.webView = webView;
  }

  /** Starts cmd+args (JSON: {"cmd":"ping" (or an absolute path),"args":[...]}), returns a stream id, or null if it couldn't start. */
  @JavascriptInterface
  public String start(String requestJson) {
    String cmd;
    List<String> args = new ArrayList<>();
    try {
      JSONObject req = new JSONObject(requestJson);
      cmd = req.optString("cmd", null);
      JSONArray argsJson = req.optJSONArray("args");
      if (argsJson != null) {
        for (int i = 0; i < argsJson.length(); i++) args.add(argsJson.getString(i));
      }
    } catch (Exception e) {
      Log.w(TAG, "[exec-stream] bad request: " + e.getMessage());
      return null;
    }
    // A bare name (e.g. "ping") is resolved via $PATH by ProcessBuilder
    // itself, same as an absolute path -- see ExecClient's doc comment for
    // why that's fine (no shell involved, so no injection risk in doing so).
    if (cmd == null || cmd.isEmpty()) {
      Log.w(TAG, "[exec-stream] cmd must not be empty");
      return null;
    }

    List<String> argv = new ArrayList<>();
    argv.add(cmd);
    argv.addAll(args);

    Process process;
    try {
      process = new ProcessBuilder(argv).redirectErrorStream(true).start();
    } catch (IOException e) {
      Log.e(TAG, "[exec-stream] failed to start " + cmd, e);
      return null;
    }

    String id = "s" + nextId.getAndIncrement();
    running.put(id, process);

    Thread reader = new Thread(() -> pump(id, process), "denoapk-execstream-" + id);
    reader.setDaemon(true);
    reader.start();
    return id;
  }

  /** Kills the process behind a stream id. Safe to call after it already ended on its own. */
  @JavascriptInterface
  public void cancel(String id) {
    Process process = running.remove(id);
    if (process == null) return;
    process.destroy();
    try {
      process.destroyForcibly();
    } catch (Throwable ignored) {
      // destroyForcibly() only exists since API 26; MIN_SDK here is 24.
    }
  }

  /** Runs on its own thread per stream: reads until EOF, pushing each chunk as it arrives. */
  private void pump(String id, Process process) {
    try {
      InputStream in = process.getInputStream();
      byte[] buf = new byte[1024];
      int n;
      while ((n = in.read(buf)) != -1) {
        push(id, new String(buf, 0, n, "UTF-8"), false);
      }
    } catch (IOException e) {
      // Expected when cancel() destroys the process mid-read; nothing to log.
    } finally {
      running.remove(id);
      push(id, "", true);
    }
  }

  /** evaluateJavascript must run on the UI thread; this may be called from the pump thread. */
  private void push(String id, String chunk, boolean done) {
    webView.post(() -> {
      String js = "window.__denoapkExecStreamPush && window.__denoapkExecStreamPush(" +
          JSONObject.quote(id) + "," + JSONObject.quote(chunk) + "," + done + ")";
      webView.evaluateJavascript(js, null);
    });
  }
}
