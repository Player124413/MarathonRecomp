project("MarathonRecomp")

if (WIN32)
    option(MARATHON_RECOMP_D3D12 "Add D3D12 support for rendering" ON)
endif()

if (APPLE)
    option(MARATHON_RECOMP_METAL "Add Metal support for rendering" ON)
endif()

if (CMAKE_SYSTEM_NAME MATCHES "Linux")
    option(MARATHON_RECOMP_FLATPAK "Configure the build for Flatpak compatibility." OFF)
endif()

function(BIN2C)
    cmake_parse_arguments(BIN2C_ARGS "" "TARGET_OBJ;SOURCE_FILE;DEST_FILE;ARRAY_NAME;COMPRESSION_TYPE" "" ${ARGN})

    if(NOT BIN2C_ARGS_TARGET_OBJ)
        message(FATAL_ERROR "TARGET_OBJ not specified.")
    endif()

    if(NOT BIN2C_ARGS_SOURCE_FILE)
        message(FATAL_ERROR "SOURCE_FILE not specified.")
    endif()

    if(NOT BIN2C_ARGS_DEST_FILE)
        set(BIN2C_ARGS_DEST_FILE "${BIN2C_ARGS_SOURCE_FILE}")
    endif()

    if(NOT BIN2C_ARGS_COMPRESSION_TYPE)
        set(BIN2C_ARGS_COMPRESSION_TYPE "none")
    endif()

    if (MARATHON_RECOMP_USE_PREBUILT_HOST_TOOLS)
        set(MARATHON_RECOMP_FILE_TO_C_COMMAND "${MARATHON_RECOMP_HOST_FILE_TO_C}")
        set(MARATHON_RECOMP_FILE_TO_C_TOOL_DEPENDS "${MARATHON_RECOMP_HOST_FILE_TO_C}")
    else()
        set(MARATHON_RECOMP_FILE_TO_C_COMMAND "$<TARGET_FILE:file_to_c>")
        set(MARATHON_RECOMP_FILE_TO_C_TOOL_DEPENDS file_to_c)
    endif()

    add_custom_command(OUTPUT "${BIN2C_ARGS_DEST_FILE}.c"
        COMMAND ${MARATHON_RECOMP_FILE_TO_C_COMMAND} "${BIN2C_ARGS_SOURCE_FILE}" "${BIN2C_ARGS_ARRAY_NAME}" "${BIN2C_ARGS_COMPRESSION_TYPE}" "${BIN2C_ARGS_DEST_FILE}.c" "${BIN2C_ARGS_DEST_FILE}.h"
        DEPENDS "${BIN2C_ARGS_SOURCE_FILE}" ${MARATHON_RECOMP_FILE_TO_C_TOOL_DEPENDS}
        BYPRODUCTS "${BIN2C_ARGS_DEST_FILE}.h"
        COMMENT "Generating binary header for ${BIN2C_ARGS_SOURCE_FILE}..."
    )

    set_source_files_properties(${BIN2C_ARGS_DEST_FILE}.c PROPERTIES SKIP_PRECOMPILE_HEADERS ON)
    target_sources(${BIN2C_ARGS_TARGET_OBJ} PRIVATE ${BIN2C_ARGS_DEST_FILE}.c)
endfunction()

add_compile_options(
    -fno-strict-aliasing
    -Wno-switch
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -Wno-void-pointer-to-int-cast
    -Wno-int-to-void-pointer-cast
    -Wno-invalid-offsetof
    -Wno-null-arithmetic
    -Wno-null-conversion
    -Wno-tautological-undefined-compare
)

if (WIN32)
    add_compile_options(/fp:strict)
else()
    add_compile_options(-ffp-model=strict)
endif()

if (NOT ANDROID)
    # On Android, main() must stay as SDL_main() (via SDL_main.h's macro) so that
    # SDL2's Android JNI glue (which calls SDL_main from a thread spawned by
    # SDLActivity's nativeInit) has something to call; SDL_MAIN_HANDLED would suppress that.
    add_compile_definitions(SDL_MAIN_HANDLED)
endif()

add_compile_definitions(
    _DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR # Microsoft wtf?
    _CRT_SECURE_NO_WARNINGS)

set(MARATHON_RECOMP_PRECOMPILED_HEADERS
    "stdafx.h"
)

set(MARATHON_RECOMP_KERNEL_CXX_SOURCES
    "kernel/imports.cpp"
    "kernel/xdm.cpp"
    "kernel/heap.cpp"
    "kernel/memory.cpp"
    "kernel/xam.cpp"
    "kernel/io/file_system.cpp"
)

set(MARATHON_RECOMP_LOCALE_CXX_SOURCES
    "locale/achievement_locale.cpp"
    "locale/config_locale.cpp"
    "locale/locale.cpp"
)

