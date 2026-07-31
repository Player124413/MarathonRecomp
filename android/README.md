# Marathon Droid — Android launcher

An Android launcher for [Marathon Recompiled](../README.md), inspired by
[RimDroid](https://github.com/udarmolota/RimDroid).

Marathon Recompiled is a native **x86_64 Linux** program. Phones are ARM64, so the
launcher runs it through a translation layer — and you choose which one:

| Backend | Notes |
|---|---|
| **Box64** | The compatible choice. Starts on almost anything, slower on heavy code. |
| **FEX‑Emu** | Usually faster thanks to its JIT, but wants a recent device and a proper x86_64 rootfs. |

The setting is a plain dropdown on the home screen and takes effect on the next launch.

> **No game data is included or distributed.** You supply your own legally acquired copy,
> exactly as with the desktop builds.

---

## Features

- **Backend choice** — Box64 or FEX‑Emu, switchable at any time.
- **On-screen Xbox 360 pad** — two analog sticks, d-pad with diagonals, A/B/X/Y,
  LB/RB/LT/RT, Start/Back and L3/R3.
- **Layout editor** — drag to move, sliders for size and opacity, rebind any button,
  add or delete controls, reset to default.
- **Automatic gamepad handover** — plug in a controller and the touch overlay disappears
  completely; unplug it and the overlay comes back.
- **Rumble** — the game's vibration requests drive the phone's motor.
- **English and Russian** UI.

---

## How input reaches the game

The game runs as a **separate process** inside the emulator, so Android's input events
cannot reach it directly — and inside box64/FEX there are no Android input devices to
speak of.

Instead both sides share a small memory-mapped file:

```
 touch overlay ─┐
                ├─→ PadState ─→ JNI ─→ [ shared memory ] ─→ hid::vpad ─→ XamInputGetState
 gamepad       ─┘   (merge)                 seqlock                        (the game)
                                                 ↑
                                          rumble travels back
```

- The launcher is the only writer, the game is the only reader; a seqlock means the game
  never observes a half-written frame (verified with a 2 000 000-frame concurrency test).
- Values are raw XInput: buttons are `XAMINPUT_GAMEPAD_*` masks, triggers `0..255`,
  sticks `-32768..32767`. No translation table exists anywhere, because none is needed.
- The ABI lives in [`MarathonRecomp/hid/virtual_pad_shm.h`](../MarathonRecomp/hid/virtual_pad_shm.h)
  and is included by both the game and the launcher's JNI code, so the two cannot drift apart.

On the game side this is completely inert unless `MARATHON_RECOMP_VPAD` points at the
file, so desktop builds behave exactly as before.

### Why a physical pad wins

`hid::GetState` only consults the virtual pad when **no SDL controller is open**. So when
you plug a controller into the phone, the game reads that controller directly and the
launcher hides the overlay — there is no path where both fight over the same slot.

---

## Setup

### 1. Install a runtime package

Neither backend ships inside the APK: they are separate GPL projects with their own
release cadence, and a stale bundled copy helps nobody. Import one once, per backend:

*Settings → choose the backend → **Import runtime package (.zip)***

The zip must contain the backend executable (`box64` or `FEXInterpreter`) and, ideally,
an x86_64 rootfs:

```
box64                      <- or FEXInterpreter
rootfs/
  lib/x86_64-linux-gnu/...
  usr/lib/x86_64-linux-gnu/...
```

It is unpacked to `filesDir/runtime/<backend>/`, the binary is marked executable, and the
importer refuses entries that try to escape that directory.

### 2. Point the launcher at the game

*__Select the game folder__* — pick the folder holding the `MarathonRecomp` executable
and its data.

The folder must be on **internal shared storage**. The emulated game opens files with
plain `open()`, so it cannot read through Android's Storage Access Framework; the launcher
tells you instead of failing later with a confusing error.

### 3. Play

The **Play** button stays disabled until both the runtime and the game folder are valid.

---

## The layout editor

Reachable from the home screen, from the in-game quick menu, or from the small **Edit**
button on the overlay itself.

| Action | How |
|---|---|
| Move a control | Drag it |
| Resize | *Size* slider (50 %–250 %) |
| Opacity | *Opacity* slider |
| Rebind | *Binding* → pick any Xbox 360 input |
| Round / rectangular | *Round* checkbox |
| Hold vs latch | *Latch* checkbox |
| Add | *Add* → button, stick or d-pad |
| Delete | Select, then *Delete* |
| Start over | *Reset* |

Positions are stored as **fractions of the screen**, so a layout arranged on a phone still
lands correctly on a tablet and survives rotation. Leaving without saving asks first.

The stock layout is not eyeballed — it was checked with circle-accurate collision tests at
720p/1.5×, 1080p/2.0×, 2340×1080/2.75×, 2400×1080/3.0×, 2560×1600/2.0× and 4K/3.5×, and
nothing overlaps or falls off screen on any of them.

---

## Building

```bash
cd android
./gradlew assembleDebug      # app/build/outputs/apk/debug/marathondroid-debug.apk
```

Requirements: JDK 17, Android SDK 35, NDK r27, CMake 3.22. CI does all of this in
[`ci/workflows/build-android.yml`](../ci/workflows/build-android.yml) (see [ci/README.md](../ci/README.md) to activate) — it needs
no secrets, because the launcher never touches game data.

The launcher must be built from inside the MarathonRecomp checkout: its `CMakeLists.txt`
includes the pad ABI header from `MarathonRecomp/hid/` and fails with a clear message
otherwise.

---

## Status and honest expectations

The launcher, the input pipeline and the editor are complete and tested. What cannot be
verified without a physical device and a runtime package is the **end-to-end launch**:
how well the recompiled game performs under box64 or FEX on any given phone, and which
GPU driver each device needs.

Sonic '06 is a demanding 3D game and emulation adds cost on top. Treat performance on
anything but a recent flagship as unknown, and expect to try both backends.
