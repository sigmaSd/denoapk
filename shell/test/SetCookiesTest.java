import dev.denoapk.shell.SetCookies;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain-JVM tests for SetCookies. Run via `deno task test:shell`.
 */
public final class SetCookiesTest {

  private static int failures = 0;

  public static void main(String[] args) {
    Map<String, List<String>> fields = new LinkedHashMap<>();
    // HttpURLConnection reports the status line under a null key.
    fields.put(null, Collections.singletonList("HTTP/1.1 200 OK"));
    fields.put("Content-Type", Collections.singletonList("application/json"));
    fields.put("Set-Cookie", Arrays.asList(
        "a=1; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT",
        "b=2; HttpOnly"));
    eq(SetCookies.headerValue(fields),
        "[\"a=1; Path=/; Expires=Wed, 21 Oct 2026 07:28:00 GMT\",\"b=2; HttpOnly\"]",
        "every Set-Cookie line, commas in Expires intact");

    Map<String, List<String>> lower = new LinkedHashMap<>();
    lower.put("set-cookie", Collections.singletonList("c=3"));
    eq(SetCookies.headerValue(lower), "[\"c=3\"]", "header name is case-insensitive");

    Map<String, List<String>> none = new LinkedHashMap<>();
    none.put("Content-Type", Collections.singletonList("text/plain"));
    eq(SetCookies.headerValue(none), null, "no Set-Cookie means no header");

    Map<String, List<String>> odd = new LinkedHashMap<>();
    odd.put("Set-Cookie", Collections.singletonList("q=\"x\\y\"\u00e9"));
    eq(SetCookies.headerValue(odd), "[\"q=\\\"x\\\\y\\\"\\u00e9\"]",
        "quotes, backslashes and non-ASCII are escaped");

    ok(SetCookies.isSetCookie("Set-Cookie"), "Set-Cookie is filtered");
    ok(!SetCookies.isSetCookie("Set-Cookie2"), "only Set-Cookie itself");
    ok(!SetCookies.isSetCookie(null), "null name");

    if (failures > 0) {
      System.err.println(failures + " SetCookiesTest failure(s)");
      System.exit(1);
    }
    System.out.println("SetCookiesTest: all passed");
  }

  private static void ok(boolean cond, String name) {
    if (!cond) {
      failures++;
      System.err.println("FAIL: " + name);
    }
  }

  private static void eq(String actual, String expected, String name) {
    boolean same = actual == null ? expected == null : actual.equals(expected);
    if (!same) {
      failures++;
      System.err.println("FAIL: " + name + "\n  expected: " + expected + "\n  actual:   " + actual);
    }
  }
}
