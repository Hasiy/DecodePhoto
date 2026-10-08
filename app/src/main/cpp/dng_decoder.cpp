/*
 * Copyright 2026 Raw Photo Viewer contributors.
 */
#include <android/bitmap.h>
#include <android/imagedecoder.h>
#include <dlfcn.h>
#include <jni.h>
#include <unistd.h>

#include <cstdint>
#include <limits>
#include <string>

namespace {

constexpr std::uint64_t kMaxDecodedPixels = 160ULL * 1000ULL * 1000ULL;
constexpr int kMaxDimension = 32768;

thread_local std::string lastError;

struct ImageDecoderApi {
    using CreateFromFd = int (*)(int, AImageDecoder**);
    using DeleteDecoder = void (*)(AImageDecoder*);
    using GetHeaderInfo = const AImageDecoderHeaderInfo* (*)(AImageDecoder*);
    using GetDimension = int32_t (*)(const AImageDecoderHeaderInfo*);
    using SetBitmapFormat = int (*)(AImageDecoder*, int32_t);
    using SetTargetSize = int (*)(AImageDecoder*, int32_t, int32_t);
    using GetMinimumStride = size_t (*)(AImageDecoder*);
    using DecodeImage = int (*)(AImageDecoder*, void*, size_t, size_t);

    void* handle = nullptr;
    CreateFromFd createFromFd = nullptr;
    DeleteDecoder deleteDecoder = nullptr;
    GetHeaderInfo getHeaderInfo = nullptr;
    GetDimension getWidth = nullptr;
    GetDimension getHeight = nullptr;
    SetBitmapFormat setBitmapFormat = nullptr;
    SetTargetSize setTargetSize = nullptr;
    GetMinimumStride getMinimumStride = nullptr;
    DecodeImage decodeImage = nullptr;
    const char* (*resultToString)(int) = nullptr;
};

template <typename Function>
Function loadSymbol(void* handle, const char* name) {
    return reinterpret_cast<Function>(dlsym(handle, name));
}

ImageDecoderApi* getImageDecoderApi() {
    static ImageDecoderApi api = [] {
        ImageDecoderApi loaded;
        loaded.handle = dlopen("libjnigraphics.so", RTLD_NOW | RTLD_LOCAL);
        if (loaded.handle == nullptr) return loaded;

        loaded.createFromFd = loadSymbol<decltype(loaded.createFromFd)>(
            loaded.handle,
            "AImageDecoder_createFromFd"
        );
        loaded.deleteDecoder = loadSymbol<decltype(loaded.deleteDecoder)>(
            loaded.handle,
            "AImageDecoder_delete"
        );
        loaded.getHeaderInfo = loadSymbol<decltype(loaded.getHeaderInfo)>(
            loaded.handle,
            "AImageDecoder_getHeaderInfo"
        );
        loaded.getWidth = loadSymbol<decltype(loaded.getWidth)>(
            loaded.handle,
            "AImageDecoderHeaderInfo_getWidth"
        );
        loaded.getHeight = loadSymbol<decltype(loaded.getHeight)>(
            loaded.handle,
            "AImageDecoderHeaderInfo_getHeight"
        );
        loaded.setBitmapFormat = loadSymbol<decltype(loaded.setBitmapFormat)>(
            loaded.handle,
            "AImageDecoder_setAndroidBitmapFormat"
        );
        loaded.setTargetSize = loadSymbol<decltype(loaded.setTargetSize)>(
            loaded.handle,
            "AImageDecoder_setTargetSize"
        );
        loaded.getMinimumStride = loadSymbol<decltype(loaded.getMinimumStride)>(
            loaded.handle,
            "AImageDecoder_getMinimumStride"
        );
        loaded.decodeImage = loadSymbol<decltype(loaded.decodeImage)>(
            loaded.handle,
            "AImageDecoder_decodeImage"
        );
        loaded.resultToString = loadSymbol<decltype(loaded.resultToString)>(
            loaded.handle,
            "AImageDecoder_resultToString"
        );
        return loaded;
    }();

    const bool complete = api.handle != nullptr &&
        api.createFromFd != nullptr &&
        api.deleteDecoder != nullptr &&
        api.getHeaderInfo != nullptr &&
        api.getWidth != nullptr &&
        api.getHeight != nullptr &&
        api.setBitmapFormat != nullptr &&
        api.setTargetSize != nullptr &&
        api.getMinimumStride != nullptr &&
        api.decodeImage != nullptr;
    return complete ? &api : nullptr;
}

void setResultError(const ImageDecoderApi* api, const int result) {
    if (api != nullptr && api->resultToString != nullptr) {
        const char* description = api->resultToString(result);
        if (description != nullptr) {
            lastError = description;
            return;
        }
    }
    lastError = "Android image decoder error " + std::to_string(result);
}

class ScopedDecoder {
public:
    explicit ScopedDecoder(ImageDecoderApi* decoderApi) : api(decoderApi) {}

