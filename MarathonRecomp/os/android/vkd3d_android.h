#pragma once

#include <cstdint>
#include <filesystem>
#include <string>

// DirectX 12 is not a driver feature on Android: the only way to run the recompiled D3D12
// renderer here is a translation runtime (vkd3d / vkd3d-proton) shipped inside the APK, which
// converts the D3D12 calls into Vulkan calls on the very same GPU driver the native path
// already uses. Nothing in the game can render D3D12 without such a library present at run
// time, so these helpers answer exactly one question - "is a usable runtime installed, and was
// this build even configured with the D3D12 renderer?" - and expose the answer to both the
// log and the launcher, so a user never has to guess why the DirectX 12 option is inert.
//
// Loading a user supplied library is deliberately opt-in: the runtime is only dlopened when the
// game was actually asked to run on DirectX 12 (see docs/DX12-VKD3D-ANDROID.md for the whole
// picture). The launcher (Java) checks file names only; the verdict below comes from a real
// probe and is written back to a status file for the launcher's next refresh.
namespace os::android
{
    struct Vkd3dRuntime
    {
        // True when the APK was built with the D3D12 renderer compiled in
        // (MARATHON_RECOMP_D3D12 via the MARATHON_RECOMP_D3D12_VKD3D CMake option).
        bool compiledIn = false;
        // True when a candidate library file was found in one of the searched folders.
        bool found = false;
        // True when that candidate is at least the right kind of file (64-bit ARM shared
        // object). Only such a candidate is ever handed to dlopen.
        bool archOk = false;
        // True once the candidate has actually been loaded and inspected (loadProbe).
        bool probed = false;
        // True when the candidate was an aarch64 ELF that loaded and exported D3D12 entry points.
        bool usable = false;
        std::filesystem::path path;
        uint64_t size = 0;
        // Short, user presentable reason for the outcome (English; shown verbatim in the log).
        std::string note;
    };

    // Searches the APK's native library directory plus the vkd3d/vkd3d_import folders. The
    // search result is cached; with loadProbe the first plausible candidate is additionally
    // dlopened and checked for D3D12 entry points, and that result is cached as well.
    const Vkd3dRuntime &GetVkd3dRuntime(bool loadProbe = false);

    // True when a DirectX 12 session could actually be started in this installation. Probes the
    // library if that has not happened yet, so only call it on a path that needs the answer.
    bool IsDirectX12Available();

    // Logs the outcome and writes <external files>/vkd3d_status.txt, which the launcher shows
    // next to the renderer option. Call once, during renderer creation; loadProbe should only be
    // true when the user actually asked for DirectX 12.
    void ReportDirectX12Availability(bool loadProbe);
}
