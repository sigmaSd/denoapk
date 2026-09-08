package dev.denapk.shell;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.IOException;
import java.io.InputStream;

/**
 * The denapk shell: a WebView serving the app's bundled web assets.
 *
 * Assets are served from https://appassets.androidplatform.net rather than
 * file:// because that is a secure origin, which is what makes localStorage
 * and crypto.subtle available. The app keeps its tokens in localStorage, so
 * this is load-bearing, not cosmetic.
 *
 * Two paths are handled specially, matching what the Deno host does:
 *
 *   /__denapk/runtime.js       the fetch shim, from assets
 *   /__denapk/proxy/<url>      a request the page cannot make itself
 *
 * Everything else maps to assets/www/. We implement the asset mapping directly
 * instead of using androidx.webkit's WebViewAssetLoader so the shell builds
 * against android.jar alone, with no AAR dependencies to resolve.
 */
public final class MainActivity extends Activity {

  private static final String TAG = "denapk";
  private static final String ASSET_HOST = "appassets.androidplatform.net";
  private static final String BASE_URL = "https://" + ASSET_HOST + "/";
  private static final String PROXY_PREFIX = "/__denapk/proxy/";
  private static final String RUNTIME_PATH = "/__denapk/runtime.js";

  private WebView webView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    webView = new WebView(this);
    WebSettings settings = webView.getSettings();
    settings.setJavaScriptEnabled(true);
    // localStorage lives behind this flag.
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);

    webView.setWebViewClient(new WebViewClient() {
      @Override
      public WebResourceResponse shouldInterceptRequest(
          WebView view, WebResourceRequest request) {
        return handle(request);
      }
    });

    setContentView(webView);
    webView.loadUrl(BASE_URL + "index.html");
  }

  /** Route a request, or return null to let the WebView handle it normally. */
  private WebResourceResponse handle(WebResourceRequest request) {
    Uri uri = request.getUrl();
    if (!ASSET_HOST.equals(uri.getHost())) {
      // Cross-origin requests should have been rewritten by the shim; letting
      // them through unchanged means they hit the network and fail CORS, which
      // is the same behaviour as a plain browser.
      return null;
    }

    String path = uri.getPath();
    if (path == null) path = "/";

    if (path.startsWith(PROXY_PREFIX)) {
      return ProxyClient.perform(request, path.substring(PROXY_PREFIX.length()));
    }
    if (RUNTIME_PATH.equals(path)) {
      return asset("runtime.js", "text/javascript");
    }
    if (path.equals("/")) path = "/index.html";
    return asset("www" + path, mimeOf(path));
  }

  private WebResourceResponse asset(String name, String mime) {
    try {
      InputStream in = getAssets().open(name);
      WebResourceResponse res =
          new WebResourceResponse(mime, "utf-8", in);
      // Assets are same-origin to the page; no CORS headers needed.
      return res;
    } catch (IOException e) {
      Log.w(TAG, "asset not found: " + name);
      return new WebResourceResponse(
          "text/plain", "utf-8", 404, "Not Found", null,
          new java.io.ByteArrayInputStream(new byte[0]));
    }
  }

  static String mimeOf(String path) {
    int dot = path.lastIndexOf('.');
    String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
    switch (ext) {
      case "html": return "text/html";
      case "js":   return "text/javascript";
      case "css":  return "text/css";
      case "json": return "application/json";
      case "svg":  return "image/svg+xml";
      case "png":  return "image/png";
      case "jpg":
      case "jpeg": return "image/jpeg";
      case "webp": return "image/webp";
      case "gif":  return "image/gif";
      case "woff2": return "font/woff2";
      case "woff": return "font/woff";
      case "ttf":  return "font/ttf";
      case "ico":  return "image/x-icon";
      default:     return "application/octet-stream";
    }
  }

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
    } else {
      super.onBackPressed();
    }
  }
}
