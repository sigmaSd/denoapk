import dev.denapk.shell.Router;

/**
 * Plain-JVM tests for the shell's request routing.
 *
 * Router is deliberately free of Android types so this can run without a
 * device or emulator. Run via `deno task test:shell`.
 */
public final class RouterTest {

  private static int failures = 0;

  public static void main(String[] args) {
    Router.Route r;

    // The page itself.
    r = Router.route(Router.ASSET_HOST, "/index.html");
    eq("ASSET:www/index.html", r.toString(), "index.html");

    // A bare root must land on index.html, not on the directory.
    r = Router.route(Router.ASSET_HOST, "/");
    eq("ASSET:www/index.html", r.toString(), "root maps to index");
    r = Router.route(Router.ASSET_HOST, "");
    eq("ASSET:www/index.html", r.toString(), "empty path maps to index");
    r = Router.route(Router.ASSET_HOST, null);
    eq("ASSET:www/index.html", r.toString(), "null path maps to index");

    // The bundle, reached by a relative src in index.html.
    r = Router.route(Router.ASSET_HOST, "/dist/app.js");
    eq("ASSET:www/dist/app.js", r.toString(), "nested asset");

    // The shim is served from the assets root, not from www/.
    r = Router.route(Router.ASSET_HOST, "/__denapk/runtime.js");
    eq("RUNTIME:runtime.js", r.toString(), "runtime shim");

    // The proxy carries the encoded target through untouched.
    String encoded = "https%3A%2F%2Fclaude.ai%2Fapi%2Forganizations";
    r = Router.route(Router.ASSET_HOST, "/__denapk/proxy/" + encoded);
    eq("PROXY:" + encoded, r.toString(), "proxy target survives routing");

    // Anything not on the asset host is none of our business.
    r = Router.route("claude.ai", "/api/organizations");
    eq("PASSTHROUGH:null", r.toString(), "foreign host passes through");
    r = Router.route(null, "/index.html");
    eq("PASSTHROUGH:null", r.toString(), "null host passes through");

    // A path that merely looks like the proxy prefix must not be treated as one.
    r = Router.route(Router.ASSET_HOST, "/__denapk/proxynot/x");
    eq("ASSET:www/__denapk/proxynot/x", r.toString(), "near-miss prefix");

    // MIME types drive whether the WebView parses or downloads a response.
    eq("text/html", Router.mimeOf("www/index.html"), "html mime");
    eq("text/javascript", Router.mimeOf("www/dist/app.js"), "js mime");
    eq("image/png", Router.mimeOf("www/icon.PNG"), "uppercase extension");
    eq("application/octet-stream", Router.mimeOf("www/README"), "no extension");
    // A dot in a directory name must not be read as the file's extension.
    eq("application/octet-stream", Router.mimeOf("www/v1.2/LICENSE"), "dot in dir");

    if (failures > 0) {
      System.err.println(failures + " failure(s)");
      System.exit(1);
    }
    System.out.println("shell routing: all assertions passed");
  }

  private static void eq(String expected, String actual, String what) {
    if (!expected.equals(actual)) {
      System.err.println("FAIL " + what + ": expected " + expected + ", got " + actual);
      failures++;
    }
  }
}
