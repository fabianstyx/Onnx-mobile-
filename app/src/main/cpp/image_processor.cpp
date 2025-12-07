#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdint>
#include <cmath>
#include <algorithm>

#define LOG_TAG "ImageProcessor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifdef USE_NEON
#include <arm_neon.h>
#endif

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_preprocessNHWC(
        JNIEnv *env,
        jclass clazz,
        jobject srcByteBuffer,
        jobject dstFloatBuffer,
        jint srcWidth,
        jint srcHeight,
        jint pixelStride,
        jint rowStride,
        jint dstWidth,
        jint dstHeight) {

    auto start = std::chrono::high_resolution_clock::now();

    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcByteBuffer));
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(dstFloatBuffer));

    if (src == nullptr || dst == nullptr) {
        LOGE("Null buffer in preprocessNHWC");
        return -1;
    }

    const float scaleX = static_cast<float>(srcWidth) / static_cast<float>(dstWidth);
    const float scaleY = static_cast<float>(srcHeight) / static_cast<float>(dstHeight);
    constexpr float normFactor = 1.0f / 255.0f;

    int outIdx = 0;
    for (int y = 0; y < dstHeight; y++) {
        const int srcY = static_cast<int>(static_cast<float>(y) * scaleY);
        const int rowOffset = srcY * rowStride;

        for (int x = 0; x < dstWidth; x++) {
            const int srcX = static_cast<int>(static_cast<float>(x) * scaleX);
            const int pixelOffset = rowOffset + srcX * pixelStride;

            dst[outIdx++] = static_cast<float>(src[pixelOffset]) * normFactor;
            dst[outIdx++] = static_cast<float>(src[pixelOffset + 1]) * normFactor;
            dst[outIdx++] = static_cast<float>(src[pixelOffset + 2]) * normFactor;
        }
    }

    auto end = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
}

JNIEXPORT jlong JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_preprocessNCHW(
        JNIEnv *env,
        jclass clazz,
        jobject srcByteBuffer,
        jobject dstFloatBuffer,
        jint srcWidth,
        jint srcHeight,
        jint pixelStride,
        jint rowStride,
        jint dstWidth,
        jint dstHeight) {

    auto start = std::chrono::high_resolution_clock::now();

    auto* src = static_cast<uint8_t*>(env->GetDirectBufferAddress(srcByteBuffer));
    auto* dst = static_cast<float*>(env->GetDirectBufferAddress(dstFloatBuffer));

    if (src == nullptr || dst == nullptr) {
        LOGE("Null buffer in preprocessNCHW");
        return -1;
    }

    const float scaleX = static_cast<float>(srcWidth) / static_cast<float>(dstWidth);
    const float scaleY = static_cast<float>(srcHeight) / static_cast<float>(dstHeight);
    constexpr float normFactor = 1.0f / 255.0f;

    const int planeSize = dstWidth * dstHeight;
    float* rPlane = dst;
    float* gPlane = dst + planeSize;
    float* bPlane = dst + planeSize * 2;

    for (int y = 0; y < dstHeight; y++) {
        const int srcY = static_cast<int>(static_cast<float>(y) * scaleY);
        const int rowOffset = srcY * rowStride;
        const int outRowOffset = y * dstWidth;

        for (int x = 0; x < dstWidth; x++) {
            const int srcX = static_cast<int>(static_cast<float>(x) * scaleX);
            const int pixelOffset = rowOffset + srcX * pixelStride;
            const int outIdx = outRowOffset + x;

            rPlane[outIdx] = static_cast<float>(src[pixelOffset]) * normFactor;
            gPlane[outIdx] = static_cast<float>(src[pixelOffset + 1]) * normFactor;
            bPlane[outIdx] = static_cast<float>(src[pixelOffset + 2]) * normFactor;
        }
    }

    auto end = std::chrono::high_resolution_clock::now();
    return std::chrono::duration_cast<std::chrono::microseconds>(end - start).count();
}

JNIEXPORT void JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_scaleCoordinates(
        JNIEnv *env,
        jclass clazz,
        jfloatArray keypoints,
        jint numKeypoints,
        jfloat srcWidth,
        jfloat srcHeight,
        jfloat dstWidth,
        jfloat dstHeight) {

    if (keypoints == nullptr || numKeypoints <= 0) return;

    float* kps = env->GetFloatArrayElements(keypoints, nullptr);
    if (kps == nullptr) return;

    const float scaleX = dstWidth / srcWidth;
    const float scaleY = dstHeight / srcHeight;

    for (int i = 0; i < numKeypoints; i++) {
        kps[i * 2] *= scaleX;
        kps[i * 2 + 1] *= scaleY;
    }

    env->ReleaseFloatArrayElements(keypoints, kps, 0);
}

JNIEXPORT jfloatArray JNICALL
Java_com_example_onnxsc_engine_aim_NativeProcessor_applyPrediction(
        JNIEnv *env,
        jclass clazz,
        jfloat currentX,
        jfloat currentY,
        jfloatArray historyX,
        jfloatArray historyY,
        jint historySize,
        jfloat predictionScale,
        jfloat maxVelocity,
        jfloat deltaTime) {

    jfloatArray result = env->NewFloatArray(2);
    if (result == nullptr) return nullptr;

    float* output = env->GetFloatArrayElements(result, nullptr);
    if (output == nullptr) return nullptr;

    if (historySize < 2) {
        output[0] = currentX;
        output[1] = currentY;
        env->ReleaseFloatArrayElements(result, output, 0);
        return result;
    }

    float* hx = env->GetFloatArrayElements(historyX, nullptr);
    float* hy = env->GetFloatArrayElements(historyY, nullptr);

    if (hx == nullptr || hy == nullptr) {
        output[0] = currentX;
        output[1] = currentY;
        env->ReleaseFloatArrayElements(result, output, 0);
        if (hx) env->ReleaseFloatArrayElements(historyX, hx, JNI_ABORT);
        if (hy) env->ReleaseFloatArrayElements(historyY, hy, JNI_ABORT);
        return result;
    }

    float velX = 0, velY = 0;
    int samples = std::min(historySize, 3);
    for (int i = 0; i < samples - 1; i++) {
        int idx = historySize - 1 - i;
        velX += (hx[idx] - hx[idx - 1]);
        velY += (hy[idx] - hy[idx - 1]);
    }
    velX /= static_cast<float>(samples - 1);
    velY /= static_cast<float>(samples - 1);

    velX = std::max(-maxVelocity, std::min(maxVelocity, velX));
    velY = std::max(-maxVelocity, std::min(maxVelocity, velY));

    const float predFrames = deltaTime * 60.0f;
    output[0] = currentX + velX * predFrames * predictionScale;
    output[1] = currentY + velY * predFrames * predictionScale;

    env->ReleaseFloatArrayElements(historyX, hx, JNI_ABORT);
    env->ReleaseFloatArrayElements(historyY, hy, JNI_ABORT);
    env->ReleaseFloatArrayElements(result, output, 0);

    return result;
}

}
