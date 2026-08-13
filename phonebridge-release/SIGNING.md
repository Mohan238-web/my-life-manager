# PhoneBridge release signing

PhoneBridge v1.1 keeps signing credentials out of source control.

## Windows Authenticode

To remove the Unknown Publisher/SmartScreen publisher warning in a normal public release, use a trusted code-signing certificate issued to the publisher. Store it only as GitHub Actions secrets:

- `WINDOWS_CERT_PFX_BASE64` — base64-encoded `.pfx`
- `WINDOWS_CERT_PASSWORD` — PFX password

The release workflow signs the receiver, virtual-camera setup, virtual-camera media-source DLL, and the final installer, then verifies each signature with `signtool verify /pa`.

A self-signed certificate is useful for development but does not provide normal SmartScreen publisher trust, so the release workflow does not pretend that a self-signed build is production-signed.

## Android release signing

For a distributable Android release, create/retain one long-lived upload/release key and store it only in GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The signing key must never be committed to this repository. Losing the key can prevent future upgrades from installing over the existing app when distributing outside Play App Signing.

Even a correctly signed APK can still display a sideload/Play Protect warning when installed outside Google Play. Google Play distribution is the normal way to remove the generic sideload workflow for end users.

## Stable media baseline

Do not replace the proven video pipeline during signing/release changes:

`CameraX standardized NV21 -> JPEG -> libjpeg-turbo BGRA -> Direct2D -> PhoneBridge Camera`

Regression checks must continue to reject `StretchDIBits` and the previous manual/RGBA color conversion paths that caused visible color corruption on real hardware.