if (WIN32)
    set(MARATHON_RECOMP_OS_CXX_SOURCES
        "os/win32/logger_win32.cpp"
        "os/win32/media_win32.cpp"
        "os/win32/process_win32.cpp"
        "os/win32/user_win32.cpp"
        "os/win32/version_win32.cpp"
    )
elseif (ANDROID)
    set(MARATHON_RECOMP_OS_CXX_SOURCES
        "os/android/logger_android.cpp"
        "os/android/media_android.cpp"
        "os/android/process_android.cpp"
        "os/android/storage_android.cpp"
        "os/android/user_android.cpp"
        "os/android/version_android.cpp"
        "os/android/vkd3d_android.cpp"
        "os/android/vulkan_driver_android.cpp"
    )
elseif (CMAKE_SYSTEM_NAME MATCHES "Linux")
    set(MARATHON_RECOMP_OS_CXX_SOURCES
        "os/linux/logger_linux.cpp"
        "os/linux/media_linux.cpp"
        "os/linux/process_linux.cpp"
        "os/linux/user_linux.cpp"
        "os/linux/version_linux.cpp"
    )
elseif (APPLE)
    set(MARATHON_RECOMP_OS_CXX_SOURCES
        "os/macos/logger_macos.cpp"
        "os/macos/media_macos.cpp"
        "os/macos/process_macos.cpp"
        "os/macos/user_macos.cpp"
        "os/macos/version_macos.cpp"
    )
endif()

set(MARATHON_RECOMP_CPU_CXX_SOURCES
    "cpu/guest_thread.cpp"
)

set(MARATHON_RECOMP_GPU_CXX_SOURCES
    "gpu/video.cpp"
    "gpu/imgui/imgui_common.cpp"
    "gpu/imgui/imgui_font_builder.cpp"
    "gpu/imgui/imgui_snapshot.cpp"
)

set(MARATHON_RECOMP_APU_CXX_SOURCES
    "apu/audio.cpp"
    "apu/xma_decoder.cpp"
    "apu/embedded_player.cpp"
    "apu/driver/sdl2_driver.cpp"
)

set(MARATHON_RECOMP_HID_CXX_SOURCES
    "hid/hid.cpp"
    "hid/driver/sdl_hid.cpp"
)

set(MARATHON_RECOMP_PATCHES_CXX_SOURCES
    "patches/aspect_ratio_patches.cpp"
    "patches/audio_patches.cpp"
    "patches/camera_patches.cpp"
    "patches/fps_patches.cpp"
    "patches/frontend_listener.cpp"
    "patches/input_patches.cpp"
    "patches/loading_patches.cpp"
    "patches/MainMenuTask_patches.cpp"
    "patches/misc_patches.cpp"
    "patches/pause_patches.cpp"
    "patches/player_patches.cpp"
    "patches/SaveDataTask_patches.cpp"
    "patches/text_patches.cpp"
    "patches/TitleTask_patches.cpp"
    "patches/video_patches.cpp"
)

set(MARATHON_RECOMP_UI_CXX_SOURCES
    "ui/achievement_menu.cpp"
    "ui/achievement_overlay.cpp"
    "ui/black_bar.cpp"
    "ui/button_window.cpp"
    "ui/common_menu.cpp"
    "ui/fader.cpp"
    "ui/game_window.cpp"
    "ui/imgui_utils.cpp"
    "ui/message_window.cpp"
    "ui/options_menu.cpp"
)

# The GUI installer wizard (native file picker + wizard UI) is not built for Android;
# game files are installed from the app's launcher instead. See main.cpp __ANDROID__ guards.
if (NOT ANDROID)
    list(APPEND MARATHON_RECOMP_UI_CXX_SOURCES "ui/installer_wizard.cpp")
else()
    # On-screen touch controls are Android-only (they reference the Android-only
    # config entries, which are guarded in user/config*.h).
    list(APPEND MARATHON_RECOMP_UI_CXX_SOURCES "ui/touch_controls.cpp")
endif()

set(MARATHON_RECOMP_INSTALL_CXX_SOURCES
    "install/installer.cpp"
    "install/iso_file_system.cpp"
    "install/update_checker.cpp"
    "install/xcontent_file_system.cpp"
    "install/hashes/game.cpp"
    "install/hashes/episode_sonic.cpp"
    "install/hashes/episode_shadow.cpp"
    "install/hashes/episode_silver.cpp"
    "install/hashes/episode_amigo.cpp"
    "install/hashes/mission_sonic.cpp"
    "install/hashes/mission_shadow.cpp"
    "install/hashes/mission_silver.cpp"
)

set(MARATHON_RECOMP_USER_CXX_SOURCES
    "user/achievement_data.cpp"
    "user/achievement_manager.cpp"
    "user/config.cpp"
    "user/registry.cpp"
    "user/paths.cpp"
)

set(MARATHON_RECOMP_MOD_CXX_SOURCES
    "mod/mod_loader.cpp"
)

