package dev.denapk.shell;

/**
 * Request routing, kept free of Android types so it can be tested on a plain
 * JVM. MainActivity does the Android-specific parts (reading assets, opening
 * connections); everything about *which* handler a path belongs to lives here.
 */
public final class Router {

  public static final String ASSET_HOST = "appassets.androidplatform.net";
  public static final String PROXY_PREFIX = "/__denapk/proxy/";
  public static final String RUNTIME_PATH = "/__denapk/runtime.js";

  /** What a request should be served by. */
  public enum Kind {
    /** Not ours — let the WebView handle it normally. */
    PASSTHROUGH,
    /** Replay it natively; `detail` is the encoded target URL. */
    PROXY,
    /** The fetch shim; `detail` is the asset name. */
    RUNTIME,
    /** A bundled web asset; `detail` is the asset name. */
    ASSET,
  }

  public static final class Route {
    public final Kind kind;
    public final String detail;

    Route(Kind kind, String detail) {
      this.kind = kind;
      this.detail = detail;
    }

    @Override
    public String toString() {
      return kind + ":" + detail;
    }
  }

  private Router() {}

  public static Route route(String host, String path) {
    if (!ASSET_HOST.equals(host)) {
      // Cross-origin requests should have been rewritten by the shim. Letting
      // one through unchanged means it hits the network and fails CORS, which
      // is exactly what a plain browser would do.
      return new Route(Kind.PASSTHROUGH, null);
    }
    if (path == null || path.isEmpty()) path = "/";

    if (path.startsWith(PROXY_PREFIX)) {
      return new Route(Kind.PROXY, path.substring(PROXY_PREFIX.length()));
    }
    if (RUNTIME_PATH.equals(path)) {
      return new Route(Kind.RUNTIME, "runtime.js");
    }
    if (path.equals("/")) path = "/index.html";
    return new Route(Kind.ASSET, "www" + path);
  }

  public static String mimeOf(String path) {
    int slash = path.lastIndexOf('/');
    int dot = path.lastIndexOf('.');
    String ext = (dot < 0 || dot < slash) ? "" : path.substring(dot + 1);
    switch (ext.toLowerCase()) {
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
}
