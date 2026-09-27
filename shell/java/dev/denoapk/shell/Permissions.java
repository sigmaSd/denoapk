package dev.denoapk.shell;

import android.webkit.PermissionRequest;

/**
 * Pure decision logic for WebView permission requests, kept free of anything
 * that needs a real Activity/Context so it can be tested on a plain JVM (see
 * shell/test/PermissionsTest.java) — the actual grant/deny plumbing in
 * MainActivity needs a device, but which resources we're willing to grant at
 * all does not.
 *
 * `PermissionRequest.RESOURCE_VIDEO_CAPTURE` and `RESOURCE_AUDIO_CAPTURE` are
 * plain `static final String` constants, not methods — referencing them works
 * under a stub android.jar on a plain JVM the same way any other constant
 * would, even though most of PermissionRequest's actual methods would throw
 * if called there.
 */
public final class Permissions {

  private Permissions() {}

  /** WebView resources this shell is willing to grant at all. */
  public static boolean isGrantable(String resource) {
    return PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)
        || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource);
  }

  /**
   * Resources this shell is willing to grant, filtered from what a page
   * actually asked for, in the order requested. Whether the OS-level
   * permission behind each one (CAMERA for video, RECORD_AUDIO for audio) is
   * currently granted is checked by MainActivity — an undeclared permission
   * is always denied by Android before that code ever runs, so per-app
   * gating still comes from the manifest, not from here.
   */
  public static String[] grantable(String[] requested) {
    int count = 0;
    for (String r : requested) {
      if (isGrantable(r)) count++;
    }
    String[] out = new String[count];
    int i = 0;
    for (String r : requested) {
      if (isGrantable(r)) out[i++] = r;
    }
    return out;
  }
}