set(MARATHON_RECOMP_UTILS_CXX_SOURCES
    "utils/bit_stream.cpp"
    "utils/ring_buffer.cpp"
)

set(MARATHON_RECOMP_THIRDPARTY_SOURCES
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui/backends/imgui_impl_sdl2.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui/imgui.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui/imgui_demo.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui/imgui_draw.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui/imgui_tables.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui/imgui_widgets.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/implot/implot.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/implot/implot_demo.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/implot/implot_items.cpp"
    "${MARATHON_RECOMP_TOOLS_ROOT}/XenosRecomp/thirdparty/smol-v/source/smolv.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/etcpak/ProcessRGB.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/etcpak/Tables.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/etcpak/Dither.cpp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/miniz/miniz.c"
)

set(MARATHON_RECOMP_THIRDPARTY_INCLUDES
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/bcdec"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/concurrentqueue"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/ddspp"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/etcpak"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/miniz"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/imgui"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/implot"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/json/include"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/magic_enum/include"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/stb"
    "${MARATHON_RECOMP_THIRDPARTY_ROOT}/unordered_dense/include"
    "${MARATHON_RECOMP_TOOLS_ROOT}/bc_diff"
    "${MARATHON_RECOMP_TOOLS_ROOT}/XenosRecomp/thirdparty/smol-v/source"
)

set_source_files_properties(${MARATHON_RECOMP_THIRDPARTY_SOURCES} PROPERTIES SKIP_PRECOMPILE_HEADERS ON)

set(MARATHON_RECOMP_CXX_SOURCES
    "app.cpp"
    "exports.cpp"
    "main.cpp"
    "misc_impl.cpp"
    "preload_executable.cpp"
    "sdl_listener.cpp"
    "stdafx.cpp"
    "version.cpp"
    
    ${MARATHON_RECOMP_KERNEL_CXX_SOURCES}
    ${MARATHON_RECOMP_LOCALE_CXX_SOURCES}
    ${MARATHON_RECOMP_OS_CXX_SOURCES}
    ${MARATHON_RECOMP_CPU_CXX_SOURCES}
    ${MARATHON_RECOMP_GPU_CXX_SOURCES}
    ${MARATHON_RECOMP_APU_CXX_SOURCES}
    ${MARATHON_RECOMP_HID_CXX_SOURCES}
    ${MARATHON_RECOMP_PATCHES_CXX_SOURCES}
    ${MARATHON_RECOMP_UI_CXX_SOURCES}
    ${MARATHON_RECOMP_INSTALL_CXX_SOURCES}
    ${MARATHON_RECOMP_USER_CXX_SOURCES}
    ${MARATHON_RECOMP_UTILS_CXX_SOURCES}
    ${MARATHON_RECOMP_MOD_CXX_SOURCES}
    ${MARATHON_RECOMP_THIRDPARTY_SOURCES}
)

include("version.cmake")

set(VERSION_TXT "${PROJECT_SOURCE_DIR}/res/version.txt")

# Only show Git info and build type if not Release.
set(SHOW_GIT_INFO_AND_BUILD_TYPE 0)
if (NOT ${CMAKE_BUILD_TYPE} MATCHES "Release")
    set(SHOW_GIT_INFO_AND_BUILD_TYPE 1)
endif()

if (MARATHON_RECOMP_METAL)
    set(XCRUN_TOOL "/usr/bin/xcrun")
endif()

GenerateVersionSources(
    OUTPUT_DIR ${PROJECT_SOURCE_DIR}
    VERSION_TXT ${VERSION_TXT}
    H_TEMPLATE "${PROJECT_SOURCE_DIR}/res/version.h.template"
    CXX_TEMPLATE "${PROJECT_SOURCE_DIR}/res/version.cpp.template"
    BUILD_TYPE ${CMAKE_BUILD_TYPE}
    SHOW_GIT_INFO ${SHOW_GIT_INFO_AND_BUILD_TYPE}
    SHOW_BUILD_TYPE ${SHOW_GIT_INFO_AND_BUILD_TYPE}
)

if (WIN32)
    # Create binary version number for Win32 integer attributes.
    CreateVersionString(
        VERSION_TXT ${VERSION_TXT}
        OUTPUT_CSV 1
        OUTPUT_VAR WIN32_VERSION_BINARY
    )

    # Create string version number for Win32 detailed attributes.
    CreateVersionString(
        VERSION_TXT ${VERSION_TXT}
        BUILD_TYPE ${CMAKE_BUILD_TYPE}
        SHOW_GIT_INFO ${SHOW_GIT_INFO_AND_BUILD_TYPE}
        SHOW_BUILD_TYPE ${SHOW_GIT_INFO_AND_BUILD_TYPE}
        OUTPUT_VAR WIN32_VERSION_STRING
    )

    # Set Win32 icon path.
    set(WIN32_ICON_PATH "${PROJECT_SOURCE_DIR}/../MarathonRecompResources/images/game_icon.ico")

    configure_file("res/win32/res.rc.template" "${CMAKE_BINARY_DIR}/res.rc" @ONLY)
    add_executable(MarathonRecomp ${MARATHON_RECOMP_CXX_SOURCES} "${CMAKE_BINARY_DIR}/res.rc")

    # Hide console for release configurations.
    if (${CMAKE_BUILD_TYPE} MATCHES "Release")
        target_link_options(MarathonRecomp PRIVATE "/SUBSYSTEM:WINDOWS" "/ENTRY:mainCRTStartup")
    endif()
