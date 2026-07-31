cmake_minimum_required(VERSION 3.22.1)

project("marathondroid" C)

# The shared-memory pad ABI lives with the game so both sides can never drift apart.
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
