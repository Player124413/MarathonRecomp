# You built the Linux binary — what now?

Short version: **the binary on its own is not enough.** It needs the converted game data
next to it, and that conversion has to happen on a PC once. This page walks through it.

---

## Why the binary alone will not run

Marathon Recompiled is the *engine*, not the game. On first launch it looks for

```
<folder with the executable>/game/default.xex
```

If that is missing it opens its **installer wizard**, which asks for the files from your
own Xbox 360 copy and converts them. That wizard is a desktop UI driven by a file picker —
on a phone you would get a window you cannot usefully operate.

So the rule is: **run the installer once on a PC, then move the finished folder to the
phone.** The launcher now checks for this and tells you plainly instead of dropping you
into the wizard.

---

## Step 1 — Prepare the folder on a PC

You need, from your own legally acquired Xbox 360 copy of *Sonic the Hedgehog (2006)*:

- the game disc content (files such as `default.xex`, `.ar.00`, `.arl`), and
- the Title Update, **from the same region** as the disc.

Then:

1. Put the binary you built into an empty folder and make it executable:

   ```bash
   mkdir ~/marathon && cp MarathonRecomp ~/marathon/
   chmod +x ~/marathon/MarathonRecomp
   ```

2. Run it **on the PC**:

   ```bash
   cd ~/marathon && ./MarathonRecomp
   ```

3. The installer wizard appears — point it at your game files and let it finish.

4. Confirm you now have this layout:

   ```
   marathon/
   ├── MarathonRecomp        <- the binary you built
   └── game/
       ├── default.xex       <- the launcher checks for exactly this
       └── ...               <- converted data
   ```

> Not on a Linux PC? The same folder produced by the **Windows** build works, as long as
> the `game/` folder came from the installer — the data is platform independent. Only the
> executable itself must be the Linux x86_64 one you built.

---

## Step 2 — Zip it

Zip the **contents** of that folder (or the folder itself — the launcher unwraps a single
wrapper folder automatically):

```bash
cd ~/marathon && zip -r ~/marathon.zip .
```

Copy `marathon.zip` to the phone however you like.

---

## Step 3 — Install it in the launcher

1. Install the APK from the **Actions** tab (artifact `marathondroid-debug`).
2. Open the app → **Install the game from a .zip** → pick `marathon.zip`.
3. Wait for it to unpack. The status line changes to *Installed in the app*.

Box64 is already built into the APK, so there is nothing else to install.

---

## Step 3b — x86_64 system libraries (required)

**This is the step behind "the game exited, code 255".**

The build is an ordinary **dynamically linked** Linux program. Most of its dependencies
are compiled in (SDL, curl, fmt, ...), but it still needs the x86_64 versions of the
system libraries it links against on Linux:

```
libc.so.6   libm   libdl   libpthread
libX11      libglib-2.0     libgio-2.0    (+ their own dependencies)
```

Android cannot provide any of these — its libraries are ARM64 and bionic, not x86_64
glibc. When they are missing, box64 prints

```
Error: Loading needed libs in elf .../MarathonRecomp
```

and exits with **255**. That is exactly what code 255 means here: not a broken game folder,
just no system libraries to link against.

**Fix:** *Emulation backend → **Import x86_64 system libraries (.zip)*** — import a small
x86_64 rootfs once. The launcher checks for `libc.so.6` and refuses archives that clearly
are not one, and **Play** stays disabled until it is present.

Where to get one:

- Any Debian/Ubuntu **amd64** rootfs works. On a PC you can build a minimal one with
  `debootstrap`, then add the X11 and glib packages the game needs.
- The rootfs images shipped by box64/Termux communities work too — just repackage as a
  **`.zip`**, since `.tar.xz` needs an XZ decoder Android does not provide.

Layout inside the zip (either of these is fine):

```
lib/x86_64-linux-gnu/libc.so.6 ...        usr/lib/x86_64-linux-gnu/...
```

## Step 4 — Optional but recommended: Turnip

If your phone has an **Adreno** GPU, import a Turnip build under **Graphics →
Import a Vulkan driver**. The system Vulkan driver is often the weak link under emulation.

The launcher detects the GPU and will tell you if it is not Adreno, in which case skip
this step.

---

## Step 5 — Play

Press **Play**. The touch controls appear automatically; plug in a controller and they
disappear.

---

## If something goes wrong

| Symptom | Meaning |
|---|---|
| *"the game data is not there: …/game/default.xex is missing"* | Step 1 was skipped — the zip had only the binary. |
| *"No MarathonRecomp executable"* | The zip has the data but not the executable, or it is named differently. |
| *"Box64 is missing from this build"* | The APK was built without the box64 submodule. |
| **Exit code 255** | The x86_64 system libraries are missing — do Step 3b. |
| Game exits immediately | Check the log at `filesDir/logs/game.log`; it captures the emulator's stdout and stderr. |

### About performance

Sonic '06 is a demanding 3D game and box64 adds translation cost on top. This has not been
measured on real hardware yet — treat anything short of a recent flagship as unknown, and
expect to experiment with **Compatibility mode** if it refuses to start.