elseif (APPLE)
    # Create version number for app bundle.
    CreateVersionString(
            VERSION_TXT ${VERSION_TXT}
            OUTPUT_VAR MACOS_BUNDLE_VERSION
    )

    add_executable(MarathonRecomp MACOSX_BUNDLE
            ${MARATHON_RECOMP_CXX_SOURCES}
            res/macos/game_icon.icns
    )
    set_source_files_properties(res/macos/game_icon.icns PROPERTIES
            MACOSX_PACKAGE_LOCATION Resources)
    set_target_properties(MarathonRecomp PROPERTIES
            OUTPUT_NAME "Marathon Recompiled"
            MACOSX_BUNDLE_INFO_PLIST ${CMAKE_CURRENT_SOURCE_DIR}/res/macos/MacOSXBundleInfo.plist.in
            MACOSX_BUNDLE_GUI_IDENTIFIER sonicnext-dev.MarathonRecomp
            MACOSX_BUNDLE_BUNDLE_NAME "Marathon Recompiled"
            MACOSX_BUNDLE_BUNDLE_VERSION ${MACOS_BUNDLE_VERSION}
            MACOSX_BUNDLE_SHORT_VERSION_STRING ${MACOS_BUNDLE_VERSION}
            MACOSX_BUNDLE_ICON_FILE "game_icon.icns"
    )

    # Linking with MoltenVK directly would prevent using the system Vulkan loader to load with debug layers.
    # Instead, copy the MoltenVK dylib to the app bundle along with an ICD file for the loader to find it.
    # In the event the loader is not installed, the MoltenVK dylib can still be picked up directly in the app bundle.
    set(MVK_BUNDLED_PATH "Resources/vulkan/icd.d")
    set(MVK_DST "${CMAKE_CURRENT_BINARY_DIR}/Marathon Recompiled.app/Contents/${MVK_BUNDLED_PATH}")
    set_property(TARGET MarathonRecomp APPEND PROPERTY BUILD_RPATH "@executable_path/../${MVK_BUNDLED_PATH}")

    set(MVK_ICD_SRC "${MARATHON_RECOMP_THIRDPARTY_ROOT}/MoltenVK/MoltenVK/MoltenVK/icd/MoltenVK_icd.json")
    set(MVK_ICD_DST "${MVK_DST}/MoltenVK_icd.json")
    set(MVK_DYLIB_SRC "${CMAKE_BINARY_DIR}/thirdparty/MoltenVK/libMoltenVK.dylib")
    set(MVK_DYLIB_DST "${MVK_DST}/libMoltenVK.dylib")

    add_custom_command(
            OUTPUT ${MVK_DST}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${MVK_DST})
    add_custom_command(
            OUTPUT ${MVK_ICD_DST}
            DEPENDS ${MVK_ICD_SRC} ${MVK_DST}
            COMMAND ${CMAKE_COMMAND} -E copy ${MVK_ICD_SRC} ${MVK_ICD_DST})
    add_custom_command(
            OUTPUT ${MVK_DYLIB_DST}
            DEPENDS ${MVK_DYLIB_SRC} ${MVK_DST}
            COMMAND ${CMAKE_COMMAND} -E copy ${MVK_DYLIB_SRC} ${MVK_DYLIB_DST})
    add_custom_target(CopyMoltenVK DEPENDS ${MVK_ICD_DST} ${MVK_DYLIB_DST})
    add_dependencies(CopyMoltenVK MoltenVK)
    add_dependencies(MarathonRecomp CopyMoltenVK)
elseif (ANDROID)
    # Built as a shared library loaded via JNI from a Java Activity (the SDL2 Android
    # template's SDLActivity expects "libmain.so" by default via getLibraries()), rather
    # than a standalone executable, since Android apps cannot be launched as bare ELF binaries.
    add_library(MarathonRecomp SHARED ${MARATHON_RECOMP_CXX_SOURCES})
    set_target_properties(MarathonRecomp PROPERTIES OUTPUT_NAME "main")

    # Keep the Android release build focused on CPU-side guest emulation and
    # command recording. The default Android toolchain flags vary between NDKs;
    # explicitly enable the optimized release profile without using device-specific
    # instructions, so the same APK remains valid across ARM64 phones.
    if (CMAKE_BUILD_TYPE MATCHES "Release")
        target_compile_options(MarathonRecomp PRIVATE
            -O3
            -ffunction-sections
            -fdata-sections
        )
        target_link_options(MarathonRecomp PRIVATE
            -Wl,--gc-sections
        )
    endif()
