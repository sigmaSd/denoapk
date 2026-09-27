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
        new String[0],
        Permissions.grantable(new String[] { PermissionRequest.RESOURCE_AUDIO_CAPTURE }),
        "never grants audio — we only ever ask the OS for camera"
    );

    eq(
        new String[0],
        Permissions.grantable(new String[0]),
        "empty request grants nothing"
    );

    eq(
        new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE },
        Permissions.grantable(new String[] {
            PermissionRequest.RESOURCE_AUDIO_CAPTURE,
            PermissionRequest.RESOURCE_VIDEO_CAPTURE,
        }),
        "grants only video out of a mixed request, never audio alongside it"
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
