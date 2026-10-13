/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
#include <android/bitmap.h>
#include <jni.h>
#include <libraw/libraw.h>

#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <algorithm>
#include <cmath>

namespace {

constexpr std::uint64_t kMaxDecodedPixels = 160ULL * 1000ULL * 1000ULL;
constexpr int kMaxDimension = 32768;
constexpr unsigned int kMaxRawMemoryMb = 512U;

std::mutex requestMutex;
std::unordered_map<jlong, std::shared_ptr<std::atomic_bool>> requestFlags;

void throwRawNativeException(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass(
        "top/hasiy/libraw/LibRawException"
    );
    if (exceptionClass != nullptr) env->ThrowNew(exceptionClass, message);
}

int progressCallback(void* data, LibRaw_progress, int, int) {
    const auto* cancelled = static_cast<std::atomic_bool*>(data);
    return cancelled->load(std::memory_order_relaxed) ? 1 : 0;
}

class RequestScope {
public:
    explicit RequestScope(const jlong id) : requestId(id) {
        std::lock_guard<std::mutex> lock(requestMutex);
        const auto iterator = requestFlags.find(requestId);
        if (iterator != requestFlags.end()) {
            flag = iterator->second;
        } else {
            flag = std::make_shared<std::atomic_bool>(false);
            requestFlags.emplace(requestId, flag);
        }
    }

    ~RequestScope() {
        std::lock_guard<std::mutex> lock(requestMutex);
        requestFlags.erase(requestId);
    }

    bool isCancelled() const {
        return flag->load(std::memory_order_relaxed);
    }

    std::shared_ptr<std::atomic_bool> flag;

private:
    jlong requestId;
};

class ProcessedImage {
public:
    explicit ProcessedImage(libraw_processed_image_t* value) : image(value) {}
    ~ProcessedImage() {
        if (image != nullptr) LibRaw::dcraw_clear_mem(image);
    }
    libraw_processed_image_t* image;
};

jobject createArgb8888Bitmap(JNIEnv* env, const int width, const int height) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmapClass == nullptr || configClass == nullptr) return nullptr;

    jfieldID argbField = env->GetStaticFieldID(
        configClass,
        "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;"
    );
    jmethodID createMethod = env->GetStaticMethodID(
        bitmapClass,
        "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;"
    );
    if (argbField == nullptr || createMethod == nullptr) return nullptr;

    jobject config = env->GetStaticObjectField(configClass, argbField);
    return env->CallStaticObjectMethod(
        bitmapClass,
        createMethod,
        width,
        height,
        config
    );
}

bool dimensionsAreSafe(const int width, const int height) {
    if (width <= 0 || height <= 0 || width > kMaxDimension || height > kMaxDimension) {
        return false;
    }
    return static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) <=
        kMaxDecodedPixels;
}

bool knownDimensionsAreSafe(const int width, const int height) {
    return (width <= 0 || height <= 0) || dimensionsAreSafe(width, height);
}

bool checkLibRawResult(JNIEnv* env, const int result) {
    if (result == LIBRAW_SUCCESS) return true;
    throwRawNativeException(env, libraw_strerror(result));
    return false;
}

}  // namespace

extern "C"
JNIEXPORT void JNICALL
Java_top_hasiy_libraw_LibRawNativeDecoder_nativePrepareRequest(
    JNIEnv*,
    jobject,
    jlong requestId
) {
    std::lock_guard<std::mutex> lock(requestMutex);
    requestFlags[requestId] = std::make_shared<std::atomic_bool>(false);
}

extern "C"
JNIEXPORT void JNICALL
Java_top_hasiy_libraw_LibRawNativeDecoder_nativeCancel(
    JNIEnv*,
    jobject,
    jlong requestId
) {
    std::lock_guard<std::mutex> lock(requestMutex);
    const auto iterator = requestFlags.find(requestId);
    if (iterator != requestFlags.end()) {
        iterator->second->store(true, std::memory_order_relaxed);
    }
}

