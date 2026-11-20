# libkcswipenet: the JNI glue for FUTO Swipe's encoder plus the ExecuTorch runtime, built from source
# (docs/DECISIONS.md N-6) and linked statically into this one library: the runtime core, the Module,
# tensor and data-loader extensions, XNNPACK (the model's int8 layers are inside its delegate) and
# only the portable kernels the model calls (tools/swipenet/ops.txt).
#
# Configured by tools/swipenet/build.sh, which passes ET_SRC, KC_SWIPENET_OPS, PYTHON_EXECUTABLE and,
# on the desktop, KC_JNI_INCLUDE; Android builds use the NDK's toolchain file and jni.h.
cmake_minimum_required(VERSION 3.24)
project(kcswipenet C CXX)

if(NOT ET_SRC OR NOT EXISTS "${ET_SRC}/CMakeLists.txt")
  message(FATAL_ERROR "Pass -DET_SRC=<executorch source directory>")
endif()
if(NOT KC_SWIPENET_OPS)
  message(FATAL_ERROR "Pass -DKC_SWIPENET_OPS=<comma-separated operator list>")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE)
  set(CMAKE_BUILD_TYPE Release)
endif()

# Only what the encoder needs; nothing is downloaded at build time (KleidiAI would be), no tests,
# tools, Python bindings or other backends.
set(EXECUTORCH_BUILD_XNNPACK ON CACHE BOOL "" FORCE)
set(EXECUTORCH_XNNPACK_ENABLE_KLEIDI OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_XNNPACK_ENABLE_WEIGHT_CACHE OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_MODULE ON CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_TENSOR ON CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_DATA_LOADER ON CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_FLAT_TENSOR ON CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_NAMED_DATA_MAP ON CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXECUTOR_RUNNER OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_EVALUE_UTIL OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_EXTENSION_RUNNER_UTIL OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_KERNELS_OPTIMIZED OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_KERNELS_QUANTIZED OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_PYBIND OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_DEVTOOLS OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_ENABLE_LOGGING OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_ENABLE_EVENT_TRACER OFF CACHE BOOL "" FORCE)
set(EXECUTORCH_OPTIMIZE_SIZE ON CACHE BOOL "" FORCE)
set(EXECUTORCH_SELECT_OPS_LIST "${KC_SWIPENET_OPS}" CACHE STRING "" FORCE)
set(XNNPACK_ENABLE_ARM_SME OFF CACHE BOOL "" FORCE)
set(XNNPACK_ENABLE_ARM_SME2 OFF CACHE BOOL "" FORCE)
set(XNNPACK_ENABLE_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(XNNPACK_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(XNNPACK_BUILD_BENCHMARKS OFF CACHE BOOL "" FORCE)
set(XNNPACK_ENABLE_SPARSE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory("${ET_SRC}" "${CMAKE_BINARY_DIR}/executorch" EXCLUDE_FROM_ALL)
# EXECUTORCH_OPTIMIZE_SIZE turns exceptions off everywhere, but XNNPACK's weights cache uses
# try/catch; target options come after the global flags, so this wins (the source stays pristine).
target_compile_options(xnnpack_backend PRIVATE -fexceptions)

add_library(kcswipenet SHARED kc_swipenet_jni.cpp)
# Kernels and the backend register themselves from static constructors; ExecuTorch's targets
# already ask for their archives to be linked whole.
target_link_libraries(
  kcswipenet
  PRIVATE executorch
          executorch_selected_kernels
          xnnpack_backend
          extension_module_static
          extension_tensor
          extension_data_loader
          extension_threadpool)
if(KC_JNI_INCLUDE)
  target_include_directories(kcswipenet PRIVATE ${KC_JNI_INCLUDE})
endif()
if(ANDROID)
  target_link_libraries(kcswipenet PRIVATE log)
endif()
# Keep the exported surface to the JNI functions and drop what nothing references.
set_target_properties(kcswipenet PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_link_options(kcswipenet PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
if(ANDROID)
  # 16 KB pages (Android 15+ devices may use them).
  target_link_options(kcswipenet PRIVATE -Wl,-z,max-page-size=16384)
endif()
