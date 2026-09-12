#include "vkd3d_android.h"

#include <os/android/storage_android.h>
#include <os/logger.h>

#include <SDL.h>
#include <SDL_system.h>

#include <algorithm>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <dlfcn.h>
#include <fstream>
#include <jni.h>
#include <string>
#include <vector>

// Library names a DirectX 12 on Vulkan runtime is commonly distributed under. vkd3d-proton
// publishes d3d12.dll for Wine and libvkd3d-proton.so for its (development only) native build;
// Android packs shipped by community forks are usually named libvkd3d*.so. The search is
// case-sensitive on purpose: these are filesystem names, not display names.
static const char *const g_vkd3dLibraryNames[] = {
    "libvkd3d_proton.so",
    "libvkd3d-proton.so",
    "libvkd3d.so",
    "libd3d12.so",
    "d3d12.so",
};

// Community builds carry version suffixes (libvkd3d_proton-3.0.so and similar), so any 64-bit
// .so whose name starts with one of these prefixes is tried. Names outside this set are never
// loaded, which keeps a stray Vulkan driver or unrelated library from being dlopened. Keep the
// prefixes in sync with VKD3D_LIBRARY_NAMES in the launcher's LauncherActivity.
static bool LooksLikeVkd3dLibrary(const std::filesystem::path &path)
{
    const std::string name = path.filename().string();
    if (name.size() < 4 || name.compare(name.size() - 3, 3, ".so") != 0)
        return false;

    for (const char *prefix : { "vkd3d", "libvkd3d", "d3d12", "libd3d12" })
    {
        if (name.rfind(prefix, 0) == 0)
            return true;
    }

    return false;
}

// Entry points that prove a loaded library really exposes a D3D12 device factory rather than
// being an unrelated shared object with a similar name. Only looked up, never called: probing
// must not execute code from a user supplied library, that is the renderer's job.
static const char *const g_vkd3dEntryPoints[] = {
    "D3D12CreateDevice",
    "D3D12GetDebugInterface",
    "vkd3d_create_device",
    "vkd3d_get_version_string",
};

// Folders searched, in priority order. nativeLibraryDir is where the APK unpacks bundled
// libraries, so a vkd3d runtime added to the build lands there; the *_import folders mirror the
// custom Vulkan driver workflow (drop a file, relaunch) and are reachable without a PC.
static const char *const g_vkd3dImportFolderNames[] = {
    "vkd3d",
    "vkd3d_import",
};

static std::string GetNativeLibraryDir()
{
    JNIEnv *env = static_cast<JNIEnv *>(SDL_AndroidGetJNIEnv());
    jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
    if (env == nullptr || activity == nullptr)
        return {};

    std::string result;
    jclass activityClass = env->GetObjectClass(activity);
    jmethodID getApplicationInfoMethod = activityClass != nullptr
        ? env->GetMethodID(activityClass, "getApplicationInfo", "()Landroid/content/pm/ApplicationInfo;")
        : nullptr;

    if (getApplicationInfoMethod != nullptr)
    {
        jobject applicationInfo = env->CallObjectMethod(activity, getApplicationInfoMethod);
        jfieldID nativeLibraryDirField = applicationInfo != nullptr
            ? env->GetFieldID(env->GetObjectClass(applicationInfo), "nativeLibraryDir", "Ljava/lang/String;")
            : nullptr;

        if (nativeLibraryDirField != nullptr)
        {
            auto nativeLibraryDirString = static_cast<jstring>(env->GetObjectField(applicationInfo, nativeLibraryDirField));
            if (nativeLibraryDirString != nullptr)
            {
                const char *chars = env->GetStringUTFChars(nativeLibraryDirString, nullptr);
                if (chars != nullptr)
                {
                    result = chars;
                    env->ReleaseStringUTFChars(nativeLibraryDirString, chars);
                }
                env->DeleteLocalRef(nativeLibraryDirString);
            }
        }

        env->DeleteLocalRef(applicationInfo);
    }

    if (env->ExceptionCheck())
        env->ExceptionClear();

    env->DeleteLocalRef(activityClass);
    return result;
}

