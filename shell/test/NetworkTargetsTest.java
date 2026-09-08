import dev.denoapk.shell.NetworkTargets;

/**
 * Plain-JVM tests for NetworkTargets. Run via `deno task test:shell`.
 */
public final class NetworkTargetsTest {

  private static int failures = 0;

  public static void main(String[] args) {
    // Private ranges: RFC1918 plus loopback and link-local.
    ok(NetworkTargets.isPrivateIPv4Literal("192.168.1.38"), "192.168.x is private");
    ok(NetworkTargets.isPrivateIPv4Literal("10.0.0.1"), "10.x is private");
    ok(NetworkTargets.isPrivateIPv4Literal("172.16.0.1"), "172.16.x is private (low end of range)");
    ok(NetworkTargets.isPrivateIPv4Literal("172.31.255.255"), "172.31.x is private (high end of range)");
    ok(NetworkTargets.isPrivateIPv4Literal("127.0.0.1"), "127.x loopback is private");
    ok(NetworkTargets.isPrivateIPv4Literal("169.254.1.1"), "169.254.x link-local is private");

    // Just outside the 172.16.0.0/12 range — a real off-by-one risk given
    // it's not byte-aligned like the other RFC1918 ranges.
    no(NetworkTargets.isPrivateIPv4Literal("172.15.255.255"), "172.15.x is NOT private");
    no(NetworkTargets.isPrivateIPv4Literal("172.32.0.0"), "172.32.x is NOT private");

    // Public addresses must never pass.
    no(NetworkTargets.isPrivateIPv4Literal("8.8.8.8"), "public IP is not private");
    no(NetworkTargets.isPrivateIPv4Literal("1.1.1.1"), "public IP is not private");

    // A hostname is never treated as private, even if the name suggests it —
    // this proxy never resolves DNS to decide, by design.
    no(NetworkTargets.isPrivateIPv4Literal("router.local"), "hostname is never private");
    no(NetworkTargets.isPrivateIPv4Literal("localhost"), "even 'localhost' the name, not the IP, is rejected");

    // Malformed input must not throw or false-positive.
    no(NetworkTargets.isPrivateIPv4Literal(""), "empty string");
    no(NetworkTargets.isPrivateIPv4Literal("192.168.1"), "too few octets");
    no(NetworkTargets.isPrivateIPv4Literal("192.168.1.1.1"), "too many octets");
    no(NetworkTargets.isPrivateIPv4Literal("192.168.1.256"), "octet out of range");
    ok(NetworkTargets.isPrivateIPv4Literal("192.168.01.1"), "leading zero still parses as decimal 1, stays private (no octal ambiguity, no security issue)");
    no(NetworkTargets.isPrivateIPv4Literal(null), "null");

    // The actual gate ProxyClient calls.
    ok(NetworkTargets.isAllowed("https", "claude.ai"), "https to a public hostname is always allowed");
    ok(NetworkTargets.isAllowed("https", "8.8.8.8"), "https to a public IP is allowed too");
    ok(NetworkTargets.isAllowed("http", "192.168.1.38"), "http allowed to a private IP literal");
    no(NetworkTargets.isAllowed("http", "claude.ai"), "http to a public hostname is refused");
    no(NetworkTargets.isAllowed("http", "8.8.8.8"), "http to a public IP is refused");
    no(NetworkTargets.isAllowed("file", "192.168.1.38"), "non-http(s) scheme is refused outright");

    if (failures > 0) {
      System.err.println(failures + " failure(s)");
      System.exit(1);
    }
    System.out.println("network target checks: all assertions passed");
  }

  private static void ok(boolean actual, String what) {
    if (!actual) {
      System.err.println("FAIL (expected true) " + what);
      failures++;
    }
  }

  private static void no(boolean actual, String what) {
    if (actual) {
      System.err.println("FAIL (expected false) " + what);
      failures++;
    }
  }
}
