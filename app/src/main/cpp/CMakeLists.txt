cmake_minimum_required(VERSION 3.22.1)
project(raw_photo_viewer LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(android_library android)
find_library(dl_library dl)
find_library(jnigraphics_library jnigraphics)
find_library(log_library log)

add_library(
    android_image_decoder
    SHARED
    dng_decoder.cpp
)
target_link_libraries(
    android_image_decoder
    PRIVATE
    ${android_library}
    ${dl_library}
    ${jnigraphics_library}
    ${log_library}
)
target_compile_options(android_image_decoder PRIVATE -Werror)

# NDK 27 can emit 16 KiB-aligned shared libraries for current Android devices.
target_link_options(android_image_decoder PRIVATE "-Wl,-z,max-page-size=16384")