static std::vector<std::filesystem::path> GetSearchDirectories()
{
    std::vector<std::filesystem::path> directories;

    const std::string nativeLibraryDir = GetNativeLibraryDir();
    if (!nativeLibraryDir.empty())
        directories.emplace_back(nativeLibraryDir);

    const std::filesystem::path &internal = os::android::GetInternalFilesDir();
    const std::filesystem::path &external = os::android::GetExternalFilesDir();
    const std::filesystem::path &media = os::android::GetExternalMediaDir();
    const std::filesystem::path &gameRoot = os::android::GetDataRoot();

    for (const char *folder : g_vkd3dImportFolderNames)
    {
        if (!internal.empty())
            directories.emplace_back(internal / folder);
        if (!external.empty())
            directories.emplace_back(external / folder);
        if (!media.empty())
            directories.emplace_back(media / folder);
        if (!gameRoot.empty())
            directories.emplace_back(gameRoot / folder);
    }

    return directories;
}

// Exact names are checked first so the layout a build is shipped with wins over anything a user
// may have dropped next to it; the prefix scan then covers renamed community builds.
static std::vector<std::filesystem::path> CollectCandidates(const std::filesystem::path &directory)
{
    std::vector<std::filesystem::path> candidates;
    std::error_code ec;
    if (!std::filesystem::is_directory(directory, ec))
        return candidates;

    for (const char *name : g_vkd3dLibraryNames)
    {
        std::filesystem::path exact = directory / name;
        if (std::filesystem::is_regular_file(exact, ec))
            candidates.push_back(std::move(exact));
    }

    std::vector<std::filesystem::path> extras;
    for (const auto &entry : std::filesystem::directory_iterator(directory, ec))
    {
        std::error_code entryEc;
        if (!entry.is_regular_file(entryEc) || entryEc)
            continue;

        const std::filesystem::path &path = entry.path();
        if (std::find(candidates.begin(), candidates.end(), path) != candidates.end())
            continue;

        if (LooksLikeVkd3dLibrary(path))
            extras.push_back(path);
    }

    std::sort(extras.begin(), extras.end());
    candidates.insert(candidates.end(), extras.begin(), extras.end());
    return candidates;
}

static bool IsAarch64SharedObject(const std::filesystem::path &path, uint64_t &outSize)
{
    outSize = 0;
    std::error_code ec;
    const auto fileSize = std::filesystem::file_size(path, ec);
    if (ec)
        return false;

    outSize = static_cast<uint64_t>(fileSize);
    if (outSize < 20)
        return false;

    // Elf64 header: 0x7f 'E' 'L' 'F', EI_CLASS (64 bit) at 4, EI_DATA (LSB) at 5,
    // e_type at 16, e_machine (EM_AARCH64 = 183) at 18.
    unsigned char header[20]{};
    std::ifstream input(path, std::ios::binary);
    if (!input.read(reinterpret_cast<char *>(header), sizeof(header)))
        return false;

    if (header[0] != 0x7f || header[1] != 'E' || header[2] != 'L' || header[3] != 'F')
        return false;

    if (header[4] != 2 /* ELFCLASS64 */ || header[5] != 1 /* ELFDATA2LSB */)
        return false;

    const uint16_t eType = static_cast<uint16_t>(header[16] | (header[17] << 8));
    const uint16_t eMachine = static_cast<uint16_t>(header[18] | (header[19] << 8));

    // ET_DYN (3): shared objects and PIEs are what dlopen accepts on Android; a stray static
    // executable must never be loaded.
    return eType == 3 && eMachine == 183;
}

// Loads the candidate and checks that it exports a D3D12 entry point, then closes it again: the
// renderer opens the runtime itself when (and if) a D3D12 session is requested.
static bool ProbeLibrary(const std::filesystem::path &path, std::string &outNote)
{
    void *handle = dlopen(path.string().c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr)
    {
        const char *error = dlerror();
        outNote = error != nullptr ? std::string("failed to load: ") + error : "failed to load";
        return false;
    }

    bool foundEntryPoint = false;
    for (const char *entryPoint : g_vkd3dEntryPoints)
    {
        if (dlsym(handle, entryPoint) != nullptr)
        {
            foundEntryPoint = true;
            break;
        }
    }

    dlclose(handle);

    if (!foundEntryPoint)
        outNote = "loaded, but exports no D3D12 entry point (not a vkd3d runtime?)";

    return foundEntryPoint;
}

