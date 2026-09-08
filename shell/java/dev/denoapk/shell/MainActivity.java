package dev.denoapk.shell;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.IOException;
import java.io.InputStream;

/**
 * The denoapk shell: a WebView serving the app's bundled web assets.
 *
 * Assets are served from https://appassets.androidplatform.net rather than
 * file:// because that is a secure origin, which is what makes localStorage
 * and crypto.subtle available. The app keeps its tokens in localStorage, so
 * this is load-bearing, not cosmetic.
 *
 * Two paths are handled specially, matching what the Deno host does:
 *
 *   /__denoapk/runtime.js       the fetch shim, from assets
 *   /__denoapk/proxy/<url>      a request the page cannot make itself
 *
 * Everything else maps to assets/www/. We implement the asset mapping directly
 * instead of using androidx.webkit's WebViewAssetLoader so the shell builds
 * against android.jar alone, with no AAR dependencies to resolve.
 *
 * Camera permission (for QR-code scanning and similar) works the same way:
 * `Activity.requestPermissions`/`checkSelfPermission`/`onRequestPermissionsResult`
 * are plain framework APIs since API 23 — not the androidx `ActivityCompat`/
 * `ContextCompat` wrappers, which would need an AAR dependency this shell
 * deliberately avoids everywhere else.
 *
 * Whether a given app can actually be granted CAMERA is decided per app, at
 * build time, by whether its deno.json declared `android.permissions:
 * ["camera"]` (see src/build.ts's manifestFor()) — this shared class runs
 * identically for every app regardless. An app that didn't opt in never
 * declared the permission in its manifest, so `requestPermissions` below is
 * auto-denied by the OS with no dialog shown; nothing here needs to know
 * per-app whether camera was requested.
 */
public final class MainActivity extends Activity {

  private static final String TAG = "denoapk";
  private static final String BASE_URL = "https://" + Router.ASSET_HOST + "/";
  private static final int CAMERA_PERMISSION_REQUEST_CODE = 1001;

  private WebView webView;

  /**
   * The WebView's pending request while we wait for the OS permission dialog.
   * WebView permission requests are answered asynchronously — onPermissionRequest
   * does not have to grant/deny before returning, so we hold this and resolve it
   * once onRequestPermissionsResult fires. Single slot, not a queue: this app
   * only ever has one WebView with one getUserMedia() call in flight at a time,
   * so a second concurrent request (which never happens in practice here) would
   * simply overwrite this rather than being queued.
   */
  private PermissionRequest pendingPermissionRequest;

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
    webView.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onPermissionRequest(PermissionRequest request) {
        handlePermissionRequest(request);
      }

      // Without this override, page console.log/warn/error never reach
      // logcat at all — WebView has no default console-forwarding behaviour,
      // unlike a full browser's own devtools. Forwarding it here is what
      // makes any future "something silently isn't working" debuggable.
      @Override
      public boolean onConsoleMessage(android.webkit.ConsoleMessage cm) {
        String where = cm.sourceId() + ":" + cm.lineNumber();
        switch (cm.messageLevel()) {
          case ERROR:
            Log.e(TAG, "[console] " + where + " " + cm.message());
            break;
          case WARNING:
            Log.w(TAG, "[console] " + where + " " + cm.message());
            break;
          default:
            Log.i(TAG, "[console] " + where + " " + cm.message());
        }
        return true;
      }
    });

    // Apps targeting SDK 35 get edge-to-edge forced on by the system: content
    // draws from y=0 under the status/nav bars unless something pads it.
    //
    // The padding cannot go on the WebView itself — WebView's own rendering
    // pipeline does not honour View.setPadding() (a known quirk: the padded
    // area appears in layout but the page still draws into it). A plain
    // container laid out around the WebView does not have that problem, so
    // the container gets the padding and the WebView is simply sized to fit
    // what's left of it.
    FrameLayout root = new FrameLayout(this);
    root.addView(webView, new FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
      @Override
      public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
        v.setPadding(
            insets.getSystemWindowInsetLeft(),
            insets.getSystemWindowInsetTop(),
            insets.getSystemWindowInsetRight(),
            insets.getSystemWindowInsetBottom());
        return insets.consumeSystemWindowInsets();
      }
    });

    setContentView(root);
    webView.loadUrl(BASE_URL + "index.html");
  }

  /**
   * Decide whether to grant a getUserMedia()-style request from the page.
   * Runs on the UI thread, matching where onPermissionRequest is documented
   * to be called and where requestPermissions() must be called from.
   */
  private void handlePermissionRequest(PermissionRequest request) {
    String[] grantable = Permissions.grantable(request.getResources());
    if (grantable.length == 0) {
      request.deny();
      return;
    }

    if (checkSelfPermission(Manifest.permission.CAMERA)
        == PackageManager.PERMISSION_GRANTED) {
      request.grant(grantable);
      return;
    }

    // Not yet granted at the OS level: ask now and answer this request once
    // the user responds, in onRequestPermissionsResult below. If this app's
    // manifest never declared CAMERA (it didn't opt in via android.permissions
    // in deno.json), the OS auto-denies this with no dialog at all — same code
    // path either way.
    pendingPermissionRequest = request;
    requestPermissions(new String[] { Manifest.permission.CAMERA },
        CAMERA_PERMISSION_REQUEST_CODE);
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode != CAMERA_PERMISSION_REQUEST_CODE) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
      return;
    }
    PermissionRequest pending = pendingPermissionRequest;
    pendingPermissionRequest = null;
    if (pending == null) return;

    boolean granted = grantResults.length > 0
        && grantResults[0] == PackageManager.PERMISSION_GRANTED;
    if (granted) {
      pending.grant(Permissions.grantable(pending.getResources()));
    } else {
      pending.deny();
    }
  }

  /** Route a request, or return null to let the WebView handle it normally. */
  private WebResourceResponse handle(WebResourceRequest request) {
    Uri uri = request.getUrl();
    Router.Route route = Router.route(uri.getHost(), uri.getPath());
    switch (route.kind) {
      case PROXY:
        return ProxyClient.perform(request, route.detail);
      case RUNTIME:
        return asset(route.detail, "text/javascript");
      case ASSET:
        return asset(route.detail, Router.mimeOf(route.detail));
      case PASSTHROUGH:
      default:
        return null;
    }
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

  @Override
  public void onBackPressed() {
    if (webView != null && webView.canGoBack()) {
      webView.goBack();
    } else {
      super.onBackPressed();
    }
  }
}