else()
    add_executable(MarathonRecomp ${MARATHON_RECOMP_CXX_SOURCES})
endif()

if (MARATHON_RECOMP_FLATPAK)
    target_compile_definitions(MarathonRecomp PRIVATE 
        "MARATHON_RECOMP_FLATPAK"
        "GAME_INSTALL_DIRECTORY=\"/var/data\""
    )
endif()

if (ANDROID)
    # Game files are installed from the app's own launcher (SAF file picker) into the
    # app's private storage. This avoids /sdcard's FUSE-based scoped storage layer, which
    # produced spurious "Permission denied" errors on stat() shortly after directory
    # creation there. Must match applicationId in android-apk/app/build.gradle. Only a
    # fallback constant: storage_android.cpp resolves the real path at runtime via SDL.
    target_compile_definitions(MarathonRecomp PRIVATE
        "GAME_INSTALL_DIRECTORY=\"/data/data/com.sega.marathon/files/MarathonRecomp\""
    )

    target_link_libraries(MarathonRecomp PRIVATE adrenotools)

    # libadrenotools builds its hooks as separate shared libraries which
    # adrenotools_open_libvulkan() dlopens from the app's nativeLibraryDir at
    # runtime. Copy them next to libmain.so so the APK packaging step can pick
    # them up (the workflow copies every *.so from this directory into
    # jniLibs/arm64-v8a/).
    add_custom_command(TARGET MarathonRecomp POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            "$<TARGET_FILE:main_hook>"
            "$<TARGET_FILE:file_redirect_hook>"
            "$<TARGET_FILE:gsl_alloc_hook>"
            "$<TARGET_FILE:hook_impl>"
            "$<TARGET_FILE_DIR:MarathonRecomp>"
        COMMENT "Copying adrenotools hook libraries next to libmain.so"
    )
endif()

find_package(CURL REQUIRED)

if (MARATHON_RECOMP_METAL)
    target_compile_definitions(MarathonRecomp PRIVATE MARATHON_RECOMP_METAL)
endif()

if (MARATHON_RECOMP_D3D12)
    find_package(directx-headers CONFIG REQUIRED)
    find_package(directx12-agility CONFIG REQUIRED)
    target_compile_definitions(MarathonRecomp PRIVATE MARATHON_RECOMP_D3D12)

    if (WIN32)
        # Structured exception handling around D3D12 device creation (MSVC only) and the DXC
        # spec constant linker, which needs the dxcompiler runtime that only the Windows
        # deployment ships. Kept out of the generic D3D12 define so other D3D12 hosts (e.g. a
        # vkd3d-proton based Android build) can compile the same renderer without them.
        target_compile_definitions(MarathonRecomp PRIVATE MARATHON_RECOMP_D3D12_SEH MARATHON_RECOMP_D3D12_DXC)
    endif()

    file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/D3D12)
    add_custom_command(TARGET MarathonRecomp POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_PROPERTY:Microsoft::DirectX12-Core,IMPORTED_LOCATION_RELEASE> $<TARGET_FILE_DIR:MarathonRecomp>/D3D12
            COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_PROPERTY:Microsoft::DirectX12-Layers,IMPORTED_LOCATION_DEBUG> $<TARGET_FILE_DIR:MarathonRecomp>/D3D12
            COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_PROPERTY:Microsoft::DirectXShaderCompiler,IMPORTED_LOCATION> $<TARGET_FILE_DIR:MarathonRecomp>
            COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_PROPERTY:Microsoft::DXIL,IMPORTED_LOCATION> $<TARGET_FILE_DIR:MarathonRecomp>
            COMMAND_EXPAND_LISTS
    )

    target_link_libraries(MarathonRecomp PRIVATE
            Microsoft::DirectXShaderCompiler
            Microsoft::DXIL
            dxgi
    )
endif()

if (WIN32)
    target_link_libraries(MarathonRecomp PRIVATE
        comctl32
        dwmapi
        ntdll
        Shcore
        Synchronization
        winmm
        windowsapp
    )
endif()

