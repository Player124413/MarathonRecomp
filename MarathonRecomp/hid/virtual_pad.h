#pragma once

#include <cstdint>
#include <hid/virtual_pad_shm.h>

/*
 * Virtual pad: lets an external process (the Android launcher) drive the game's
 * XInput state through a shared memory file. Completely inert on desktop unless
 * MARATHON_RECOMP_VPAD (or MARATHON_RECOMP_VPAD_DIR) is set, so it costs nothing
 * for normal PC builds.
 */
namespace hid::vpad
{
    // Maps the shared memory file if the environment asks for it. Safe to call twice.
    void Init();

    // True when a shared memory block is mapped and a launcher is attached.
    bool IsAvailable();

    // True when the given pad slot currently reports itself as connected.
    bool IsConnected(uint32_t userIndex);

    // Copies a consistent snapshot of the pad into 'out'. Returns false if unavailable.
    bool Poll(uint32_t userIndex, MRVirtualPadState& out);

    // Publishes rumble back to the launcher so the phone can vibrate.
    void SetRumble(uint32_t userIndex, uint16_t left, uint16_t right);

    // Unmaps the block (called at shutdown; optional).
    void Shutdown();
}
