cmake_minimum_required(VERSION 3.22.1)
project("quickjs_bridge")

add_definitions(-D_GNU_SOURCE -DCONFIG_VERSION=\"2024-01-13\")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O2 -Wall -Wno-unused-parameter -Wno-sign-compare")
# Ensure 16KB page size alignment for Android 16 (API 36)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

include_directories(
    ${CMAKE_CURRENT_SOURCE_DIR}/src/main/cpp/quickjs
)

add_library(
    quickjs_bridge
    SHARED
    src/main/cpp/quickjs/quickjs.c
    src/main/cpp/quickjs/cutils.c
    src/main/cpp/quickjs/dtoa.c
    src/main/cpp/quickjs/libregexp.c
    src/main/cpp/quickjs/libunicode.c
    src/main/cpp/quickjs_jni.c
)

find_library(log-lib log)
find_library(m-lib m)

target_link_libraries(
    quickjs_bridge
    ${log-lib}
    ${m-lib}
)
