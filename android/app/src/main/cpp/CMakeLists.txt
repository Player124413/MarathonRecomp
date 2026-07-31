cmake_minimum_required(VERSION 3.22.1)

project("marathondroid" C)

# ---------------------------------------------------------------------------
# Shared pad ABI
#
# The shared-memory pad ABI lives with the game so both sides can never drift apart.
# ---------------------------------------------------------------------------
set(MARATHON_HID_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../MarathonRecomp/hid")

if (NOT EXISTS "${MARATHON_HID_DIR}/virtual_pad_shm.h")
    message(FATAL_ERROR
        "virtual_pad_shm.h not found at ${MARATHON_HID_DIR}. "
        "The launcher must be built from inside the MarathonRecomp checkout.")
endif()

add_library(marathondroid SHARED
    vpad_bridge.c
    process.c
)

target_include_directories(marathondroid PRIVATE
    "${CMAKE_CURRENT_SOURCE_DIR}"
    "${MARATHON_HID_DIR}"
)

target_compile_options(marathondroid PRIVATE
    -Wall
    -Wextra
    -Wno-unused-parameter
)

find_library(log-lib log)

target_link_libraries(marathondroid ${log-lib})

# ---------------------------------------------------------------------------
# box64, built straight into the APK
#
# Android 10+ refuses to exec() anything from the app's writable data directory
# (W^X). The one supported escape hatch is the APK's own native library folder:
# files there are extracted read-only by the package installer, already carry the
# execute bit, and are exempt from the no-exec mount.
#
# The installer only extracts files matching lib*.so, so the box64 *executable* is
# renamed to "libbox64.so". It remains an ordinary ARM64 PIE executable - only the
# file name changes - and the launcher runs it from nativeLibraryDir.
#
# Requires android:extractNativeLibs="true" and useLegacyPackaging for jniLibs
# (both set in the Gradle/manifest config); otherwise the library is never written
# to disk as a real file and there is nothing to execute.
#
# box64 is built with ExternalProject rather than add_subdirectory on purpose: its
# CMakeLists uses CMAKE_SOURCE_DIR in ~76 places (its version parsing, script copies
# and install rules all assume it is the top-level project), which silently resolves
# to *this* directory when included as a sub-project and breaks the configure step.
# A separate CMake invocation gives it the top-level context it expects.
# ---------------------------------------------------------------------------
option(MARATHON_DROID_BUNDLE_BOX64 "Build box64 into the APK" ON)

set(MARATHON_BOX64_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../box64")

if (MARATHON_DROID_BUNDLE_BOX64)
    if (NOT EXISTS "${MARATHON_BOX64_DIR}/CMakeLists.txt")
        message(FATAL_ERROR
            "box64 sources are missing at ${MARATHON_BOX64_DIR}.\n"
            "Run: git submodule update --init --depth 1 android/box64\n"
            "or build with -PbundleBox64=false to leave it out.")
    endif()

    include(ExternalProject)

    # Where Gradle wants the packaged .so for this ABI.
    set(MARATHON_JNILIBS_DIR "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}")
    set(MARATHON_BOX64_OUTPUT "${MARATHON_JNILIBS_DIR}/libbox64.so")

    ExternalProject_Add(box64_external
        SOURCE_DIR "${MARATHON_BOX64_DIR}"
        PREFIX "${CMAKE_CURRENT_BINARY_DIR}/box64"
        CMAKE_ARGS
            # Re-use the exact NDK toolchain Gradle handed us, so box64 is built for
            # the same ABI, API level and page size as the rest of the app.
            "-DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}"
            "-DANDROID_ABI=${ANDROID_ABI}"
            "-DANDROID_PLATFORM=${ANDROID_PLATFORM}"
            "-DANDROID_NDK=${ANDROID_NDK}"
            "-DANDROID_STL=c++_shared"
            "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            "-DCMAKE_BUILD_TYPE=Release"
            # box64's own switches.
            "-DANDROID=ON"
            "-DARM64=ON"
            "-DNOGIT=ON"      # CI exports have no .git to read a revision from
            "-DBOX32=OFF"     # 32-bit guests are not needed for this game
            "-DHAVE_TRACE=OFF"
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target box64
        # box64's install rules target a Linux/Termux prefix; the binary is copied
        # out by hand instead.
        INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory "${MARATHON_JNILIBS_DIR}"
                COMMAND ${CMAKE_COMMAND} -E copy_if_different
                        <BINARY_DIR>/box64 "${MARATHON_BOX64_OUTPUT}"
        BUILD_BYPRODUCTS "${MARATHON_BOX64_OUTPUT}"
        USES_TERMINAL_BUILD TRUE
    )

    # Make the ordinary app build produce box64 too.
    add_dependencies(marathondroid box64_external)

    message(STATUS "box64 will be packaged as ${MARATHON_BOX64_OUTPUT}")
endif()
