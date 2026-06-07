cmake_minimum_required(VERSION 3.22.1)
project(glassmic_native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# shadowhook 通过 prefab 暴露
find_package(shadowhook REQUIRED CONFIG)

add_library(glassmic_native SHARED
    glass_jni.cpp
    glass_aaudio.cpp
)

target_include_directories(glassmic_native PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
)

target_link_libraries(glassmic_native
    PRIVATE
        shadowhook::shadowhook
        aaudio     # Android NDK 自带，运行时连到 /system/lib*/libaaudio.so
        log        # __android_log_print
)

# 隐藏所有非 JNI 符号，减少表项 + 防止外部干扰
target_link_options(glassmic_native PRIVATE
    "-Wl,--exclude-libs,ALL"
    "-Wl,--gc-sections"
)
