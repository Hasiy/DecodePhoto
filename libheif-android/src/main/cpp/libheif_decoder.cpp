/* Copyright 2026 Raw Photo Viewer contributors. */
#include <android/bitmap.h>
#include <jni.h>
#include <libheif/heif.h>
#include <libheif/heif_items.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

namespace {
constexpr std::uint64_t kMaxDecodedPixels = 160ULL * 1000ULL * 1000ULL;
constexpr std::uint64_t kMaxRgba8888SourcePixels = 96ULL * 1000ULL * 1000ULL;
constexpr std::uint64_t kMaxRgbaF16SourcePixels = 64ULL * 1000ULL * 1000ULL;
constexpr std::uint64_t kMaxMemoryBlockBytes = 512ULL * 1024ULL * 1024ULL;
constexpr std::uint64_t kMaxTotalDecodeMemoryBytes = 768ULL * 1024ULL * 1024ULL;
constexpr int kMaxDimension = 32768;
constexpr int kInfoFieldCount = 19;
constexpr int kMaxInspectableItems = 1024;
constexpr std::size_t kMaxIccProfileBytes = 16U * 1024U * 1024U;
constexpr char kItemFieldSeparator = 0x1f;

using ContextPtr = std::unique_ptr<heif_context, decltype(&heif_context_free)>;
using HandlePtr = std::unique_ptr<heif_image_handle, decltype(&heif_image_handle_release)>;
using ImagePtr = std::unique_ptr<heif_image, decltype(&heif_image_release)>;
using OptionsPtr = std::unique_ptr<heif_decoding_options, decltype(&heif_decoding_options_free)>;
using NclxPtr = std::unique_ptr<heif_color_profile_nclx, decltype(&heif_nclx_color_profile_free)>;

void throwNative(JNIEnv* env, const std::string& message) {
    jclass type = env->FindClass("top/hasiy/libheif/LibHeifException");
    if (type != nullptr) env->ThrowNew(type, message.c_str());
}

bool safeDimensions(int width, int height) {
    return width > 0 && height > 0 && width <= kMaxDimension && height <= kMaxDimension &&
        static_cast<std::uint64_t>(width) * static_cast<std::uint64_t>(height) <= kMaxDecodedPixels;
}

void clampSecurityLimit(std::uint64_t& value, const std::uint64_t maximum) {
    if (value == 0U || value > maximum) value = maximum;
}

std::string errorText(const heif_error& error) {
    return error.message == nullptr ? "libheif error" : error.message;
}

int colorProfileTypeCode(heif_color_profile_type type) {
    switch (type) {
        case heif_color_profile_type_not_present: return 0;
        case heif_color_profile_type_nclx: return 1;
        case heif_color_profile_type_rICC: return 2;
        case heif_color_profile_type_prof: return 3;
        default: return 4;
    }
}

std::string fourccString(std::uint32_t value) {
    std::string result(4, ' ');
    result[0] = static_cast<char>((value >> 24U) & 0xffU);
    result[1] = static_cast<char>((value >> 16U) & 0xffU);
    result[2] = static_cast<char>((value >> 8U) & 0xffU);
    result[3] = static_cast<char>(value & 0xffU);
    return result;
}

std::string sanitizeItemField(const char* value) {
    std::string result = value == nullptr ? "" : value;
    std::replace(result.begin(), result.end(), kItemFieldSeparator, ' ');
    return result;
}

std::string encodeItem(
    char kind,
    heif_item_id id,
    const std::string& type,
    int width,
    int height,
    int bitDepth,
    std::int64_t sizeBytes,
    const std::string& contentType,
    bool isPrimary
) {
    const std::string separator(1, kItemFieldSeparator);
    return std::string(1, kind) + separator + std::to_string(id) + separator + type + separator +
        std::to_string(width) + separator + std::to_string(height) + separator +
        std::to_string(bitDepth) + separator + std::to_string(sizeBytes) + separator +
        contentType + separator + (isPrimary ? "1" : "0");
}

bool openPrimaryImage(
    JNIEnv* env,
    const jstring pathValue,
    ContextPtr& context,
    HandlePtr& handle
) {
    const char* path = env->GetStringUTFChars(pathValue, nullptr);
    if (path == nullptr) return false;
    context.reset(heif_context_alloc());
    if (!context) {
        env->ReleaseStringUTFChars(pathValue, path);
        throwNative(env, "Unable to allocate libheif context");
        return false;
    }
    heif_security_limits* limits = heif_context_get_security_limits(context.get());
    if (limits != nullptr) {
        clampSecurityLimit(limits->max_image_size_pixels, kMaxDecodedPixels);
        clampSecurityLimit(limits->max_memory_block_size, kMaxMemoryBlockBytes);
        clampSecurityLimit(limits->max_total_memory, kMaxTotalDecodeMemoryBytes);
    }
    heif_error error = heif_context_read_from_file(context.get(), path, nullptr);
    env->ReleaseStringUTFChars(pathValue, path);
    if (error.code != heif_error_Ok) {
        throwNative(env, errorText(error));
        return false;
    }

    heif_image_handle* rawHandle = nullptr;
    error = heif_context_get_primary_image_handle(context.get(), &rawHandle);
    handle.reset(rawHandle);
    if (error.code != heif_error_Ok || !handle) {
        throwNative(env, errorText(error));
        return false;
    }
    const int width = heif_image_handle_get_width(handle.get());
    const int height = heif_image_handle_get_height(handle.get());
    if (!safeDimensions(width, height)) {
        throwNative(env, "HEIF dimensions exceed the configured safety limit");
        return false;
    }
    return true;
}

std::uint16_t floatToHalf(const float value) {
    std::uint32_t bits = 0;
    std::memcpy(&bits, &value, sizeof(bits));
    const std::uint32_t sign = (bits >> 16U) & 0x8000U;
    const int exponent = static_cast<int>((bits >> 23U) & 0xffU) - 127 + 15;
    std::uint32_t mantissa = bits & 0x7fffffU;
    if (exponent <= 0) {
        if (exponent < -10) return static_cast<std::uint16_t>(sign);
        mantissa = (mantissa | 0x800000U) >> static_cast<unsigned int>(1 - exponent);
        return static_cast<std::uint16_t>(sign | ((mantissa + 0x1000U) >> 13U));
    }
    if (exponent >= 31) return static_cast<std::uint16_t>(sign | 0x7c00U);
    return static_cast<std::uint16_t>(
        sign | (static_cast<std::uint32_t>(exponent) << 10U) |
            ((mantissa + 0x1000U) >> 13U)
    );
}

bool copyRgbaF16(
    const heif_image* image,
    const AndroidBitmapInfo& bitmapInfo,
    void* targetPixels
) {
    int sourceStride = 0;
    const std::uint8_t* sourceBytes = heif_image_get_plane_readonly(
        image,
        heif_channel_interleaved,
        &sourceStride
    );
    const int bitDepth = heif_image_get_bits_per_pixel_range(
        image,
        heif_channel_interleaved
    );
    if (sourceBytes == nullptr || bitDepth <= 8 || bitDepth > 16) return false;
    const float maximum = static_cast<float>((1U << static_cast<unsigned int>(bitDepth)) - 1U);
    const std::uint32_t integerMaximum =
        (1U << static_cast<unsigned int>(bitDepth)) - 1U;
    const bool premultiplyAlpha = heif_image_is_premultiplied_alpha(
        const_cast<heif_image*>(image)
    ) == 0;
    for (std::uint32_t row = 0; row < bitmapInfo.height; ++row) {
        const auto* source = reinterpret_cast<const std::uint16_t*>(
            sourceBytes + static_cast<std::size_t>(row) * sourceStride
        );
        auto* target = reinterpret_cast<std::uint16_t*>(
            static_cast<std::uint8_t*>(targetPixels) +
                static_cast<std::size_t>(row) * bitmapInfo.stride
        );
        for (std::uint32_t column = 0; column < bitmapInfo.width; ++column) {
            const std::size_t component = static_cast<std::size_t>(column) * 4U;
            const std::uint32_t alpha = source[component + 3U];
            for (std::size_t color = 0; color < 3U; ++color) {
                std::uint32_t value = source[component + color];
                if (premultiplyAlpha) {
                    value = static_cast<std::uint32_t>(
                        (static_cast<std::uint64_t>(value) * alpha + integerMaximum / 2U) /
                            integerMaximum
                    );
                }
                target[component + color] = floatToHalf(static_cast<float>(value) / maximum);
            }
            target[component + 3U] = floatToHalf(static_cast<float>(alpha) / maximum);
        }
    }
    return true;
}

bool copyRgba8888(
    const heif_image* image,
    const AndroidBitmapInfo& bitmapInfo,
    void* targetPixels
) {
    int sourceStride = 0;
    const std::uint8_t* sourcePixels = heif_image_get_plane_readonly(
        image,
        heif_channel_interleaved,
        &sourceStride
    );
    if (sourcePixels == nullptr) return false;
    const bool premultiplyAlpha = heif_image_is_premultiplied_alpha(
        const_cast<heif_image*>(image)
    ) == 0;
    for (std::uint32_t row = 0; row < bitmapInfo.height; ++row) {
        const auto* source = sourcePixels + static_cast<std::size_t>(row) * sourceStride;
        auto* target = static_cast<std::uint8_t*>(targetPixels) +
            static_cast<std::size_t>(row) * bitmapInfo.stride;
        for (std::uint32_t column = 0; column < bitmapInfo.width; ++column) {
            const std::size_t component = static_cast<std::size_t>(column) * 4U;
            const std::uint32_t alpha = source[component + 3U];
            for (std::size_t color = 0; color < 3U; ++color) {
                const std::uint32_t value = source[component + color];
                target[component + color] = static_cast<std::uint8_t>(
                    premultiplyAlpha ? (value * alpha + 127U) / 255U : value
                );
            }
            target[component + 3U] = static_cast<std::uint8_t>(alpha);
        }
    }
    return true;
}
}  // namespace

