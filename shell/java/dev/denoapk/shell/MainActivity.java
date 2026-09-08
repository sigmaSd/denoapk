package dev.denoapk.shell;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
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
 */
public final class MainActivity extends Activity {

  private static final String TAG = "denoapk";
  private static final String BASE_URL = "https://" + Router.ASSET_HOST + "/";

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
