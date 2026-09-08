package dev.denoapk.shell;

import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Performs requests the page is not allowed to make itself.
 *
 * A WebView is a real browser: it silently drops Cookie, User-Agent, Referer
 * and Sec-Fetch-* from fetch(), and the provider APIs send no CORS headers.
 * So host/runtime.js rewrites cross-origin requests to /__denoapk/proxy/<url>
 * with every header moved under an `x-denoapk-h-` prefix, which survives. This
 * class restores the real names and replays the request from native code,
 * where neither restriction applies.
 *
 * shouldInterceptRequest already runs off the UI thread, so the blocking IO
 * here is correct rather than merely tolerated.
 */
final class ProxyClient {

  private static final String TAG = "denoapk";
  private static final String HEADER_PREFIX = "x-denoapk-h-";
  private static final int TIMEOUT_MS = 30_000;

  private ProxyClient() {}

  static WebResourceResponse perform(WebResourceRequest request, String encodedTarget) {
    URL target;
    try {
      String decoded = URLDecoder.decode(encodedTarget, "UTF-8");
      target = new URL(decoded);
      // https only, matching android:usesCleartextTraffic="false" in the
      // manifest. Allowing http here would route around that setting, since
      // the connection is made from native code rather than by the WebView.
      if (!"https".equals(target.getProtocol())) {
        return error(400, "only https targets are proxied");
      }
    } catch (Exception e) {
      return error(400, "bad proxy target");
    }

    HttpURLConnection conn = null;
    try {
      conn = (HttpURLConnection) target.openConnection();
      conn.setRequestMethod(request.getMethod());
      conn.setConnectTimeout(TIMEOUT_MS);
      conn.setReadTimeout(TIMEOUT_MS);
      conn.setInstanceFollowRedirects(true);

      for (Map.Entry<String, String> e : request.getRequestHeaders().entrySet()) {
        String name = e.getKey();
        if (name.length() > HEADER_PREFIX.length()
            && name.regionMatches(true, 0, HEADER_PREFIX, 0, HEADER_PREFIX.length())) {
          conn.setRequestProperty(name.substring(HEADER_PREFIX.length()), e.getValue());
        }
      }

      int status = conn.getResponseCode();
      // A 4xx/5xx body arrives on the error stream, and the page needs it —
      // the providers report auth failures as JSON.
      InputStream body = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
      if (body == null) body = new ByteArrayInputStream(new byte[0]);

      String contentType = conn.getContentType();
      String mime = "application/octet-stream";
      String charset = "utf-8";
      if (contentType != null) {
        int semi = contentType.indexOf(';');
        mime = (semi < 0 ? contentType : contentType.substring(0, semi)).trim();
        int cs = contentType.toLowerCase().indexOf("charset=");
        if (cs >= 0) {
          charset = contentType.substring(cs + 8).trim();
          int end = charset.indexOf(';');
          if (end >= 0) charset = charset.substring(0, end).trim();
        }
      }

      Map<String, String> headers = new HashMap<>();
      for (Map.Entry<String, List<String>> e : conn.getHeaderFields().entrySet()) {
        String name = e.getKey();
        // The status line comes back under a null key.
        if (name == null || e.getValue().isEmpty()) continue;
        String lower = name.toLowerCase();
        // HttpURLConnection already decoded the body, so a leftover
        // Content-Encoding would make the WebView try to decode it again.
        if (lower.equals("content-encoding") || lower.equals("content-length")) continue;
        if (lower.startsWith("access-control-")) continue;
        if (lower.equals("transfer-encoding") || lower.equals("connection")) continue;
        headers.put(name, e.getValue().get(0));
      }

      String reason = conn.getResponseMessage();
      // WebResourceResponse rejects a null or empty reason phrase.
      if (reason == null || reason.isEmpty()) reason = statusText(status);

      return new WebResourceResponse(mime, charset, status, reason, headers, body);
    } catch (IOException e) {
      Log.w(TAG, "proxy failed for " + target, e);
      if (conn != null) conn.disconnect();
      return error(502, "proxy request failed: " + e.getMessage());
    }
  }

  private static WebResourceResponse error(int status, String message) {
    byte[] body = message.getBytes();
    return new WebResourceResponse(
        "text/plain", "utf-8", status, statusText(status),
        new HashMap<String, String>(), new ByteArrayInputStream(body));
  }

  private static String statusText(int status) {
    switch (status) {
      case 200: return "OK";
      case 400: return "Bad Request";
      case 401: return "Unauthorized";
      case 403: return "Forbidden";
      case 404: return "Not Found";
      case 502: return "Bad Gateway";
      default:  return "Status " + status;
    }
  }
}
