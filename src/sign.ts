/**
 * APK signing.
 *
 * minSdk is 24, so a v1-only (JAR) signature will not install — apksigner is
 * asked for v2+v3, which is what modern Android verifies. The debug keystore
 * is generated once with keytool (already in any JDK) and reused, matching
 * what the Android tools do for `debug` builds.
 *
 * These APKs are debug-signed: fine for sideloading and `adb install`, not for
 * distribution. Release signing is deliberately out of scope for now.
 */

import { join } from "@std/path";
import { exists } from "@std/fs";
import { cacheDir, type Sdk } from "./sdk.ts";

const KEY_ALIAS = "denoapkdebug";
const KEY_PASSWORD = "android";

export function debugKeystorePath(): string {
  return join(cacheDir(), "debug.keystore");
}

async function run(cmd: string, args: string[]) {
  const { code, stderr } = await new Deno.Command(cmd, {
    args,
    stdout: "null",
    stderr: "piped",
  }).output();
  if (code !== 0) {
    throw new Error(
      `${cmd} exited with ${code}: ${new TextDecoder().decode(stderr)}`,
    );
  }
}

async function ensureDebugKeystore(): Promise<string> {
  const path = debugKeystorePath();
  if (await exists(path)) return path;

  await Deno.mkdir(cacheDir(), { recursive: true });
  console.error(`generating debug keystore at ${path}`);
  await run("keytool", [
    "-genkeypair",
    "-keystore",
    path,
    "-alias",
    KEY_ALIAS,
    "-storepass",
    KEY_PASSWORD,
    "-keypass",
    KEY_PASSWORD,
    "-keyalg",
    "RSA",
    "-keysize",
    "2048",
    // 30 years; a debug key that expires mid-project is a bad surprise.
    "-validity",
    "10950",
    "-dname",
    "CN=denoapk debug, OU=denoapk, O=denoapk, C=US",
  ]);
  return path;
}

/** Align and sign in place, producing an installable APK. */
export async function alignAndSign(sdk: Sdk, unsigned: string, output: string) {
  const keystore = await ensureDebugKeystore();

  // zipalign must run before signing: apksigner preserves alignment, but
  // aligning a signed APK would invalidate the signature.
  await run(sdk.zipalign, ["-f", "-p", "4", unsigned, output]);

  await run(sdk.apksigner, [
    "sign",
    "--ks",
    keystore,
    "--ks-key-alias",
    KEY_ALIAS,
    "--ks-pass",
    `pass:${KEY_PASSWORD}`,
    "--key-pass",
    `pass:${KEY_PASSWORD}`,
    "--v2-signing-enabled",
    "true",
    "--v3-signing-enabled",
    "true",
    output,
  ]);
}

export async function verify(sdk: Sdk, apk: string): Promise<string> {
  const { code, stdout, stderr } = await new Deno.Command(sdk.apksigner, {
    args: ["verify", "--print-certs", apk],
    stdout: "piped",
    stderr: "piped",
  }).output();
  const out = new TextDecoder().decode(stdout) +
    new TextDecoder().decode(stderr);
  if (code !== 0) throw new Error(`signature verification failed:\n${out}`);
  return out;
}
