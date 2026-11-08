cmake_minimum_required(VERSION 3.22.1)
project(lilac_local_ai CXX C)

# The same llama.cpp release the iOS app is built with (iosApp/scripts/build-native.sh): b11490.
# CI clones it into androidApp/build/llama.cpp first; a local build fetches it here.
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/llama.cpp")
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

# GPU: llama.cpp's Vulkan backend (Exynos Xclipse, Mali, Adreno). The NDK has vulkan.h but not the
# C++ vulkan.hpp or a SPIRV-Headers CMake package, so CI puts both in androidApp/build first
# (.github/workflows/android.yml). Without them the app builds CPU-only, as before.
set(VULKAN_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/vulkan-headers")
set(SPIRV_HEADERS "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/spirv-headers")
if (EXISTS "${VULKAN_HEADERS}/include/vulkan/vulkan.hpp" AND
    EXISTS "${SPIRV_HEADERS}/share/cmake/SPIRV-Headers/SPIRV-HeadersConfig.cmake")
    if (CMAKE_HOST_WIN32)
        set(NDK_HOST windows-x86_64)
    elseif (CMAKE_HOST_APPLE)
        set(NDK_HOST darwin-x86_64)
    else()
        set(NDK_HOST linux-x86_64)
    endif()
    find_program(LILAC_GLSLC glslc PATHS "${ANDROID_NDK}/shader-tools/${NDK_HOST}" NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
    # ggml-vulkan calls Vulkan 1.1 entry points (vkGetPhysicalDeviceFeatures2), which the NDK's
    # libvulkan.so stub exports from API 28 on. Android 8.x (API 26-27) cannot load the library then,
    # but those devices lack the Vulkan 1.2 llama.cpp needs anyway.
    set(LILAC_VULKAN_API ${ANDROID_PLATFORM_LEVEL})
    if (LILAC_VULKAN_API LESS 28)
        set(LILAC_VULKAN_API 28)
    endif()
    find_library(LILAC_VULKAN_LIBRARY vulkan
        PATHS "${CMAKE_SYSROOT}/usr/lib/${CMAKE_LIBRARY_ARCHITECTURE}/${LILAC_VULKAN_API}"
        NO_DEFAULT_PATH NO_CMAKE_FIND_ROOT_PATH)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(Vulkan_INCLUDE_DIR "${VULKAN_HEADERS}/include" CACHE PATH "" FORCE)
    set(Vulkan_GLSLC_EXECUTABLE "${LILAC_GLSLC}" CACHE FILEPATH "" FORCE)
    if (LILAC_VULKAN_LIBRARY)
        set(Vulkan_LIBRARY "${LILAC_VULKAN_LIBRARY}" CACHE FILEPATH "" FORCE)
    endif()
    set(SPIRV-Headers_DIR "${SPIRV_HEADERS}/share/cmake/SPIRV-Headers" CACHE PATH "" FORCE)
    # ggml-vulkan includes <spirv/unified1/spirv.hpp> without linking the SPIRV-Headers target.
    include_directories(BEFORE SYSTEM "${VULKAN_HEADERS}/include" "${SPIRV_HEADERS}/include")
    message(STATUS "lilac: Vulkan GPU backend on (glslc: ${LILAC_GLSLC}, libvulkan: ${LILAC_VULKAN_LIBRARY})")
else()
    set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
    message(STATUS "lilac: Vulkan headers not in androidApp/build; CPU-only build")
endif()

if (EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    add_subdirectory("${LLAMA_DIR}" "${CMAKE_BINARY_DIR}/llama")
else()
    include(FetchContent)
    FetchContent_Declare(llama GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git GIT_TAG b11490 GIT_SHALLOW TRUE)
    FetchContent_MakeAvailable(llama)
endif()

# iOS's own wrapper (iosApp/Native/Sources/LilacLocalAI): the model, chat templates (Jinja) and generation.
set(NATIVE "${CMAKE_CURRENT_SOURCE_DIR}/../../../../iosApp/Native/Sources/LilacLocalAI")
file(GLOB JINJA_SOURCES "${NATIVE}/Jinja/*.cpp")
add_library(lilac_local_ai SHARED lilac_jni.cpp "${NATIVE}/LocalAI.cpp" ${JINJA_SOURCES})
set_target_properties(lilac_local_ai PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_include_directories(lilac_local_ai PRIVATE "${NATIVE}/include" "${NATIVE}" "${CMAKE_CURRENT_SOURCE_DIR}/shim")
target_compile_options(lilac_local_ai PRIVATE -O3)
target_link_libraries(lilac_local_ai PRIVATE llama android log)
