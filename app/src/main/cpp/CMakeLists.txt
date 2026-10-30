cmake_minimum_required(VERSION 3.22.1)

project(offlineassistant_whisper)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/whisper.cpp")
set(TALK_LLAMA_DIR "${WHISPER_DIR}/examples/talk-llama")

# Android 15+ devices can run with 16 KB memory pages. Ensure every shared
# object built here has LOAD segments aligned for those devices.
add_link_options("-Wl,-z,max-page-size=16384")

set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
option(OFFLINE_ASSISTANT_ASR_VULKAN "Enable Vulkan for local ASR benchmark builds" OFF)
set(GGML_VULKAN ${OFFLINE_ASSISTANT_ASR_VULKAN} CACHE BOOL "" FORCE)

add_subdirectory("${WHISPER_DIR}" "${CMAKE_BINARY_DIR}/whisper.cpp")

if(OFFLINE_ASSISTANT_ASR_VULKAN AND OFFLINE_ASSISTANT_VULKAN_HEADERS_DIR AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE "${OFFLINE_ASSISTANT_VULKAN_HEADERS_DIR}")
endif()
if(OFFLINE_ASSISTANT_ASR_VULKAN AND OFFLINE_ASSISTANT_SPIRV_HEADERS_INCLUDE_DIR AND TARGET ggml-vulkan)
    target_include_directories(ggml-vulkan PRIVATE "${OFFLINE_ASSISTANT_SPIRV_HEADERS_INCLUDE_DIR}")
endif()

foreach(target whisper ggml ggml-base ggml-cpu ggml-vulkan parakeet)
    if(TARGET ${target})
        target_link_options(${target} PRIVATE "-Wl,-z,max-page-size=16384")
    endif()
endforeach()

add_library(
    offlineassistant_whisper
    SHARED
    native_whisper.cpp
)

target_include_directories(
    offlineassistant_whisper
    PRIVATE
    "${WHISPER_DIR}/include"
)

find_library(log-lib log)

target_link_libraries(
    offlineassistant_whisper
    PRIVATE
    whisper
    ${log-lib}
)

target_link_options(
    offlineassistant_whisper
    PRIVATE
    "-Wl,-z,max-page-size=16384"
)