if (ANDROID)
    # ------------------------------------------------------------------
    # DirectX 12 through a vkd3d runtime (experimental, off by default)
    #
    # Android has no D3D12 driver, so the only way the recompiled D3D12 renderer can run here is
    # against a translation library that turns ID3D12* calls into Vulkan calls. Upstream
    # vkd3d-proton builds that as d3d12.dll for Wine or as a development-only native library, and
    # neither is usable on Android out of the box: DXGI's CreateSwapChainForHwnd (used by plume's
    # D3D12 backend) has no ANativeWindow equivalent, and vkd3d's hard driver requirements
    # (Vulkan 1.3, VK_EXT_robustness2, VK_KHR_push_descriptor, full descriptor indexing) are not
    # met by the mobile drivers this port targets.
    #
    # This option therefore only wires up the build; enabling it deliberately fails the configure
    # step until every prerequisite is in place, so no one can ship an APK that advertises
    # DirectX 12 and then dies on device. docs/DX12-VKD3D-ANDROID.md lists the work items.
    option(MARATHON_RECOMP_D3D12_VKD3D "Build the D3D12 renderer for Android against a vkd3d runtime." OFF)
    set(MARATHON_RECOMP_VKD3D_ROOT "" CACHE PATH "Prefix of an Android vkd3d build (include/d3d12.h, lib/libvkd3d_proton.so)")

    if (MARATHON_RECOMP_D3D12_VKD3D)
        # Reuses the Windows plumbing that keys off this variable: the DXIL blobs in
        # compile_shader() below and the g_*_dxil headers video.cpp includes.
        set(MARATHON_RECOMP_D3D12 ON)

        if (NOT MARATHON_RECOMP_VKD3D_ROOT)
            message(FATAL_ERROR
                "MARATHON_RECOMP_D3D12_VKD3D needs MARATHON_RECOMP_VKD3D_ROOT to point at an arm64 Android "
                "vkd3d build. There is no upstream one - see docs/DX12-VKD3D-ANDROID.md (step 1).")
        endif()

        if (NOT EXISTS "${MARATHON_RECOMP_VKD3D_ROOT}/include/d3d12.h")
            message(FATAL_ERROR
                "No d3d12.h under ${MARATHON_RECOMP_VKD3D_ROOT}/include. The D3D12 headers "
                "(directx-headers) must be provided for the Android build; vcpkg does not ship them for ANDROID.")
        endif()

        if (NOT EXISTS "${MARATHON_RECOMP_VKD3D_ROOT}/lib/libvkd3d_proton.so")
            message(FATAL_ERROR
                "No ${MARATHON_RECOMP_VKD3D_ROOT}/lib/libvkd3d_proton.so. That library has to be packaged into "
                "the APK (android-apk/.../jniLibs/arm64-v8a) or dropped into a vkd3d_import folder at run time.")
        endif()

        if (NOT PLUME_D3D12_ANDROID_ENABLED)
            message(FATAL_ERROR
                "plume builds its D3D12 backend for WIN32 only, and that backend still creates the swap chain "
                "with CreateSwapChainForHwnd. Port it to Android first (ANativeWindow surface + a DXGI shim) and "
                "set PLUME_D3D12_ANDROID_ENABLED=ON - see docs/DX12-VKD3D-ANDROID.md (steps 2 and 3).")
        endif()

        target_compile_definitions(MarathonRecomp PRIVATE MARATHON_RECOMP_D3D12 MARATHON_RECOMP_VKD3D)
        target_include_directories(MarathonRecomp PRIVATE "${MARATHON_RECOMP_VKD3D_ROOT}/include")

        # The runtime is resolved at run time (os/android/vkd3d_android.cpp) from the app's
        # native library directory or a vkd3d_import folder, so nothing is linked against it:
        # an APK with the renderer built in must still boot on devices without the library.
        message(STATUS "MarathonRecomp - D3D12 renderer enabled for Android via vkd3d (experimental).")
    endif()
endif()

target_link_libraries(MarathonRecomp PRIVATE
    fmt::fmt
    libzstd_static
    msdf-atlas-gen::msdf-atlas-gen
    o1heap
    XenonUtils
    SDL2::SDL2-static
    SDL2_mixer
    tomlplusplus::tomlplusplus
    MarathonRecompLib
    xxHash::xxhash
    CURL::libcurl
    plume
)

if (NOT ANDROID)
    target_link_libraries(MarathonRecomp PRIVATE nfd::nfd)
endif()

target_link_libraries(MarathonRecomp PRIVATE ffmpeg)

target_include_directories(MarathonRecomp PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    "${CMAKE_CURRENT_SOURCE_DIR}/api"
    ${MARATHON_RECOMP_THIRDPARTY_INCLUDES}
)

if (CMAKE_SYSTEM_NAME MATCHES "Linux")
    find_package(PkgConfig REQUIRED)
    find_package(X11 REQUIRED)
    pkg_search_module(GLIB REQUIRED glib-2.0)
    pkg_search_module(GIO REQUIRED gio-2.0)
    target_include_directories(MarathonRecomp PRIVATE ${X11_INCLUDE_DIR} ${GLIB_INCLUDE_DIRS} ${GIO_INCLUDE_DIRS})
    target_link_libraries(MarathonRecomp PRIVATE ${X11_LIBRARIES} ${GLIB_LIBRARIES} ${GIO_LIBRARIES})
