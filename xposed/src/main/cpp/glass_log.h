#pragma once

#include <android/log.h>

#define GLASS_TAG "GlassMic-Native"

#define LOGI(fmt, ...) __android_log_print(ANDROID_LOG_INFO,  GLASS_TAG, fmt, ##__VA_ARGS__)
#define LOGW(fmt, ...) __android_log_print(ANDROID_LOG_WARN,  GLASS_TAG, fmt, ##__VA_ARGS__)
#define LOGE(fmt, ...) __android_log_print(ANDROID_LOG_ERROR, GLASS_TAG, fmt, ##__VA_ARGS__)
#define LOGD(fmt, ...) __android_log_print(ANDROID_LOG_DEBUG, GLASS_TAG, fmt, ##__VA_ARGS__)
