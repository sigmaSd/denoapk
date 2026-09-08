package dev.denoapk.shell;

import android.webkit.PermissionRequest;

/**
 * Pure decision logic for WebView permission requests, kept free of anything
 * that needs a real Activity/Context so it can be tested on a plain JVM (see
 * shell/test/PermissionsTest.java) — the actual grant/deny plumbing in
 * MainActivity needs a device, but which resources we're willing to grant at
 * all does not.
 *
 * `PermissionRequest.RESOURCE_VIDEO_CAPTURE` is a plain `static final String`
 * constant, not a method — referencing it works under a stub android.jar on a
 * plain JVM the same way any other constant would, even though most of
 * PermissionRequest's actual methods would throw if called there.
 */
public final class Permissions {

  private Permissions() {}

  /**
   * Resources this shell is willing to grant, filtered from what a page
   * actually asked for. We only ever request camera at the OS level, so
   * anything else (audio capture, protected media) is dropped even if a page
   * asks for it — never grant more than the app declared it needs.
   */
  public static String[] grantable(String[] requested) {
    for (String r : requested) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)) {
        return new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE };
      }
    }
    return new String[0];
  }
}
