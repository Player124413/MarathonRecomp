# Marathon Recompiled for Android

Play the Xbox 360 version of *Sonic the Hedgehog (2006)* natively on a supported Android device.

This is an unofficial Android port of [Marathon Recompiled](https://github.com/sonicnext-dev/MarathonRecomp), modelled after [UnleashedRecomp-Android](https://github.com/SansNope/UnleashedRecomp-Android). It runs the game through static recompilation rather than emulating an Xbox 360, and includes an Android app, touch controls, gamepad support, and an optional custom Vulkan driver for Qualcomm Adreno GPUs.

> [!IMPORTANT]
> This project does not include the game. You must supply files from your own legally acquired copy of *Sonic the Hedgehog (2006)* for Xbox 360.

> [!WARNING]
> This is an experimental community port. Performance and stability vary by phone, Android version, and GPU driver. Keep a copy of your saves and game files.

## What works

- The full game, including title screens and regular gameplay
- ARM64 Android devices (Android 9+)
- Installing the game and mods from a `.zip` or folder directly in the app — no PC needed
- Staging Xbox 360 base-game, title-update and DLC package files for verified installation on first launch
- On-screen touch controls with multi-touch, touch camera control, and a drag-to-arrange layout editor
- Bluetooth and USB controllers
- Sound through speakers, wired headphones, and Bluetooth devices
- Signed in-app updates from this repository's GitHub releases
- Optional bundled Turnip driver plus importing another driver as a plain `.so` or an AdrenoTools/ExynosTools package `.zip`
- Game-file access through Android's system Files app
- App UI in English and Russian
- Logs and hang diagnostics that can be shared without `adb`

The reference port targets Adreno 710, 725, 732 and 750 GPUs with bundled Turnip builds. **No driver binaries are committed to this repository** — drop community Turnip builds into `android-apk/app/src/main/assets/bundled_driver/` (names in `MarathonRecomp/os/android/vulkan_driver_android.cpp`) before building the APK. Without them the launcher falls back to the system Vulkan driver, and drivers can still be imported at runtime.

**Mali support is experimental**: recent Mali GPUs (Valhall generation and newer with a Vulkan 1.3 driver) run the game through the stock system driver; the app detects a non-Adreno GPU and skips the bundled Adreno driver automatically.

## Before you install

You need:

- A 64-bit Android device
- Android 9 or newer
- A supported Vulkan GPU (Adreno recommended)
- Several gigabytes of free storage
- Your own Xbox 360 game dump

For the smoothest first run, start with the default graphics settings. The Android build defaults to a 50% resolution scale, no anti-aliasing, 4× anisotropic filtering, and motion blur disabled.

## Installation

1. Download the latest APK from the repository's [Releases](https://github.com/Player124413/MarathonRecomp/releases) page (or from the workflow artifacts — note that GitHub artifact downloads are **zip files**: extract `app-debug.apk` from the zip first).
2. Allow your browser or file manager to install apps from unknown sources when Android asks.
3. Install and open **Marathon Recompiled**. The first launch creates the app's folders and prepares the bundled graphics driver.
4. Tap **Install game files (.zip / folder)** in the launcher and pick your game dump — either a ZIP archive or an extracted folder. The app finds the game inside the archive automatically and copies everything into place with a progress display. You can also choose **ISO / DLC packages**, select the base game and optional DLC files together, then launch once to verify and install them.
5. Tap **Launch game**.

### If the APK "doesn't install" (the dialog just disappears)

The debug APK from CI is signed with a debug key. A fresh GitHub runner generates
a new key on every run, so **an APK from run #2 cannot be installed over the APK
from run #1** (signature mismatch — the installer silently dismisses the dialog).
The workflow now caches the keystore, but if you already hit this:

- **Uninstall the old app first**, then install the new APK.
- Make sure the download finished and the file ends in `.apk` (GitHub artifacts
  are zips — extract `app-debug.apk`).
- Give your browser/file manager permission to install unknown apps
  (Settings → Apps → [browser] → Install unknown apps → Allow).
- Check the device: this APK is **arm64-v8a only**, Android 10+.
- For the exact error, use adb: `adb install -r app-debug.apk`.

No PC is required at any point.

## Building the APK with GitHub Actions (no local toolchain, no secrets)

The repository ships a workflow (`tools/ci/build-android-apk.yml`) that builds the
APK in the cloud fully automatically. You only need to provide the **game files**
through a public link:

1. Pack a zip containing the game files (any folder layout — they are found
   automatically):
   ```
   default.xex
   shader.arc
   shader_lt.arc
   ```
   (Optional: add `drivers/*.so` to bundle Turnip driver builds. Without them
   the app uses the system Vulkan driver. Add `vkd3d/*.so` to bundle an arm64
   DirectX 12-over-Vulkan runtime; it is then offered in the launcher, see
   [docs/DX12-VKD3D-ANDROID.md](docs/DX12-VKD3D-ANDROID.md).)
2. Upload the zip somewhere public — Google Drive or HuggingFace both work.
3. Run the **Build Android APK** workflow: in the repo, go to *Actions → Build Android APK → Run workflow* and paste the link into the `build_files_url` field. Alternatively set it once as the repository variable `BUILD_FILES_URL` (*Settings → Secrets and variables → Actions → Variables*).

Everything else is automatic:
- all git submodules are initialized by the workflow,
- **ffmpeg for Android is built from source** (7.1.1 + the XMA decoder patch)
  by `tools/ci/build_ffmpeg_android.sh` — no prebuilt ffmpeg zip needed,
- the host recompiler tools are built, `libmain.so` is cross-compiled with the
  NDK, the debug APK is assembled and uploaded as a workflow artifact
  (installable — signed with the Android debug key).

> **Note on the workflow location:** GitHub only auto-runs workflows from
> `.github/workflows/`. This copy is kept in `tools/ci/` so it can be versioned
> and pushed without extra repository permissions. To activate it, copy it to
> `.github/workflows/build-android-apk.yml` (GitHub UI: *Add file → Upload files*,
> or locally), then run it from the Actions tab.

No secrets, no keystore, no local Android SDK/NDK needed.

## Building the APK from source

### 1. Clone with submodules

```bash
git clone --recurse-submodules https://github.com/Player124413/MarathonRecomp.git
```

### 2. Add the required game files

Copy the following files from the game and place them inside `./MarathonRecompLib/private/`:
- `default.xex`
- `shader.arc`
- `shader_lt.arc`

`default.xex` is located in the game's root directory, while the others are located in `/xenon/archives`.

### 3. Build the host tools (once)

The recompiled code is generated at build time by tools that must run on the machine
you build on:

```bash
./build_host_tools.sh          # needs cmake + ninja + clang; uses ./thirdparty/vcpkg
```

### 4. Cross-compile the game (libmain.so)

```bash
export ANDROID_NDK_HOME=$HOME/Android/Sdk/ndk/29.0.14206865   # NDK r29
./build_android.sh            # -> out/build/android-arm64/MarathonRecomp/libmain.so
```

This uses the `android-release` CMake preset and the NDK toolchain. FFmpeg (used for
XMA audio decoding) is fetched from `sonicnext-dev/ffmpeg-core` releases; no Android
prebuilts are published upstream yet, so on the first run you must provide
`ffmpeg-android-arm64.zip` (built for arm64-android, same structure as the other
prebuilts) — place it at the path the configure step reports.

### 5. Assemble the APK

```bash
./build_apk.sh                # copies libmain.so into jniLibs, runs gradlew assembleDebug
```

Requires JDK 17 and the Android SDK (compileSdk 34). The debug APK lands at
`android-apk/app/build/outputs/apk/debug/app-debug.apk`.

### Optional: bundled drivers

> **Adreno 6xx / Snapdragon 662 / 680 (Adreno 610) note:** older Adreno 6xx GPUs
> are supported. The launcher automatically selects **Sysmem** render mode
> (TU_DEBUG=sysmem) on these SoCs in Auto mode, which fixes corrupt video
> playback on this GPU generation. If you still see graphical corruption,
> capture the app log (`log.txt` via the launcher's Log button) — the build
> logs the exact texture formats the game uses, which helps identify the
> remaining issue.


Copy community Turnip driver builds into `android-apk/app/src/main/assets/bundled_driver/`
with the exact names from `MarathonRecomp/os/android/vulkan_driver_android.cpp`
(`vulkan.marathon_a732.so`, `vulkan.vauzi710_v2_7.so`, `vulkan.wb26_2_rp_pair_ccu_color_a725.so`)
before step 5 so they are packaged into the APK.

## Installing a custom Vulkan driver (Turnip)

The app imports a custom driver (e.g. a Mesa Turnip build) from **any** of these
folders — drop a plain `.so` (like `libvulkan_freedreno.so`) or an
AdrenoTools/ExynosTools package `.zip` into one of them and relaunch the game:

1. `Android/data/<app>/files/driver_import/` (launcher's **Driver folder** button)
2. `Android/media/<app>/driver_import/` (browsable by on-device file managers)
3. `<game root>/driver_import/` (next to your game files, opened via **Open game folder**)

Processed packages move to `installed/` and are selected automatically. See the
`readme.txt` written into each folder for TU_DEBUG / capture options.

> The debug APK must contain the libadrenotools hook libraries
> (`libmain_hook.so`, `libfile_redirect_hook.so`, `libgsl_alloc_hook.so`,
> `libhook_impl.so`) for driver loading to work — the CI workflow packages them
> next to `libmain.so` automatically. If the log shows
> `adrenotools hook missing from nativeLibraryDir`, the APK was built by an
> older workflow; rebuild with the current `tools/ci/build-android-apk.yml`.

## Configuration

- The launcher exposes the renderer (Vulkan, or DirectX 12 via vkd3d — experimental and opt-in),
  the Vulkan driver, Turnip render mode, skip-intro and diagnostic options.
- **DirectX 12 on Android is not a second driver.** It would run the recompiled D3D12 renderer on
  top of a vkd3d library that translates every call back into Vulkan on the same GPU driver, one
  layer further from the hardware: no extra driver features, no expected FPS gain, and its own set
  of possible graphical issues. No public build of that library for Android exists, so shipping
  APKs contain no DirectX 12 renderer at all — the launcher then says so and the game starts on
  Vulkan. The full status, requirements and step-by-step plan are in
  [docs/DX12-VKD3D-ANDROID.md](docs/DX12-VKD3D-ANDROID.md).
- In-game options (Input → touch controls/camera/stick, Video → resolution scale, driver, profiler) apply immediately or after restart as noted in-game.
- Touch-control layout is arranged from the launcher (**Controls → Arrange touch controls**).

## Credits

- [MarathonRecomp](https://github.com/sonicnext-dev/MarathonRecomp) — the PC recompilation this is based on
- [UnleashedRecomp-Android](https://github.com/SansNope/UnleashedRecomp-Android) — the Android port this project mirrors
- [XenonRecomp](https://github.com/sonicnext-dev/XenonRecomp), [XenosRecomp](https://github.com/sonicnext-dev/XenosRecomp)
- [libadrenotools](https://github.com/bylaws/libadrenotools), [plume](https://github.com/renderbag/plume), [SDL](https://github.com/libsdl-org/SDL)
