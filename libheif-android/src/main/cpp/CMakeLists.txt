cmake_minimum_required(VERSION 3.22.1)
project(libheif_android LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(jnigraphics_library jnigraphics)
find_library(log_library log)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(ENABLE_DECODER OFF CACHE BOOL "" FORCE)
set(ENABLE_ENCODER OFF CACHE BOOL "" FORCE)
set(ENABLE_SHERLOCK265 OFF CACHE BOOL "" FORCE)
set(ENABLE_INTERNAL_DEVELOPMENT_TOOLS OFF CACHE BOOL "" FORCE)
set(ENABLE_SDL OFF CACHE BOOL "" FORCE)
add_subdirectory(libde265 EXCLUDE_FROM_ALL)

set(LIBDE265_INCLUDE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/libde265 CACHE PATH "" FORCE)
set(
    LIBDE265_INCLUDE_DIRS
    "${CMAKE_CURRENT_SOURCE_DIR}/libde265;${libde265_BINARY_DIR}"
    CACHE STRING "" FORCE
)
set(LIBDE265_LIBRARY de265 CACHE STRING "" FORCE)
set(LIBDE265_LIBRARIES de265 CACHE STRING "" FORCE)
set(WITH_LIBDE265 ON CACHE BOOL "" FORCE)
set(WITH_LIBDE265_PLUGIN OFF CACHE BOOL "" FORCE)
set(ENABLE_PLUGIN_LOADING OFF CACHE BOOL "" FORCE)
set(WITH_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(WITH_X265 OFF CACHE BOOL "" FORCE)
set(WITH_X264 OFF CACHE BOOL "" FORCE)
set(WITH_OpenH264_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_LIBSHARPYUV OFF CACHE BOOL "" FORCE)
set(WITH_KVAZAAR OFF CACHE BOOL "" FORCE)
set(WITH_AOM_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_AOM_ENCODER OFF CACHE BOOL "" FORCE)
set(WITH_DAV1D OFF CACHE BOOL "" FORCE)
set(WITH_RAV1E OFF CACHE BOOL "" FORCE)
set(WITH_SvtEnc OFF CACHE BOOL "" FORCE)
set(WITH_FFMPEG_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_JPEG_DECODER OFF CACHE BOOL "" FORCE)
set(WITH_JPEG_ENCODER OFF CACHE BOOL "" FORCE)
add_subdirectory(libheif EXCLUDE_FROM_ALL)

target_compile_options(de265 PRIVATE -w)
target_compile_options(heif PRIVATE -w)

add_library(libheif_android_bridge SHARED libheif_decoder.cpp)
target_include_directories(
    libheif_android_bridge
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/libheif/libheif/api
    ${libheif_BINARY_DIR}
)
target_link_libraries(
    libheif_android_bridge
    PRIVATE
    heif
    de265
    ${jnigraphics_library}
    ${log_library}
)
target_compile_options(libheif_android_bridge PRIVATE -Werror)

# NDK 27 can emit 16 KiB-aligned shared libraries for current Android devices.
target_link_options(libheif_android_bridge PRIVATE "-Wl,-z,max-page-size=16384")
