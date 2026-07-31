#pragma once

/*
 * Shared memory protocol used to feed an Xbox 360 style pad into Marathon Recompiled
 * from an external process (the Android launcher's touch controls / physical gamepad).
 *
 * The layout below is the ABI contract between:
 *   - the game        : MarathonRecomp/hid/virtual_pad.cpp   (reader, writes rumble back)
 *   - the launcher    : android/app/src/main/cpp/vpad_bridge.c (writer, reads rumble)
 *
 * Both sides mmap the very same regular file (MAP_SHARED), so no root, no uinput and no
 * X11 input plumbing is required - which matters a lot when the game itself runs inside
 * box64 / FEX-Emu where Android input devices are not visible at all.
 *
 * Keep this header free of C++ so the Android JNI bridge can include it directly.
 */

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define MR_VPAD_MAGIC           0x4D525650u /* 'MRVP' */
#define MR_VPAD_VERSION         1u
#define MR_VPAD_MAX_PADS        4u

/* Default file name inside the directory pointed at by MARATHON_RECOMP_VPAD_DIR. */
#define MR_VPAD_DEFAULT_NAME    "marathon-vpad.shm"

/* Environment variable holding an absolute path to the shared memory file. */
#define MR_VPAD_ENV_PATH        "MARATHON_RECOMP_VPAD"

/* MRVirtualPadState::source values, purely informational for the game. */
#define MR_VPAD_SOURCE_NONE     0u
#define MR_VPAD_SOURCE_TOUCH    1u
#define MR_VPAD_SOURCE_GAMEPAD  2u

typedef struct MRVirtualPadState
{
    uint32_t connected;      /* 0 = ignore this pad entirely                      */
    uint32_t source;         /* MR_VPAD_SOURCE_*                                  */
    uint32_t buttons;        /* XAMINPUT_GAMEPAD_* bit mask                       */
    uint32_t leftTrigger;    /* 0 .. 255                                          */
    uint32_t rightTrigger;   /* 0 .. 255                                          */
    int32_t  thumbLX;        /* -32768 .. 32767                                   */
    int32_t  thumbLY;
    int32_t  thumbRX;
    int32_t  thumbRY;
    uint32_t updateCounter;  /* bumped by the writer on every state push          */

    /* Game -> launcher (rumble). The launcher polls rumbleCounter for changes. */
    uint32_t rumbleLeft;     /* 0 .. 65535                                        */
    uint32_t rumbleRight;    /* 0 .. 65535                                        */
    uint32_t rumbleCounter;
    uint32_t reserved[3];
} MRVirtualPadState;

typedef struct MRVirtualPadShm
{
    uint32_t magic;          /* MR_VPAD_MAGIC                                     */
    uint32_t version;        /* MR_VPAD_VERSION                                   */
    uint32_t size;           /* sizeof(MRVirtualPadShm)                           */
    uint32_t seq;            /* seqlock: odd while the writer is mid-update       */
    uint32_t padCount;       /* number of meaningful entries in pads[]            */
    uint32_t flags;          /* reserved for future use                           */
    uint32_t writerAlive;    /* launcher sets 1 on attach, 0 on detach            */
    uint32_t reserved;
    MRVirtualPadState pads[MR_VPAD_MAX_PADS];
} MRVirtualPadShm;

#ifdef __cplusplus
}
#endif
