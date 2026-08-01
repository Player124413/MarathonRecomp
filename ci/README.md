# CI workflows (activation required)

These two workflows are ready to run but live here instead of `.github/workflows/`,
because the GitHub App used to push this branch is not allowed to create or modify
workflow files (`refusing to allow a GitHub App to create or update workflow ... without
workflows permission`).

**The Android workflow is already live** at `.github/workflows/build-android.yml` — it was
uploaded through the GitHub web UI, which is the simplest way around the permission above.

To enable the Linux one the same way, either upload it through the web UI or move it
locally:

```bash
git mv ci/workflows/build-linux.yml .github/workflows/build-linux.yml
git commit -m "Enable the Linux build workflow"
git push
```

It then appears under the repository's **Actions** tab and can be started with
**Run workflow**.

---

## `build-linux.yml` — Linux build

Builds the desktop game with Clang + Ninja + vcpkg, matching the presets already in
`CMakePresets.json`.

- **Manual runs** let you pick the preset (`linux-release`, `linux-relwithdebinfo`,
  `linux-debug`); it also runs on pushes to `main`, `master` and `arena/**`.
- **Secrets:** building the game itself needs `ASSET_REPO` and `ASSET_REPO_TOKEN` — the
  private repository holding `default.xex` and the shader archives that the recompilers
  consume. The workflow checks for them first: with the secrets it builds and uploads the
  `MarathonRecomp` binary; without them it still configures the project and builds
  `XenonRecomp` / `XenosRecomp`, and prints a warning rather than failing at a confusing
  point halfway through.
- ccache and the vcpkg download/package directories are cached between runs.

## `build-android.yml` — launcher APK (already active)

Lives in `.github/workflows/`. Builds the Android launcher in
[`android/`](../android/README.md).

- JDK 17, SDK 35, NDK r27, CMake 3.22; Gradle caches are reused between runs.
- **Needs no secrets at all** — the launcher never contains or touches game data.
- Manual runs choose `debug`, `release`, or `both`; it also runs automatically when
  anything under `android/` or the shared pad ABI header changes.
- Uploads the APK as an artifact and writes its size to the run summary.


---

## Optional: verify the APK contains box64

`android/verify-apk.sh` asserts that `libbox64.so` is actually packaged (and is still an
ARM64 executable despite the `.so` name). A green build alone does not prove this — a bad
rename or packaging flag yields a valid APK with no emulator inside, and that only shows
up on device.

Run it locally:

```bash
android/verify-apk.sh          # defaults to the debug APK path
```

To run it in CI, add this step to `.github/workflows/build-android.yml` right after
*Build the debug APK* (it has to be edited through the GitHub web UI, for the permission
reason above):

```yaml
      - name: Verify box64 is packaged
        run: ../android/verify-apk.sh app/build/outputs/apk/debug/marathondroid-debug.apk
```


---

## `build-rootfs.yml` — the x86_64 system libraries (active, needs one fix)

Lives in `.github/workflows/`. Builds the rootfs the Android launcher downloads
automatically, and attaches it to a release (tag `rootfs-v1`, which is where the app
looks).

> **The live copy has one problem:** its publish step is guarded by
> `if: ${{ inputs.release_tag != '' }}`, and a **push**-triggered run has no inputs — so
> the archive builds fine but is only uploaded as a build artifact, which the app cannot
> download. The first run hit exactly this.
>
> **Fix:** copy `ci/workflows/build-rootfs.yml` over `.github/workflows/build-rootfs.yml`
> through the GitHub web UI (it drops the `if:` and defaults the tag to `rootfs-v1`), or
> just start the workflow by hand: **Actions → Build the x86_64 rootfs → Run workflow**,
> which supplies the input and publishes correctly.

**Run it once** — Actions → *Build the x86_64 rootfs* → *Run workflow*. Until then the
in-app download reports that the libraries have not been published for this build yet.

It runs `debootstrap` for Ubuntu 22.04 amd64, pulls in the libraries the game links
(`libX11`, `glib`, the Vulkan loader, `libstdc++`, ...), strips docs/locales/headers,
verifies the important ones are present, and packs a single `.tar.gz` — gzip on purpose,
because Java decodes it natively and the app therefore needs no archive library.