    ~ScopedDecoder() {
        if (decoder != nullptr) api->deleteDecoder(decoder);
        if (duplicatedFd >= 0) close(duplicatedFd);
    }

    int open(const int sourceFd) {
        duplicatedFd = dup(sourceFd);
        if (duplicatedFd < 0) {
            lastError = "Unable to duplicate DNG file descriptor";
            return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
        }
        if (lseek(duplicatedFd, 0, SEEK_SET) < 0) {
            lastError = "DNG input is not seekable";
            return ANDROID_IMAGE_DECODER_SEEK_ERROR;
        }
        const int result = api->createFromFd(duplicatedFd, &decoder);
        if (result != ANDROID_IMAGE_DECODER_SUCCESS) setResultError(api, result);
        return result;
    }

    AImageDecoder* decoder = nullptr;

private:
    ImageDecoderApi* api;
    int duplicatedFd = -1;
};

bool dimensionsAreSafe(const int width, const int height) {
    if (width <= 0 || height <= 0 || width > kMaxDimension || height > kMaxDimension) {
        return false;
    }
    return static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) <=
        kMaxDecodedPixels;
}

}  // namespace

extern "C"
JNIEXPORT jintArray JNICALL
Java_top_hasiy_rawviewer_raw_AndroidNativeImageDecoder_nativeReadSize(
    JNIEnv* env,
    jobject,
    jint fd
) {
    lastError.clear();
    ImageDecoderApi* api = getImageDecoderApi();
    if (api == nullptr) {
        lastError = "Android image decoder API is unavailable";
        return nullptr;
    }

    ScopedDecoder scoped(api);
    const int result = scoped.open(fd);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) return nullptr;

    const AImageDecoderHeaderInfo* header = api->getHeaderInfo(scoped.decoder);
    const int width = api->getWidth(header);
    const int height = api->getHeight(header);
    if (!dimensionsAreSafe(width, height)) {
        lastError = "DNG dimensions exceed the configured safety limit";
        return nullptr;
    }

    const jint dimensions[] = {width, height};
    jintArray output = env->NewIntArray(2);
    if (output == nullptr) {
        lastError = "Unable to allocate DNG dimensions";
        return nullptr;
    }
    env->SetIntArrayRegion(output, 0, 2, dimensions);
    return output;
}

extern "C"
JNIEXPORT jint JNICALL
Java_top_hasiy_rawviewer_raw_AndroidNativeImageDecoder_nativeDecode(
    JNIEnv* env,
    jobject,
    jint fd,
    jobject target
) {
    lastError.clear();
    ImageDecoderApi* api = getImageDecoderApi();
    if (api == nullptr) {
        lastError = "Android image decoder API is unavailable";
        return ANDROID_IMAGE_DECODER_INTERNAL_ERROR;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, target, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        lastError = "Unable to inspect target Bitmap";
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
        !dimensionsAreSafe(
            static_cast<int>(bitmapInfo.width),
            static_cast<int>(bitmapInfo.height)
        )) {
        lastError = "Invalid target Bitmap format or dimensions";
        return ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
    }

    ScopedDecoder scoped(api);
    int result = scoped.open(fd);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) return result;

    result = api->setBitmapFormat(scoped.decoder, ANDROID_BITMAP_FORMAT_RGBA_8888);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        setResultError(api, result);
        return result;
    }
    result = api->setTargetSize(
        scoped.decoder,
        static_cast<int32_t>(bitmapInfo.width),
        static_cast<int32_t>(bitmapInfo.height)
    );
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        setResultError(api, result);
        return result;
    }

    const size_t minimumStride = api->getMinimumStride(scoped.decoder);
    if (bitmapInfo.stride < minimumStride ||
        bitmapInfo.height > std::numeric_limits<size_t>::max() / bitmapInfo.stride) {
        lastError = "Target Bitmap stride is invalid";
        return ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, target, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        lastError = "Unable to lock target Bitmap";
        return ANDROID_IMAGE_DECODER_INTERNAL_ERROR;
    }
    result = api->decodeImage(
        scoped.decoder,
        pixels,
        bitmapInfo.stride,
        bitmapInfo.stride * bitmapInfo.height
    );
    AndroidBitmap_unlockPixels(env, target);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) setResultError(api, result);
    return result;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_top_hasiy_rawviewer_raw_AndroidNativeImageDecoder_nativeGetLastError(
    JNIEnv* env,
    jobject
) {
    return env->NewStringUTF(lastError.c_str());
}
