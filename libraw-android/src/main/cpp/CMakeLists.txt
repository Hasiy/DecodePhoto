cmake_minimum_required(VERSION 3.22.1)
project(libraw_android LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_library(jnigraphics_library jnigraphics)
find_library(log_library log)
find_library(math_library m)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libraw)
file(GLOB_RECURSE LIBRAW_SOURCES CONFIGURE_DEPENDS ${LIBRAW_ROOT}/src/*.cpp)
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*/postprocessing/postprocessing_ph\\.cpp$")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*/preprocessing/preprocessing_ph\\.cpp$")
list(FILTER LIBRAW_SOURCES EXCLUDE REGEX ".*/write/write_ph\\.cpp$")

add_library(libraw STATIC ${LIBRAW_SOURCES})
set_target_properties(libraw PROPERTIES POSITION_INDEPENDENT_CODE ON)
target_include_directories(libraw PUBLIC ${LIBRAW_ROOT})
target_compile_definitions(libraw PRIVATE LIBRAW_NODLL)
target_compile_options(libraw PRIVATE -w)
target_link_libraries(libraw PUBLIC ${math_library})

add_library(libraw_android_bridge SHARED libraw_decoder.cpp)
target_include_directories(libraw_android_bridge PRIVATE ${LIBRAW_ROOT})
target_link_libraries(
    libraw_android_bridge
    PRIVATE
    libraw
    ${jnigraphics_library}
    ${log_library}
    ${math_library}
)
target_compile_options(libraw_android_bridge PRIVATE -Werror)

# NDK 27 can emit 16 KiB-aligned shared libraries for current Android devices.
target_link_options(libraw_android_bridge PRIVATE "-Wl,-z,max-page-size=16384")
