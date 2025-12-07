#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <algorithm>

#define LOG_TAG "OnnxNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef USE_NEON
#include <arm_neon.h>
#endif

extern "C" {

JNIEXPORT jfloatArray JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_preprocessRGBA(
        JNIEnv *env,
        jclass clazz,
        jobject byteBuffer,
        jint width,
        jint height,
        jint pixelStride,
        jint rowStride,
        jint targetSize,
        jboolean normalize) {

    auto* srcBuffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(byteBuffer));
    if (srcBuffer == nullptr) {
        LOGE("Failed to get ByteBuffer address");
        return nullptr;
    }

    const int outputSize = targetSize * targetSize * 3;
    jfloatArray result = env->NewFloatArray(outputSize);
    if (result == nullptr) {
        LOGE("Failed to allocate float array");
        return nullptr;
    }

    auto* output = env->GetFloatArrayElements(result, nullptr);
    if (output == nullptr) {
        LOGE("Failed to get float array elements");
        return nullptr;
    }

    const float scaleX = static_cast<float>(width) / static_cast<float>(targetSize);
    const float scaleY = static_cast<float>(height) / static_cast<float>(targetSize);
    const float normFactor = normalize ? 1.0f / 255.0f : 1.0f;

    int outIdx = 0;
    for (int y = 0; y < targetSize; y++) {
        const int srcY = static_cast<int>(static_cast<float>(y) * scaleY);
        const int rowOffset = srcY * rowStride;

        for (int x = 0; x < targetSize; x++) {
            const int srcX = static_cast<int>(static_cast<float>(x) * scaleX);
            const int pixelOffset = rowOffset + srcX * pixelStride;

            const uint8_t r = srcBuffer[pixelOffset];
            const uint8_t g = srcBuffer[pixelOffset + 1];
            const uint8_t b = srcBuffer[pixelOffset + 2];

            output[outIdx++] = static_cast<float>(r) * normFactor;
            output[outIdx++] = static_cast<float>(g) * normFactor;
            output[outIdx++] = static_cast<float>(b) * normFactor;
        }
    }

    env->ReleaseFloatArrayElements(result, output, 0);
    return result;
}

JNIEXPORT jlong JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_preprocessRGBAToBuffer(
        JNIEnv *env,
        jclass clazz,
        jobject srcByteBuffer,
        jobject dstFloatBuffer,
        jint width,
        jint height,
        jint pixelStride,
        jint rowStride,
        jint targetSize,
        jboolean normalize) {

    auto start = std::chrono::high_resolution_clock::now();

    auto* srcBuffer = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcByteBuffer));
    auto* dstBuffer = static_cast<float*>(env->GetDirectBufferAddress(dstFloatBuffer));

    if (srcBuffer == nullptr || dstBuffer == nullptr) {
        LOGE("Failed to get buffer addresses");
        return -1;
    }

    const float scaleX = static_cast<float>(width) / static_cast<float>(targetSize);
    const float scaleY = static_cast<float>(height) / static_cast<float>(targetSize);
    const float normFactor = normalize ? 1.0f / 255.0f : 1.0f;

#ifdef USE_NEON
    const float32x4_t normVec = vdupq_n_f32(normFactor);
#endif

    int outIdx = 0;
    for (int y = 0; y < targetSize; y++) {
        const int srcY = static_cast<int>(static_cast<float>(y) * scaleY);
        const int rowOffset = srcY * rowStride;

#ifdef USE_NEON
        int x = 0;
        for (; x <= targetSize - 4; x += 4) {
            for (int dx = 0; dx < 4; dx++) {
                const int srcX = static_cast<int>(static_cast<float>(x + dx) * scaleX);
                const int pixelOffset = rowOffset + srcX * pixelStride;

                dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset]) * normFactor;
                dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset + 1]) * normFactor;
                dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset + 2]) * normFactor;
            }
        }
        for (; x < targetSize; x++) {
            const int srcX = static_cast<int>(static_cast<float>(x) * scaleX);
            const int pixelOffset = rowOffset + srcX * pixelStride;

            dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset]) * normFactor;
            dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset + 1]) * normFactor;
            dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset + 2]) * normFactor;
        }
#else
        for (int x = 0; x < targetSize; x++) {
            const int srcX = static_cast<int>(static_cast<float>(x) * scaleX);
            const int pixelOffset = rowOffset + srcX * pixelStride;

            dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset]) * normFactor;
            dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset + 1]) * normFactor;
            dstBuffer[outIdx++] = static_cast<float>(srcBuffer[pixelOffset + 2]) * normFactor;
        }
#endif
    }

    auto end = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
}

JNIEXPORT void JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_extractROI(
        JNIEnv *env,
        jclass clazz,
        jobject srcBuffer,
        jobject dstBuffer,
        jint srcWidth,
        jint srcHeight,
        jint pixelStride,
        jint rowStride,
        jint roiX,
        jint roiY,
        jint roiWidth,
        jint roiHeight) {

    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcBuffer));
    auto* dst = static_cast<uint8_t*>(env->GetDirectBufferAddress(dstBuffer));

    if (src == nullptr || dst == nullptr) {
        LOGE("Failed to get buffer addresses for ROI extraction");
        return;
    }

    const int clampedRoiX = std::max(0, std::min(roiX, srcWidth - 1));
    const int clampedRoiY = std::max(0, std::min(roiY, srcHeight - 1));
    const int clampedRoiW = std::min(roiWidth, srcWidth - clampedRoiX);
    const int clampedRoiH = std::min(roiHeight, srcHeight - clampedRoiY);

    int dstIdx = 0;
    for (int y = 0; y < clampedRoiH; y++) {
        const int srcRowOffset = (clampedRoiY + y) * rowStride + clampedRoiX * pixelStride;
        const int copyBytes = clampedRoiW * pixelStride;
        memcpy(dst + dstIdx, src + srcRowOffset, copyBytes);
        dstIdx += copyBytes;
    }
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_calculateDeltas(
        JNIEnv *env,
        jclass clazz,
        jfloat targetX,
        jfloat targetY,
        jfloat currentX,
        jfloat currentY,
        jfloat sensitivity,
        jfloat smoothing,
        jfloat deltaTime) {

    jfloatArray result = env->NewFloatArray(2);
    if (result == nullptr) return nullptr;

    float* output = env->GetFloatArrayElements(result, nullptr);
    if (output == nullptr) return nullptr;

    const float rawDeltaX = targetX - currentX;
    const float rawDeltaY = targetY - currentY;

    const float timeScale = deltaTime * 60.0f;
    const float smoothFactor = 1.0f - std::exp(-smoothing * timeScale);

    output[0] = rawDeltaX * sensitivity * smoothFactor;
    output[1] = rawDeltaY * sensitivity * smoothFactor;

    env->ReleaseFloatArrayElements(result, output, 0);
    return result;
}

}
