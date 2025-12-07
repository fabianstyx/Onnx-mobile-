cmake_minimum_required(VERSION 3.22.1)

project("onnxsc_native")

# Enable NEON for ARM optimization
# arm64-v8a has NEON enabled by default, -mfpu=neon only valid for 32-bit ARM
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -mfpu=neon")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon")
    add_definitions(-DUSE_NEON)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    # arm64-v8a has NEON intrinsics enabled by default
    add_definitions(-DUSE_NEON)
endif()

# Optimization flags
set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -ffast-math")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O3 -ffast-math -std=c++17")

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    image_processor.cpp
)

find_library(log-lib log)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(${CMAKE_PROJECT_NAME}
    ${log-lib}
    ${jnigraphics-lib}
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
    ${CMAKE_SOURCE_DIR}
)
