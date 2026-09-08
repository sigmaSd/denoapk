package dev.denoapk.shell;

/**
 * Which proxy targets are allowed, kept free of anything needing a live
 * Android environment so it's covered by a JVM test (see
 * shell/test/NetworkTargetsTest.java).
 *
 * https is always allowed. http is allowed only to a private/loopback/
 * link-local IPv4 *literal* — this is the exception that makes the local
 * pairing server (a phone fetching a one-time credential handoff from the
 * desktop's own LAN address) possible without weakening the app's cleartext
 * policy for the general case. A hostname (not a literal IP) is never
 * treated as private, even if it happens to resolve to one — resolving DNS
 * to make that judgment would add a second network round trip to every
 * proxy request's allow/deny check, for a case (a public hostname secretly
 * pointing at a private IP) that isn't how legitimate LAN targets are
 * addressed anywhere in this codebase.
 */
public final class NetworkTargets {

  private NetworkTargets() {}

  public static boolean isPrivateIPv4Literal(String host) {
    if (host == null) return false;
    String[] parts = host.split("\\.", -1);
    if (parts.length != 4) return false;
    int[] octet = new int[4];
    for (int i = 0; i < 4; i++) {
      if (parts[i].isEmpty() || parts[i].length() > 3) return false;
      for (int j = 0; j < parts[i].length(); j++) {
        if (!Character.isDigit(parts[i].charAt(j))) return false;
      }
      octet[i] = Integer.parseInt(parts[i]);
      if (octet[i] > 255) return false;
    }
    if (octet[0] == 10) return true; // 10.0.0.0/8
    if (octet[0] == 172 && octet[1] >= 16 && octet[1] <= 31) return true; // 172.16.0.0/12
    if (octet[0] == 192 && octet[1] == 168) return true; // 192.168.0.0/16
    if (octet[0] == 127) return true; // 127.0.0.0/8 loopback
    if (octet[0] == 169 && octet[1] == 254) return true; // 169.254.0.0/16 link-local
    return false;
  }

  public static boolean isAllowed(String protocol, String host) {
    if ("https".equals(protocol)) return true;
    if ("http".equals(protocol)) return isPrivateIPv4Literal(host);
    return false;
  }
}
