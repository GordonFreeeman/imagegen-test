cmake_minimum_required(VERSION 3.22.1)
project(localflux LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_GGML_LIB OFF CACHE BOOL "" FORCE)

set(SDCPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/stable-diffusion.cpp")
if(NOT EXISTS "${SDCPP_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "stable-diffusion.cpp not vendored. Run scripts/vendor-deps.sh first.")
endif()

add_subdirectory("${SDCPP_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/stable-diffusion")

# Android's NDK ships the C Vulkan headers but not the C++ vulkan.hpp wrapper
# required by current ggml-vulkan. CI installs Vulkan-Headers on the host; expose
# those header-only declarations explicitly to the cross-compiled target.
if(ANDROID AND TARGET ggml-vulkan)
    set(ANDROID_VULKAN_HPP_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../vendor/vulkan-headers")
    if(EXISTS "${ANDROID_VULKAN_HPP_DIR}/vulkan/vulkan.hpp")
        target_include_directories(ggml-vulkan PRIVATE "${ANDROID_VULKAN_HPP_DIR}")
    else()
        message(FATAL_ERROR "Android Vulkan C++ headers were not staged at ${ANDROID_VULKAN_HPP_DIR}")
    endif()
endif()

add_library(localflux SHARED native-lib.cpp)

target_include_directories(localflux PRIVATE "${SDCPP_DIR}/include")
target_link_libraries(localflux PRIVATE stable-diffusion android log)

target_link_options(localflux PRIVATE "-Wl,-z,max-page-size=16384")