extern "C" JNIEXPORT jintArray JNICALL
Java_top_hasiy_libheif_LibHeifNativeDecoder_nativeInspectFile(
    JNIEnv* env,
    jobject,
    jstring pathValue
) {
    ContextPtr context(nullptr, heif_context_free);
    HandlePtr handle(nullptr, heif_image_handle_release);
    if (!openPrimaryImage(env, pathValue, context, handle)) return nullptr;

    NclxPtr nclx(nullptr, heif_nclx_color_profile_free);
    heif_color_profile_nclx* rawNclx = nullptr;
    heif_image_handle_get_nclx_color_profile(
        handle.get(),
        &rawNclx
    );
    if (rawNclx != nullptr) nclx.reset(rawNclx);

    int bitDepth = heif_image_handle_get_luma_bits_per_pixel(handle.get());
    if (bitDepth <= 0) bitDepth = heif_image_handle_get_chroma_bits_per_pixel(handle.get());
    if (bitDepth <= 0) bitDepth = 8;
    const heif_color_profile_type profileType =
        heif_image_handle_get_color_profile_type(handle.get());
    heif_item_id primaryId = 0;
    const heif_error primaryIdError = heif_context_get_primary_image_ID(context.get(), &primaryId);
    if (primaryIdError.code != heif_error_Ok) primaryId = 0;
    heif_colorspace colorSpace = heif_colorspace_undefined;
    heif_chroma chroma = heif_chroma_undefined;
    const heif_error colorSpaceError = heif_image_handle_get_preferred_decoding_colorspace(
        handle.get(),
        &colorSpace,
        &chroma
    );
    if (colorSpaceError.code != heif_error_Ok) {
        colorSpace = heif_colorspace_undefined;
        chroma = heif_chroma_undefined;
    }
    heif_image_tiling tiling{};
    const heif_error tilingError = heif_image_handle_get_image_tiling(handle.get(), 1, &tiling);
    if (tilingError.code != heif_error_Ok) tiling = {};
    const std::size_t profileSize = heif_image_handle_get_raw_color_profile_size(handle.get());
    const jint values[kInfoFieldCount] = {
        heif_image_handle_get_width(handle.get()),
        heif_image_handle_get_height(handle.get()),
        bitDepth,
        nclx ? static_cast<jint>(nclx->color_primaries) : -1,
        nclx ? static_cast<jint>(nclx->transfer_characteristics) : -1,
        nclx ? static_cast<jint>(nclx->matrix_coefficients) : -1,
        nclx && nclx->full_range_flag ? 1 : 0,
        profileType == heif_color_profile_type_prof ||
            profileType == heif_color_profile_type_rICC ? 1 : 0,
        static_cast<jint>(primaryId),
        heif_context_get_number_of_top_level_images(context.get()),
        static_cast<jint>(colorSpace),
        static_cast<jint>(chroma),
        static_cast<jint>(tiling.num_columns),
        static_cast<jint>(tiling.num_rows),
        static_cast<jint>(tiling.tile_width),
        static_cast<jint>(tiling.tile_height),
        colorProfileTypeCode(profileType),
        profileSize > static_cast<std::size_t>(INT32_MAX) ? INT32_MAX :
            static_cast<jint>(profileSize),
        heif_image_handle_has_alpha_channel(handle.get()) ? 1 : 0,
    };
    jintArray result = env->NewIntArray(kInfoFieldCount);
    if (result != nullptr) env->SetIntArrayRegion(result, 0, kInfoFieldCount, values);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_top_hasiy_libheif_LibHeifNativeDecoder_nativeInspectItems(
    JNIEnv* env,
    jobject,
    jstring pathValue
) {
    ContextPtr context(nullptr, heif_context_free);
    HandlePtr primaryHandle(nullptr, heif_image_handle_release);
    if (!openPrimaryImage(env, pathValue, context, primaryHandle)) return nullptr;

    heif_item_id primaryId = heif_image_handle_get_item_id(primaryHandle.get());
    const int topLevelCount = std::clamp(
        heif_context_get_number_of_top_level_images(context.get()),
        0,
        kMaxInspectableItems
    );
    std::vector<heif_item_id> topLevelIds(static_cast<std::size_t>(topLevelCount));
    const int receivedTopLevel = heif_context_get_list_of_top_level_image_IDs(
        context.get(),
        topLevelIds.data(),
        topLevelCount
    );
    const int metadataCount = std::clamp(
        heif_image_handle_get_number_of_metadata_blocks(primaryHandle.get(), nullptr),
        0,
        kMaxInspectableItems - receivedTopLevel
    );
    std::vector<heif_item_id> metadataIds(static_cast<std::size_t>(metadataCount));
    const int receivedMetadata = heif_image_handle_get_list_of_metadata_block_IDs(
        primaryHandle.get(),
        nullptr,
        metadataIds.data(),
        metadataCount
    );

    std::vector<std::string> encodedItems;
    encodedItems.reserve(static_cast<std::size_t>(receivedTopLevel + receivedMetadata));

    for (int index = 0; index < receivedTopLevel; ++index) {
        heif_image_handle* rawHandle = nullptr;
        const heif_error handleError = heif_context_get_image_handle(
            context.get(),
            topLevelIds[static_cast<std::size_t>(index)],
            &rawHandle
        );
        HandlePtr itemHandle(rawHandle, heif_image_handle_release);
        if (handleError.code != heif_error_Ok || !itemHandle) continue;
        int itemBitDepth = heif_image_handle_get_luma_bits_per_pixel(itemHandle.get());
        if (itemBitDepth <= 0) {
            itemBitDepth = heif_image_handle_get_chroma_bits_per_pixel(itemHandle.get());
        }
        const heif_item_id id = topLevelIds[static_cast<std::size_t>(index)];
        encodedItems.push_back(encodeItem(
            'I',
            id,
            fourccString(heif_item_get_item_type(context.get(), id)),
            heif_image_handle_get_width(itemHandle.get()),
            heif_image_handle_get_height(itemHandle.get()),
            itemBitDepth,
            -1,
            "",
            id == primaryId
        ));
    }

    for (int index = 0; index < receivedMetadata; ++index) {
        const heif_item_id id = metadataIds[static_cast<std::size_t>(index)];
        const std::string contentType = sanitizeItemField(
            heif_image_handle_get_metadata_content_type(primaryHandle.get(), id)
        );
        encodedItems.push_back(encodeItem(
            'M',
            id,
            fourccString(heif_item_get_item_type(context.get(), id)),
            -1,
            -1,
            -1,
            static_cast<std::int64_t>(
                heif_image_handle_get_metadata_size(primaryHandle.get(), id)
            ),
            contentType,
            false
        ));
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(
        static_cast<jsize>(encodedItems.size()),
        stringClass,
        nullptr
    );
    if (result == nullptr) return nullptr;
    for (std::size_t index = 0; index < encodedItems.size(); ++index) {
        jstring value = env->NewStringUTF(encodedItems[index].c_str());
        if (value == nullptr) return nullptr;
        env->SetObjectArrayElement(result, static_cast<jsize>(index), value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_top_hasiy_libheif_LibHeifNativeDecoder_nativeReadIccProfile(
    JNIEnv* env,
    jobject,
    jstring pathValue
) {
    ContextPtr context(nullptr, heif_context_free);
    HandlePtr handle(nullptr, heif_image_handle_release);
    if (!openPrimaryImage(env, pathValue, context, handle)) return nullptr;
    const std::size_t profileSize = heif_image_handle_get_raw_color_profile_size(handle.get());
    if (profileSize == 0 || profileSize > kMaxIccProfileBytes) return nullptr;
    std::vector<std::uint8_t> profile(profileSize);
    const heif_error error = heif_image_handle_get_raw_color_profile(
        handle.get(),
        profile.data()
    );
    if (error.code != heif_error_Ok) return nullptr;
    jbyteArray result = env->NewByteArray(static_cast<jsize>(profileSize));
    if (result != nullptr) {
        env->SetByteArrayRegion(
            result,
            0,
            static_cast<jsize>(profileSize),
            reinterpret_cast<const jbyte*>(profile.data())
        );
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_top_hasiy_libheif_LibHeifNativeDecoder_nativeDecodeFile(
    JNIEnv* env,
    jobject,
    jstring pathValue,
    jobject targetBitmap,
    jboolean preserveHdrColorSpace
) {
    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, targetBitmap, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
        !safeDimensions(static_cast<int>(bitmapInfo.width), static_cast<int>(bitmapInfo.height))) {
        throwNative(env, "Unable to inspect target Bitmap");
        return JNI_FALSE;
    }
    const bool isF16 = bitmapInfo.format == ANDROID_BITMAP_FORMAT_RGBA_F16;
    if (!isF16 && bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        throwNative(env, "Unsupported target Bitmap format");
        return JNI_FALSE;
    }

    ContextPtr context(nullptr, heif_context_free);
    HandlePtr handle(nullptr, heif_image_handle_release);
    if (!openPrimaryImage(env, pathValue, context, handle)) return JNI_FALSE;
    const std::uint64_t sourcePixels =
        static_cast<std::uint64_t>(heif_image_handle_get_width(handle.get())) *
        static_cast<std::uint64_t>(heif_image_handle_get_height(handle.get()));
    const std::uint64_t sourcePixelLimit =
        isF16 ? kMaxRgbaF16SourcePixels : kMaxRgba8888SourcePixels;
    if (sourcePixels > sourcePixelLimit) {
        throwNative(env, "HEIF source image exceeds the mobile decode memory budget");
        return JNI_FALSE;
    }

    OptionsPtr options(heif_decoding_options_alloc(), heif_decoding_options_free);
    if (!options) {
        throwNative(env, "Unable to allocate libheif decoding options");
        return JNI_FALSE;
    }
    options->output_image_nclx_profile_passthrough =
        preserveHdrColorSpace == JNI_TRUE ? 1 : 0;
    heif_image* rawImage = nullptr;
    heif_error error = heif_decode_image(
        handle.get(),
        &rawImage,
        heif_colorspace_RGB,
        isF16 ? heif_chroma_interleaved_RRGGBBAA_LE : heif_chroma_interleaved_RGBA,
        options.get()
    );
    ImagePtr image(rawImage, heif_image_release);
    if (error.code != heif_error_Ok || !image) {
        throwNative(env, errorText(error));
        return JNI_FALSE;
    }

    if (bitmapInfo.width != static_cast<std::uint32_t>(heif_image_handle_get_width(handle.get())) ||
        bitmapInfo.height != static_cast<std::uint32_t>(heif_image_handle_get_height(handle.get()))) {
        heif_image* rawScaled = nullptr;
        error = heif_image_scale_image(
            image.get(),
            &rawScaled,
            static_cast<int>(bitmapInfo.width),
            static_cast<int>(bitmapInfo.height),
            nullptr
        );
        if (error.code != heif_error_Ok || rawScaled == nullptr) {
            throwNative(env, errorText(error));
            return JNI_FALSE;
        }
        // libheif scaling creates a new image without copying the alpha-mode flag.
        heif_image_set_premultiplied_alpha(
            rawScaled,
            heif_image_is_premultiplied_alpha(image.get())
        );
        image.reset(rawScaled);
    }

    void* targetPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, targetBitmap, &targetPixels) !=
        ANDROID_BITMAP_RESULT_SUCCESS) {
        throwNative(env, "Unable to access target Bitmap");
        return JNI_FALSE;
    }
    const bool copied = isF16
        ? copyRgbaF16(image.get(), bitmapInfo, targetPixels)
        : copyRgba8888(image.get(), bitmapInfo, targetPixels);
    AndroidBitmap_unlockPixels(env, targetBitmap);
    if (!copied) {
        throwNative(env, "libheif returned an incompatible RGBA plane");
        return JNI_FALSE;
    }
    return JNI_TRUE;
}
