package dev.denoapk.shell;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Carries a proxied response's Set-Cookie lines back to the page.
 *
 * A WebView hides Set-Cookie from fetch() entirely, so forwarding it as-is is
 * useless to the page (and would only plant the target's cookies on the app's
 * own origin). Instead every Set-Cookie line goes out as one JSON array under
 * {@link #HEADER}, which runtime.js exposes as Headers.getSetCookie(). JSON
 * rather than a joined list because Expires dates contain commas.
 *
 * No Android imports, so SetCookiesTest runs on a bare JDK.
 */
public final class SetCookies {

  public static final String HEADER = "x-denoapk-set-cookie";

  private SetCookies() {}

  /** Whether {@code name} is a Set-Cookie header, which the caller must not forward. */
  public static boolean isSetCookie(String name) {
    return name != null && name.equalsIgnoreCase("set-cookie");
  }

  /**
   * The {@link #HEADER} value for HttpURLConnection-style header fields, or
   * null when the response sets no cookies.
   */
  public static String headerValue(Map<String, List<String>> fields) {
    List<String> cookies = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : fields.entrySet()) {
      if (!isSetCookie(e.getKey()) || e.getValue() == null) continue;
      for (String v : e.getValue()) {
        if (v != null) cookies.add(v);
      }
    }
    return cookies.isEmpty() ? null : toJson(cookies);
  }

  /** A JSON array of strings, pure ASCII so it is always a legal header value. */
  static String toJson(List<String> values) {
    StringBuilder out = new StringBuilder("[");
    for (int i = 0; i < values.size(); i++) {
      if (i > 0) out.append(',');
      out.append('"');
      String s = values.get(i);
      for (int j = 0; j < s.length(); j++) {
        char c = s.charAt(j);
        if (c == '"' || c == '\\') {
          out.append('\\').append(c);
        } else if (c < 0x20 || c > 0x7e) {
          out.append(String.format("\\u%04x", (int) c));
        } else {
          out.append(c);
        }
      }
      out.append('"');
    }
    return out.append(']').toString();
  }
}
