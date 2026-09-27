import android.webkit.PermissionRequest;
import dev.denoapk.shell.Permissions;
import java.util.Arrays;

/**
 * Plain-JVM tests for Permissions.grantable(). Run via `deno task test:shell`.
 */
public final class PermissionsTest {

  private static int failures = 0;

  public static void main(String[] args) {
    eq(
        new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE },
        Permissions.grantable(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE }),
        "grants a bare video-capture request"
    );

    eq(
        new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE },
        Permissions.grantable(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE }),
        "grants a bare audio-capture request"
    );

    eq(
        new String[] {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
        },
        Permissions.grantable(new String[] {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
        }),
        "grants a mixed request in full, in the order requested"
    );

    eq(
        new String[0],
        Permissions.grantable(new String[0]),
        "empty request grants nothing"
    );

    eq(
        new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE },
        Permissions.grantable(new String[] {
            "android.webkit.resource.PROTECTED_MEDIA_ID",
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
        }),
        "drops ungrantable resources but keeps the grantable ones, in order"
    );

    if (failures > 0) {
      System.err.println(failures + " failure(s)");
      System.exit(1);
    }
    System.out.println("permission grant logic: all assertions passed");
  }

  private static void eq(String[] expected, String[] actual, String what) {
    if (!Arrays.equals(expected, actual)) {
      System.err.println(
          "FAIL " + what + ": expected " + Arrays.toString(expected) +
              ", got " + Arrays.toString(actual));
      failures++;
    }
  }
}
