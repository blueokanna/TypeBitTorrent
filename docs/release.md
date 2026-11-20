# Publishing a release

Set `typebit.version` in `gradle.properties` to the application version using three numeric components. Android, desktop packaging, the About screen and the WebUI read this value. The About screen obtains the TypeBit engine version from `native/Cargo.lock`.

Configure the Android signing identity in the repository's Actions secrets before publishing.

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded release keystore |
| `ANDROID_STORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Signing key alias |
| `ANDROID_KEY_PASSWORD` | Signing key password |

Push a change to `typebit.version` on `main` to publish that version. For example, changing `0.1.11` to `0.1.12` schedules `v0.1.12`. Ordinary pushes and pull requests put their downloads in the run’s **Artifacts** section. The **Determine release version** summary shows which path was selected.

To publish an already committed version, open **Actions → Build TypeBitTorrent → Run workflow**, select `main` and check **Publish the version in gradle.properties from main**. Pushing a matching `v<typebit.version>` tag also triggers publication. A public version keeps its original commit.

The Windows runner builds the signed APK, EXE, MSI and portable ZIP. The Linux runner builds the x86-64 application archive. The workflow checks JNI exports and runs the Rust and Compose tests. Both desktop launchers download and seed a file to a loopback peer, verify its contents and restore the completed task after a restart. The release APK undergoes signature and ZIP alignment checks.

After both jobs succeed, the release job uploads the five packages and `SHA256SUMS` to a draft, then publishes it. A failed upload leaves the draft available for a rerun. To check downloaded assets on Linux, run `sha256sum -c SHA256SUMS` in their directory. On Windows, use `Get-FileHash -Algorithm SHA256` and compare the result with the corresponding entry.

## Local verification for 0.1.11

| Artifact | Verification |
| --- | --- |
| Windows portable ZIP and MSI | Extracted each package and ran its launcher. A loopback peer supplied a 65,536-byte file. Its bytes matched the source, and the completed task returned to seeding after restart with the peer offline. |
| Windows EXE installer | Built locally. Interactive installation is pending. |
| Android APK | The debug APK and a release APK signed with a local test key passed signature and ZIP alignment checks. Both included the bridge for four ABIs. All 64-bit native libraries passed the 16 KiB ELF segment alignment check. |
| Linux and fnOS | The packaging scripts passed shell syntax checks. Linux package execution and fnOS installation are pending. |

The packaged Windows client also recovered from a mismatched magnet cache. It requested the correct metadata from a loopback peer using `ut_metadata`, retained the magnet's tracker and restored the corrected file list on the next launch. The subsequent hosted Windows, Linux and release jobs succeeded. The published APK signature and asset checksums were verified. [Release](https://github.com/blueokanna/TypeBitTorrent/releases/tag/v0.1.11) · [Workflow run](https://github.com/blueokanna/TypeBitTorrent/actions/runs/44699804539)

## Verification of the changes after 0.1.11

The Windows Java 17 app image downloaded and then seeded 65,536 bytes to a loopback peer. Both payloads matched the source. The detail API reported 6,654 B/s while downloading and 6,497 B/s while seeding. The combined cache and disk read counters grew by 65,536 bytes. The test also checked TCP counter deltas and restored the completed task after restart. Run `python scripts/test-packaged-download.py <packaged-launcher>` to repeat it.

All 51 shared and desktop tests passed. They cover a narrow Peer row with a long client name, empty cache samples, missing JNI statistics, all three glass presets, the add button and a frame during the mobile detail transition. `:composeApp:desktopTest` writes the three 390 dp compact-layout screenshots to `composeApp/screenshots/compact-*.png` with the same list scrolled under the navigation bar. The Android debug APK built against API 37.1 and includes all four native ABIs.

On the API 33 x86-64 software emulator, an ART test driver loaded the Kotlin JNI entry points and native library from the debug APK. A loopback peer supplied 65,536 bytes and received the same bytes back. The file on the emulator matched the payload. JNI reported 6,859 B/s down and 6,526 B/s up. The TCP counters grew by 65,808 bytes received and 65,828 bytes sent. The disk wrote 65,536 bytes, and the read cache served all 65,536 bytes of the upload.

On Android 13, the home screen displayed all four destinations and the add button at 480 × 854 px and 180 dpi with gesture navigation enabled. The add button opened the import screen. In Android DocumentsUI, opening internal storage → Download → `TypeBit-check.torrent` returned the file to the Activity. The preview showed `smoke.bin`, 64.00 KiB, one selected file and the expected info-hash prefix `808a8df764f0a0d8`. The Downloads shortcut had reported a content-provider error on this emulator.

In a separate run, Android instrumentation supplied the same file as the picker result and activated the Activity’s add button. The app persisted the task and received 65,536 bytes from a loopback peer. The stats dialog reported 64.13 KiB of TCP input, 161 B of TCP output, one disk write of 64.00 KiB and no discarded bytes. The home screen showed `Seeding` at 100%. After a process restart the app sent all 65,536 bytes back. Both the saved file in the app’s external Download directory and the bytes returned to the peer matched the original payload (SHA-256 `7daca2095d0438260fa849183dfc67faa459fdf4936e1bc91eec6b281b27e4c2`).

A sustained transfer of `peer-window.bin` (80 pieces of 16 KiB) kept the connection active across the engine’s sampling windows. The Android Peers row and header showed 22.17 KiB/s down during download and 19.10 KiB/s up on a subsequent seeding connection. The saved file and uploaded bytes both matched the 1,310,720-byte fixture, with SHA-256 `1cba3644233319cd410b4b2cca97e1cbcbb64eca4459a33e9c0eb05766a901f9`. The statistics dialog showed 80 disk writes and 80 disk reads of 1.25 MiB in each direction, zero cache-served bytes, a 0.0% read-cache hit rate and zero discarded bytes.

With both tasks seeding, opening a detail page from the grid and swiping back from the left edge restored the two-column grid, the add button and the four navigation destinations. The light-theme screenshots show dark status icons and a dark gesture bar. The emulator used Skia/OpenGL on Google SwiftShader. The installed debug APK had SHA-256 `08dcb75472d96f2096ee9320d8933a4e3b6960c8ef842f486a96a46b23de92df`. Physical-device frame timings and power consumption remain unmeasured.

For the glass measurements, the emulator had two virtual CPUs, software CPU emulation, 2 GiB of RAM and background downloads disabled in the app. Each run used the same completed torrent, switched glass mode in Appearance, warmed up once, and reset `adb shell dumpsys gfxinfo com.typebit.app reset`. Two measured repetitions opened Settings, swiped down and up, returned to Torrents, opened RSS and returned to Torrents. The figures below come from the process summary in `adb shell dumpsys gfxinfo com.typebit.app framestats`. The histogram’s highest bucket was 4,950 ms. The times include layout, screen transitions and rendering of the entire Activity.

| Glass mode | Rendered frames | Missed frame deadline | P50 | P95 histogram bucket |
| --- | ---: | ---: | ---: | ---: |
| AeroGlass | 45 | 45 | 1,150 ms | 4,950 ms |
| LiquidGlass | 42 | 42 | 1,250 ms | 4,950 ms |
| Gaussian blur | 46 | 46 | 1,000 ms | 4,950 ms |