extern "C"
JNIEXPORT jobject JNICALL
Java_top_hasiy_libraw_LibRawNativeDecoder_nativeDecodeFile(
    JNIEnv* env,
    jobject,
    jstring path,
    jboolean halfSize,
    jlong requestId,
    jint maxLongEdge,
    jfloat exposureEv,
    jint whiteBalance
) {
    RequestScope request(requestId);
    if (maxLongEdge <= 0 || !std::isfinite(exposureEv) || exposureEv < -3 || exposureEv > 3 ||
        whiteBalance < 0 || whiteBalance > 2) {
        throwRawNativeException(env, "Invalid RAW render options");
        return nullptr;
    }
    if (request.isCancelled()) {
        throwRawNativeException(env, "RAW decoding was cancelled");
        return nullptr;
    }

    const char* nativePath = env->GetStringUTFChars(path, nullptr);
    if (nativePath == nullptr) return nullptr;

    LibRaw processor;
    processor.set_progress_handler(progressCallback, request.flag.get());
    processor.imgdata.params.use_camera_wb = whiteBalance == 0 ? 1 : 0;
    processor.imgdata.params.use_auto_wb = whiteBalance == 1 ? 1 : 0;
    processor.imgdata.params.bright = std::pow(2.0f, exposureEv);
    processor.imgdata.params.output_color = 1;
    processor.imgdata.params.output_bps = 8;
    processor.imgdata.params.half_size = halfSize == JNI_TRUE ? 1 : 0;
    processor.imgdata.rawparams.max_raw_memory_mb = kMaxRawMemoryMb;

    int result = processor.open_file(nativePath);
    env->ReleaseStringUTFChars(path, nativePath);
    if (!checkLibRawResult(env, result)) return nullptr;

    const auto& openedSizes = processor.imgdata.sizes;
    if (!knownDimensionsAreSafe(openedSizes.raw_width, openedSizes.raw_height) ||
        !knownDimensionsAreSafe(openedSizes.width, openedSizes.height) ||
        !knownDimensionsAreSafe(openedSizes.iwidth, openedSizes.iheight)) {
        throwRawNativeException(env, "RAW dimensions exceed the configured safety limit");
        return nullptr;
    }

    result = processor.unpack();
    if (!checkLibRawResult(env, result)) return nullptr;

    const int sourceWidth = processor.imgdata.sizes.iwidth;
    const int sourceHeight = processor.imgdata.sizes.iheight;
    if (!dimensionsAreSafe(sourceWidth, sourceHeight)) {
        throwRawNativeException(env, "RAW dimensions exceed the configured safety limit");
        return nullptr;
    }

    result = processor.dcraw_process();
    if (!checkLibRawResult(env, result)) return nullptr;
    if (request.isCancelled()) {
        throwRawNativeException(env, "RAW decoding was cancelled");
        return nullptr;
    }

    int imageError = LIBRAW_SUCCESS;
    ProcessedImage processed(processor.dcraw_make_mem_image(&imageError));
    if (processed.image == nullptr || imageError != LIBRAW_SUCCESS) {
        throwRawNativeException(env, libraw_strerror(imageError));
        return nullptr;
    }
    if (processed.image->type != LIBRAW_IMAGE_BITMAP ||
        processed.image->bits != 8 ||
        processed.image->colors < 3 ||
        !dimensionsAreSafe(processed.image->width, processed.image->height)) {
        throwRawNativeException(env, "Unexpected LibRaw output format or dimensions");
        return nullptr;
    }

    const std::uint64_t sourceStride =
        static_cast<std::uint64_t>(processed.image->width) * processed.image->colors;
    const std::uint64_t requiredBytes =
        sourceStride * static_cast<std::uint64_t>(processed.image->height);
    if (requiredBytes > processed.image->data_size ||
        requiredBytes > std::numeric_limits<unsigned int>::max()) {
        throwRawNativeException(env, "LibRaw output buffer is inconsistent");
        return nullptr;
    }

    const double scale = std::min(1.0, static_cast<double>(maxLongEdge) /
        std::max(processed.image->width, processed.image->height));
    const int targetWidth = std::max(1, static_cast<int>(processed.image->width * scale));
    const int targetHeight = std::max(1, static_cast<int>(processed.image->height * scale));
    jobject bitmap = createArgb8888Bitmap(
        env,
        targetWidth,
        targetHeight
    );
    if (bitmap == nullptr || env->ExceptionCheck()) return nullptr;

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwRawNativeException(env, "Unable to access target Bitmap");
        return nullptr;
    }

    void* pixelBuffer = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixelBuffer) !=
        ANDROID_BITMAP_RESULT_SUCCESS) {
        throwRawNativeException(env, "Unable to lock target Bitmap");
        return nullptr;
    }

    auto* destination = static_cast<std::uint8_t*>(pixelBuffer);
    for (int y = 0; y < targetHeight; ++y) {
        if (request.isCancelled()) {
            AndroidBitmap_unlockPixels(env, bitmap);
            throwRawNativeException(env, "RAW decoding was cancelled");
            return nullptr;
        }
        std::uint8_t* targetRow = destination + y * bitmapInfo.stride;
        const int y0 = y * processed.image->height / targetHeight;
        const int y1 = std::max(y0 + 1, (y + 1) * processed.image->height / targetHeight);
        for (int x = 0; x < targetWidth; ++x) {
            const int x0 = x * processed.image->width / targetWidth;
            const int x1 = std::max(x0 + 1, (x + 1) * processed.image->width / targetWidth);
            std::uint64_t sums[3] = {0, 0, 0};
            for (int sy = y0; sy < y1; ++sy) {
                const auto* row = processed.image->data + static_cast<size_t>(sy) * sourceStride;
                for (int sx = x0; sx < x1; ++sx) {
                    for (int c = 0; c < 3; ++c) sums[c] += row[sx * processed.image->colors + c];
                }
            }
            const auto count = static_cast<std::uint64_t>((y1 - y0) * (x1 - x0));
            std::uint8_t* targetPixel = targetRow + x * 4U;
            for (int c = 0; c < 3; ++c) targetPixel[c] = static_cast<std::uint8_t>(sums[c] / count);
            targetPixel[3] = 255;
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}
