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
 * Three paths are handled specially, matching what the Deno host does:
 *
 *   /__denoapk/runtime.js       the fetch shim, from assets
 *   /__denoapk/proxy/<url>      a request the page cannot make itself
 *   /__denoapk/exec/<req>       run a native subprocess (see ExecClient)
 *
 * Everything else maps to assets/www/. We implement the asset mapping directly
 * instead of using androidx.webkit's WebViewAssetLoader so the shell builds
 * against android.jar alone, with no AAR dependencies to resolve.
 *
 * A fourth capability, streaming a subprocess's output (denoapk.execStream()
 * in runtime.js), is NOT one of the paths above — fetch()'s WebResourceResponse
 * can't actually stream on this WebView (verified: it buffers to EOF before
 * the page sees anything). It goes through ExecStreamBridge instead, an
 * addJavascriptInterface object that pushes each chunk to the page directly
 * via evaluateJavascript() rather than the page pulling via fetch().
 *
 * Camera and microphone permission (for QR-code scanning, audio monitoring
 * and similar) works the same way:
 * `Activity.requestPermissions`/`checkSelfPermission`/`onRequestPermissionsResult`
 * are plain framework APIs since API 23 — not the androidx `ActivityCompat`/
 * `ContextCompat` wrappers, which would need an AAR dependency this shell
 * deliberately avoids everywhere else.
 *
 * Whether a given app can actually be granted CAMERA or RECORD_AUDIO is
 * decided per app, at build time, by whether its deno.json declared
 * `android.permissions: ["camera", "microphone"]` (see src/build.ts's
 * manifestFor()) — this shared class runs identically for every app
 * regardless. An app that didn't opt in never declared the permission in its
 * manifest, so `requestPermissions` below is auto-denied by the OS with no
 * dialog shown; nothing here needs to know per-app what was requested.
 */
public final class MainActivity extends Activity {

  private static final String TAG = "denoapk";
  private static final String BASE_URL = "https://" + Router.ASSET_HOST + "/";
  private static final int MEDIA_PERMISSION_REQUEST_CODE = 1001;

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

    // Must be registered before loadUrl() so it's present for scripts that
    // run during initial page load (runtime.js's <script> tag included).
    webView.addJavascriptInterface(new ExecStreamBridge(webView), "DenoapkExecStreamBridge");

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
   *
   * Each WebView resource maps to one OS permission (video capture needs
   * CAMERA, audio capture needs RECORD_AUDIO). Only the missing ones are
   * requested; resources whose OS permission is already granted are granted
   * immediately. If the app's manifest never declared one (it didn't opt in
   * via android.permissions in deno.json), the OS auto-denies it with no
   * dialog at all — same code path either way.
   */
  private void handlePermissionRequest(PermissionRequest request) {
    String[] grantable = Permissions.grantable(request.getResources());
    if (grantable.length == 0) {
      request.deny();
      return;
    }

    String[] needed = osPermissionsFor(grantable);
    String[] missing = missingPermissions(needed);
    if (missing.length == 0) {
      request.grant(grantable);
      return;
    }

    // Not yet granted at the OS level: ask now and answer this request once
    // the user responds, in onRequestPermissionsResult below.
    pendingPermissionRequest = request;
    requestPermissions(missing, MEDIA_PERMISSION_REQUEST_CODE);
  }

  /** OS permissions backing the given WebView resources, deduplicated. */
  private static String[] osPermissionsFor(String[] resources) {
    boolean wantCamera = false;
    boolean wantMic = false;
    for (String r : resources) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) wantCamera = true;
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantMic = true;
    }
    if (wantCamera && wantMic) {
      return new String[] { Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO };
    } else if (wantCamera) {
      return new String[] { Manifest.permission.CAMERA };
    } else {
      return new String[] { Manifest.permission.RECORD_AUDIO };
    }
  }

  /** Subset of the given OS permissions the user has not granted yet. */
  private String[] missingPermissions(String[] permissions) {
    int count = 0;
    for (String p : permissions) {
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) count++;
    }
    String[] out = new String[count];
    int i = 0;
    for (String p : permissions) {
      if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) out[i++] = p;
    }
    return out;
  }

  /**
   * WebView resources whose OS permission was granted, in the order the page
   * requested them. A partial grant (mic allowed, camera denied) grants only
   * the allowed half rather than denying the whole request.
   */
  private static String[] grantedResources(String[] requested, String[] permissions, int[] grantResults) {
    boolean cameraOk = wasGranted(Manifest.permission.CAMERA, permissions, grantResults);
    boolean micOk = wasGranted(Manifest.permission.RECORD_AUDIO, permissions, grantResults);
    int count = 0;
    for (String r : requested) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && cameraOk) count++;
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && micOk) count++;
    }
    String[] out = new String[count];
    int i = 0;
    for (String r : requested) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && cameraOk) out[i++] = r;
      if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && micOk) out[i++] = r;
    }
    return out;
  }

  /**
   * Whether an OS permission ended up granted. Permissions that were already
   * granted were never re-requested, so absence from the result arrays means
   * granted — only an explicit denial in grantResults blocks its resource.
   */
  private static boolean wasGranted(String permission, String[] permissions, int[] grantResults) {
    for (int i = 0; i < permissions.length; i++) {
      if (permission.equals(permissions[i])) {
        return grantResults[i] == PackageManager.PERMISSION_GRANTED;
      }
    }
    return true;
  }

  @Override
  public void onRequestPermissionsResult(
      int requestCode, String[] permissions, int[] grantResults) {
    if (requestCode != MEDIA_PERMISSION_REQUEST_CODE) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);
      return;
    }
    PermissionRequest pending = pendingPermissionRequest;
    pendingPermissionRequest = null;
    if (pending == null) return;

    String[] granted = grantedResources(
        Permissions.grantable(pending.getResources()), permissions, grantResults);
    if (granted.length > 0) {
      pending.grant(granted);
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
      case EXEC:
        return ExecClient.perform(route.detail);
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