endif()

target_precompile_headers(MarathonRecomp PUBLIC ${MARATHON_RECOMP_PRECOMPILED_HEADERS})

function(compile_shader FILE_PATH TARGET_NAME)
    set(HLSL_FILE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/gpu/shader/hlsl/${FILE_PATH}.hlsl)
    set(MSL_FILE_PATH ${CMAKE_CURRENT_SOURCE_DIR}/gpu/shader/msl/${FILE_PATH}.metal)
    cmake_path(GET HLSL_FILE_PATH STEM HLSL_NAME)
    cmake_path(GET MSL_FILE_PATH STEM MSL_NAME)
    if (MARATHON_RECOMP_METAL)
        add_custom_command(
                OUTPUT ${MSL_FILE_PATH}.ir
                COMMAND ${XCRUN_TOOL} -sdk macosx metal -o ${MSL_FILE_PATH}.ir -c ${MSL_FILE_PATH} -D__air__ -DMARATHON_RECOMP -frecord-sources -gline-tables-only
                DEPENDS ${MSL_FILE_PATH}
        )
        add_custom_command(
                OUTPUT ${MSL_FILE_PATH}.metallib
                COMMAND ${XCRUN_TOOL} -sdk macosx metallib -o ${MSL_FILE_PATH}.metallib ${MSL_FILE_PATH}.ir
                DEPENDS ${MSL_FILE_PATH}.ir
        )
        BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${MSL_FILE_PATH}.metallib" DEST_FILE "${MSL_FILE_PATH}.metallib" ARRAY_NAME "g_${MSL_NAME}_air")
    endif()
    if (MARATHON_RECOMP_D3D12)
        add_custom_command(
            OUTPUT ${HLSL_FILE_PATH}.dxil.h
            COMMAND ${DIRECTX_DXC_TOOL} -T ${TARGET_NAME} -HV 2021 -all-resources-bound -Wno-ignored-attributes -DMARATHON_RECOMP -E shaderMain -Fh ${HLSL_FILE_PATH}.dxil.h ${HLSL_FILE_PATH} -Vn g_${HLSL_NAME}_dxil
            DEPENDS ${HLSL_FILE_PATH}
        )
        target_sources(MarathonRecomp PRIVATE ${HLSL_FILE_PATH}.dxil.h)
    endif()
    add_custom_command(
        OUTPUT ${HLSL_FILE_PATH}.spirv.h
        COMMAND ${DIRECTX_DXC_TOOL} -T ${TARGET_NAME} -HV 2021 -all-resources-bound -spirv -fvk-use-dx-layout ${ARGN} -DMARATHON_RECOMP -E shaderMain -Fh ${HLSL_FILE_PATH}.spirv.h ${HLSL_FILE_PATH} -Vn g_${HLSL_NAME}_spirv
        DEPENDS ${HLSL_FILE_PATH}
    )
    target_sources(MarathonRecomp PRIVATE ${HLSL_FILE_PATH}.spirv.h)
endfunction()

function(compile_vertex_shader FILE_PATH)
    compile_shader(${FILE_PATH} vs_6_0 -fvk-invert-y)
endfunction()

function(compile_pixel_shader FILE_PATH)
    compile_shader(${FILE_PATH} ps_6_0)
endfunction()

compile_pixel_shader(blend_color_alpha_ps)
compile_pixel_shader(conditional_survey_ps)
compile_vertex_shader(copy_vs)
compile_pixel_shader(copy_color_ps)
compile_pixel_shader(copy_depth_ps)
compile_pixel_shader(csd_filter_ps)
compile_vertex_shader(csd_no_tex_vs)
compile_vertex_shader(csd_vs)
compile_vertex_shader(enhanced_burnout_blur_vs)
compile_pixel_shader(enhanced_burnout_blur_ps)
compile_pixel_shader(gaussian_blur_3x3)
compile_pixel_shader(gaussian_blur_5x5)
compile_pixel_shader(gaussian_blur_7x7)
compile_pixel_shader(gaussian_blur_9x9)
compile_pixel_shader(gamma_correction_ps)
compile_pixel_shader(imgui_ps)
compile_vertex_shader(imgui_vs)
compile_pixel_shader(resolve_msaa_color_2x)
compile_pixel_shader(resolve_msaa_color_4x)
compile_pixel_shader(resolve_msaa_color_8x)
compile_pixel_shader(resolve_msaa_depth_2x)
compile_pixel_shader(resolve_msaa_depth_4x)
compile_pixel_shader(resolve_msaa_depth_8x)

