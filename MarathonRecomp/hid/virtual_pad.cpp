#include <stdafx.h>
#include <hid/virtual_pad.h>
#include <os/logger.h>

#ifndef _WIN32
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#endif

#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>

namespace
{
    MRVirtualPadShm* g_shm = nullptr;
    bool g_initialised = false;

#ifndef _WIN32
    int g_fd = -1;
#endif

    std::string ResolveShmPath()
    {
        if (const char* path = std::getenv(MR_VPAD_ENV_PATH); path && *path)
            return path;

        if (const char* dir = std::getenv("MARATHON_RECOMP_VPAD_DIR"); dir && *dir)
        {
            std::string result = dir;

            if (!result.empty() && result.back() != '/')
                result += '/';

            result += MR_VPAD_DEFAULT_NAME;

            return result;
        }

        return {};
    }

    // Relaxed atomic load of a plain uint32_t living in shared memory.
    inline uint32_t LoadSeq(const volatile uint32_t& value)
    {
        uint32_t result = value;
        std::atomic_thread_fence(std::memory_order_acquire);
        return result;
    }
}

void hid::vpad::Init()
{
    if (g_initialised)
        return;

    g_initialised = true;

#ifndef _WIN32
    const std::string path = ResolveShmPath();

    if (path.empty())
        return;

    g_fd = open(path.c_str(), O_RDWR | O_CREAT, 0600);

    if (g_fd < 0)
    {
        LOGFN_ERROR("Virtual pad: cannot open \"{}\".", path);
        return;
    }

    // Make sure the file is large enough even when we got here before the launcher.
    struct stat st{};

    if (fstat(g_fd, &st) == 0 && static_cast<size_t>(st.st_size) < sizeof(MRVirtualPadShm))
    {
        if (ftruncate(g_fd, static_cast<off_t>(sizeof(MRVirtualPadShm))) != 0)
        {
            LOGFN_ERROR("Virtual pad: cannot size \"{}\".", path);
            close(g_fd);
            g_fd = -1;
            return;
        }
    }

    void* mapping = mmap(nullptr, sizeof(MRVirtualPadShm), PROT_READ | PROT_WRITE, MAP_SHARED, g_fd, 0);

    if (mapping == MAP_FAILED)
    {
        LOGFN_ERROR("Virtual pad: cannot map \"{}\".", path);
        close(g_fd);
        g_fd = -1;
        return;
    }

    g_shm = static_cast<MRVirtualPadShm*>(mapping);

    // The launcher normally writes the header first; fill it in if we won the race.
    if (g_shm->magic != MR_VPAD_MAGIC)
    {
        std::memset(g_shm, 0, sizeof(MRVirtualPadShm));
        g_shm->magic = MR_VPAD_MAGIC;
        g_shm->version = MR_VPAD_VERSION;
        g_shm->size = sizeof(MRVirtualPadShm);
        g_shm->padCount = MR_VPAD_MAX_PADS;
    }

    LOGFN("Virtual pad: attached to \"{}\".", path);
#endif
}

bool hid::vpad::IsAvailable()
{
    return g_shm != nullptr && g_shm->magic == MR_VPAD_MAGIC && g_shm->writerAlive != 0;
}

bool hid::vpad::IsConnected(uint32_t userIndex)
{
    if (!IsAvailable() || userIndex >= MR_VPAD_MAX_PADS)
        return false;

    return g_shm->pads[userIndex].connected != 0;
}

bool hid::vpad::Poll(uint32_t userIndex, MRVirtualPadState& out)
{
    if (!IsAvailable() || userIndex >= MR_VPAD_MAX_PADS)
        return false;

    // Seqlock read: retry while the writer is inside an update or the counter moved.
    for (int attempt = 0; attempt < 8; attempt++)
    {
        const uint32_t before = LoadSeq(g_shm->seq);

        if (before & 1u)
            continue;

        std::memcpy(&out, &g_shm->pads[userIndex], sizeof(MRVirtualPadState));

        std::atomic_thread_fence(std::memory_order_acquire);

        if (LoadSeq(g_shm->seq) == before)
            return out.connected != 0;
    }

    return false;
}

void hid::vpad::SetRumble(uint32_t userIndex, uint16_t left, uint16_t right)
{
    if (!IsAvailable() || userIndex >= MR_VPAD_MAX_PADS)
        return;

    auto& pad = g_shm->pads[userIndex];

    pad.rumbleLeft = left;
    pad.rumbleRight = right;

    std::atomic_thread_fence(std::memory_order_release);

    pad.rumbleCounter = pad.rumbleCounter + 1;
}

void hid::vpad::Shutdown()
{
#ifndef _WIN32
    if (g_shm != nullptr)
    {
        munmap(g_shm, sizeof(MRVirtualPadShm));
        g_shm = nullptr;
    }

    if (g_fd >= 0)
    {
        close(g_fd);
        g_fd = -1;
    }
#endif

    g_initialised = false;
}
