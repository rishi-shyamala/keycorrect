# libkcllama: llama.cpp (static, CPU only) plus the JNI glue, as one shared library.
#
#   cmake -S core/llama/src/main/cpp -B build -DLLAMA_SRC=<llama.cpp source dir> \
#         [-DKC_JNI_INCLUDE="<jdk>/include;<jdk>/include/linux"]   # desktop only
#
# The Android build (:neuralpack) passes LLAMA_SRC and gets jni.h from the NDK; the desktop build
# (tools/llama/build-host.sh) also passes KC_JNI_INCLUDE.
cmake_minimum_required(VERSION 3.22)
project(kcllama C CXX)

if(NOT LLAMA_SRC OR NOT EXISTS "${LLAMA_SRC}/CMakeLists.txt")
  message(FATAL_ERROR "Pass -DLLAMA_SRC=<llama.cpp source directory>")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# CPU only, everything static, nothing that touches the network or builds a binary we do not ship.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_USE_PREBUILT_UI OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(LLAMA_TOOLS_INSTALL OFF CACHE BOOL "" FORCE)
set(LLAMA_TESTS_INSTALL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_HIP OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL OFF CACHE BOOL "" FORCE)
set(GGML_CCACHE OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_SRC}" "${CMAKE_BINARY_DIR}/llama.cpp" EXCLUDE_FROM_ALL)

add_library(kcllama SHARED kc_llama_jni.cpp)
target_link_libraries(kcllama PRIVATE llama)
target_compile_options(kcllama PRIVATE -O3)  # the log-sum-exp loop relies on auto-vectorisation
if(KC_JNI_INCLUDE)
  target_include_directories(kcllama PRIVATE ${KC_JNI_INCLUDE})
endif()
if(ANDROID)
  target_link_libraries(kcllama PRIVATE log)
endif()
# Keep the exported surface to the JNI functions.
set_target_properties(kcllama PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)
target_link_options(kcllama PRIVATE -Wl,--exclude-libs,ALL)
