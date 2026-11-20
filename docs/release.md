# Publishing a release

Set `typebit.version` in `gradle.properties` to the application version using three numeric components. Android, desktop packaging, the About screen and the WebUI read this value. The About screen obtains the TypeBit engine version from `native/Cargo.lock`.

Configure the Android signing identity in the repository's Actions secrets before publishing.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Commit the release sources, tag that commit `v<typebit.version>` and push the tag to GitHub. For example, the tag for `typebit.version=0.1.11` is `v0.1.11`. The Windows runner builds the APK, EXE, MSI and portable ZIP. The Linux runner builds the x86-64 application archive. The workflow checks JNI exports and runs the Rust and Compose tests. Both desktop launchers download a file from a loopback peer, verify its contents and restore the completed task after a restart. The tag build signs the APK and verifies its signature and ZIP alignment.

After both jobs succeed, the release job uploads the five packages and `SHA256SUMS` to a draft, then publishes it. A failed upload leaves the draft available for a rerun. To check downloaded assets on Linux, run `sha256sum -c SHA256SUMS` in their directory. On Windows, use `Get-FileHash -Algorithm SHA256` and compare the result with the corresponding entry.

## Local verification for 0.1.11

| Artifact | Verification |
| --- | --- |
| Windows portable ZIP and MSI | Extracted each package and ran its launcher. A loopback peer supplied a 65,536-byte file. Its bytes matched the source, and the completed task returned to seeding after restart with the peer offline. |
| Windows EXE installer | Built locally. Interactive installation is pending. |
| Android APK | The debug APK and a release APK signed with a local test key passed signature and ZIP alignment checks. Both included the bridge for four ABIs. All 64-bit native libraries passed the 16 KiB ELF segment alignment check. Device testing and production signing are pending. |
| Linux and fnOS | The packaging scripts passed shell syntax checks. Linux package execution and fnOS installation are pending. |

The packaged Windows client also recovered from a mismatched magnet cache. It requested the correct metadata from a loopback peer using `ut_metadata`, retained the magnet's tracker and restored the corrected file list on the next launch. The GitHub release workflow passed `actionlint` and a local staging run with fixture assets. Its signing secrets and hosted release jobs still require verification on GitHub.
