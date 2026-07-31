#pragma once

#include <android/log.h>

#define MR_LOG_TAG "MarathonDroid"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  MR_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  MR_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MR_LOG_TAG, __VA_ARGS__)