// Walks the search folders and keeps the first candidate that is a plausible arm64 library, so a
// file that obviously cannot work (wrong architecture, wrong format) does not hide a usable one
// next to it. A rejected candidate is still reported, because "there is a libvkd3d.so but it is
// not an Android arm64 library" is the single most likely mistake a user can make here.
static void DiscoverRuntime(os::android::Vkd3dRuntime &runtime)
{
    runtime.found = false;
    runtime.archOk = false;
    runtime.path.clear();
    runtime.size = 0;
    runtime.note.clear();

    std::filesystem::path firstRejected;

    for (const auto &directory : GetSearchDirectories())
    {
        for (const auto &candidate : CollectCandidates(directory))
        {
            uint64_t size = 0;
            if (!IsAarch64SharedObject(candidate, size))
            {
                if (firstRejected.empty())
                    firstRejected = candidate;
                continue;
            }

            runtime.found = true;
            runtime.archOk = true;
            runtime.path = candidate;
            runtime.size = size;
            runtime.note = "runtime present (not loaded yet)";
            return;
        }
    }

    if (!firstRejected.empty())
    {
        runtime.found = true;
        runtime.path = firstRejected;
        runtime.note = "file is not an arm64 shared object";
        return;
    }

    runtime.note = "no vkd3d runtime installed";
}

static const os::android::Vkd3dRuntime &GetCachedRuntime(bool loadProbe)
{
    static os::android::Vkd3dRuntime runtime = []
    {
        os::android::Vkd3dRuntime result;
#ifdef MARATHON_RECOMP_D3D12
        result.compiledIn = true;
#endif
        DiscoverRuntime(result);
        return result;
    }();

    if (loadProbe && !runtime.probed && runtime.found && runtime.archOk)
    {
        runtime.probed = true;
        std::string note;
        runtime.usable = ProbeLibrary(runtime.path, note);
        if (!runtime.usable)
            runtime.note = note;
        else
            runtime.note = "runtime loaded";
    }

    return runtime;
}

namespace os::android
{
    const Vkd3dRuntime &GetVkd3dRuntime(bool loadProbe)
    {
        return GetCachedRuntime(loadProbe);
    }

    bool IsDirectX12Available()
    {
        const Vkd3dRuntime &runtime = GetCachedRuntime(true);
        return runtime.compiledIn && runtime.usable;
    }

    void ReportDirectX12Availability(bool loadProbe)
    {
        const Vkd3dRuntime &runtime = GetCachedRuntime(loadProbe);

        if (!runtime.compiledIn)
        {
            LOG("DirectX 12 is not part of this build (configured without MARATHON_RECOMP_D3D12); "
                "the Vulkan renderer is used. See docs/DX12-VKD3D-ANDROID.md for what a vkd3d "
                "build would need.");
        }
        else if (runtime.usable)
        {
            LOGF("DirectX 12 runtime ready: {} ({} bytes).", runtime.path.string(),
                static_cast<unsigned long long>(runtime.size));
        }
        else if (runtime.found && (runtime.probed || !runtime.archOk))
        {
            // "probed" means dlopen/dlsym rejected it; "!archOk" is already decisive on its own,
            // so neither case needs the library to be loaded before the message can be stated.
            LOGF_WARNING("DirectX 12 runtime is unusable: {} ({}).", runtime.path.string(), runtime.note);
        }
        else if (runtime.found)
        {
            LOGF("DirectX 12 runtime present but not loaded, because Vulkan was selected: {}.",
                runtime.path.string());
        }
        else
        {
            LOG("No DirectX 12 runtime installed; continuing with Vulkan.");
        }

        // One machine parseable line the launcher reads back (LauncherActivity.refreshVkd3dStatus),
        // so the renderer option shows a probed verdict instead of a guess based on file names.
        // The token before '|' selects the message, the text after it is shown verbatim.
        const std::filesystem::path &external = os::android::GetExternalFilesDir();
        if (external.empty())
            return;

        std::string line;
        if (!runtime.compiledIn)
            line = "no-renderer-in-build";
        else if (runtime.usable)
            line = "ready|" + runtime.path.filename().string();
        else if (runtime.found && (!runtime.archOk || runtime.probed))
            line = "bad-runtime|" + runtime.path.filename().string() + " (" + runtime.note + ")";
        else if (runtime.found)
            line = "present-not-loaded|" + runtime.path.filename().string();
        else
            line = "missing-runtime|vkd3d_import/";

        std::error_code ec;
        std::filesystem::create_directories(external, ec);
        std::ofstream status(external / "vkd3d_status.txt", std::ios::binary | std::ios::trunc);
        if (status.is_open())
            status << line << "\n";
    }
}