function(generate_aggregate_header INPUT_DIRECTORY OUTPUT_FILE)
    get_filename_component(ABS_OUTPUT_FILE "${OUTPUT_FILE}" ABSOLUTE)
    file(GLOB_RECURSE HEADER_FILES "${INPUT_DIRECTORY}/*.h")
    set(HEADER_CONTENT "#pragma once\n\n")

    foreach(HEADER_FILE IN LISTS HEADER_FILES)
        get_filename_component(ABS_HEADER_FILE "${HEADER_FILE}" ABSOLUTE)
        if (ABS_HEADER_FILE STREQUAL ABS_OUTPUT_FILE)
            continue()
        endif()
        file(RELATIVE_PATH RELATIVE_HEADER_FILE "${INPUT_DIRECTORY}" "${HEADER_FILE}")
        string(APPEND HEADER_CONTENT "#include \"${RELATIVE_HEADER_FILE}\"\n")
    endforeach()

    if (EXISTS "${OUTPUT_FILE}")
        file(READ "${OUTPUT_FILE}" EXISTING_CONTENT)
        if (EXISTING_CONTENT STREQUAL HEADER_CONTENT)
            return()
        endif()
    endif()

    file(WRITE "${OUTPUT_FILE}" "${HEADER_CONTENT}")
endfunction()

generate_aggregate_header(
    "${CMAKE_CURRENT_SOURCE_DIR}/api"
    "${CMAKE_CURRENT_SOURCE_DIR}/api/Marathon.h"
)

set(RESOURCES_SOURCE_PATH "${PROJECT_SOURCE_DIR}/../MarathonRecompResources")
set(RESOURCES_OUTPUT_PATH "${PROJECT_SOURCE_DIR}/res")

## Miscellaneous ##
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/bc_diff/button_bc_diff.bin" DEST_FILE "${RESOURCES_OUTPUT_PATH}/bc_diff/button_bc_diff.bin" ARRAY_NAME "g_button_bc_diff" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/font/im_font_atlas.bin" DEST_FILE "${RESOURCES_OUTPUT_PATH}/font/im_font_atlas.bin" ARRAY_NAME "g_im_font_atlas" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/font/im_font_atlas.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/font/im_font_atlas.dds" ARRAY_NAME "g_im_font_atlas_texture" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/button_window.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/button_window.dds" ARRAY_NAME "g_button_window" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/controller.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/controller.dds" ARRAY_NAME "g_controller" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/sonicnext-dev.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/sonicnext-dev.dds" ARRAY_NAME "g_sonicnextdev" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/kbm.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/kbm.dds" ARRAY_NAME "g_kbm" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/main_menu1.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/main_menu1.dds" ARRAY_NAME "g_main_menu1" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/main_menu7.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/main_menu7.dds" ARRAY_NAME "g_main_menu7" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/main_menu8.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/main_menu8.dds" ARRAY_NAME "g_main_menu8" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/main_menu9.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/main_menu9.dds" ARRAY_NAME "g_main_menu9" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/arrow.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/arrow.dds" ARRAY_NAME "g_arrow" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/window.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/window.dds" ARRAY_NAME "g_window" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/common/select_arrow.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/common/select_arrow.dds" ARRAY_NAME "g_select_arrow" COMPRESSION_TYPE "zstd")

## Installer ##
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_001.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_001.dds" ARRAY_NAME "g_install_001" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_002.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_002.dds" ARRAY_NAME "g_install_002" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_003.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_003.dds" ARRAY_NAME "g_install_003" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_004.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_004.dds" ARRAY_NAME "g_install_004" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_005.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_005.dds" ARRAY_NAME "g_install_005" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_006.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_006.dds" ARRAY_NAME "g_install_006" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_007.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_007.dds" ARRAY_NAME "g_install_007" COMPRESSION_TYPE "zstd")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/installer/install_008.dds" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/installer/install_008.dds" ARRAY_NAME "g_install_008" COMPRESSION_TYPE "zstd")

## Game Icon ##
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/images/game_icon.bmp" DEST_FILE "${RESOURCES_OUTPUT_PATH}/images/game_icon.bmp" ARRAY_NAME "g_game_icon")

## Audio ##
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/music/installer.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/music/installer.ogg" ARRAY_NAME "g_installer_music")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/window_open.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/window_open.ogg" ARRAY_NAME "g_window_open")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/window_close.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/window_close.ogg" ARRAY_NAME "g_window_close")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/cursor.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/cursor.ogg" ARRAY_NAME "g_cursor")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/deside.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/deside.ogg" ARRAY_NAME "g_deside")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/move.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/move.ogg" ARRAY_NAME "g_move")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/main_deside.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/main_deside.ogg" ARRAY_NAME "g_main_deside")
BIN2C(TARGET_OBJ MarathonRecomp SOURCE_FILE "${RESOURCES_SOURCE_PATH}/sounds/cannot_deside.ogg" DEST_FILE "${RESOURCES_OUTPUT_PATH}/sounds/cannot_deside.ogg" ARRAY_NAME "g_cannot_deside")
