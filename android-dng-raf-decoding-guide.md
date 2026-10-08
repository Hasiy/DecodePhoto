# Android 手机端 DNG / RAF 解码与显影方案

> 文档版本：v1.0  
> 更新日期：2026-10-08  
> 适用平台：Android 8.0+；方案二要求 Android 11 / API 30+  
> 文档状态：技术方案与参考代码；三种解码路径均已集成到当前应用  
> 目标格式：Adobe DNG、Fujifilm RAF

---

## 1. 背景与术语

DNG 和 RAF 通常不是“加密照片”，而是相机传感器的 RAW 数据。Android UI 最终需要的是 RGB Bitmap，因此完整处理链路不是普通的文件解压，而是：

```text
RAW 文件
  ↓ 文件结构与元数据解析
传感器像素 / CFA 数据
  ↓ 黑电平、白平衡、坏点修复
线性 RAW 数据
  ↓ 去马赛克（Bayer / X-Trans）
线性 RGB
  ↓ 相机色彩矩阵、色彩空间转换
sRGB / Display P3
  ↓ Gamma、曝光、色调映射
可显示 Bitmap / JPEG / PNG
```

本文覆盖三种不同成本和画质等级的实现：

1. 使用 AndroidX `ExifInterface` 提取内嵌 JPEG 预览图。
2. 使用 Android NDK `AImageDecoder` 完整解码 DNG。
3. 使用 LibRaw + NDK/JNI 同时完整解码 DNG 和 RAF。

### 1.1 本文不解决的问题

- 不实现 Lightroom / Capture One 等级的降噪、镜头校正和局部色调编辑。
- 不保证任意相机新型号发布当天即可解码。
- 不把 EXIF 可读误认为 RAW 像素可完整显影。
- 不在主线程执行文件复制、RAW 解码或 Bitmap 缩放。

---

## 2. 三种方案对比

| 对比项 | 方案一：内嵌预览 | 方案二：系统 DNG 解码 | 方案三：LibRaw |
|:--|:--|:--|:--|
| DNG | 支持读取元数据及缩略图 | 支持完整解码 | 支持完整解码 |
| RAF | 支持读取元数据及缩略图 | 不支持 | 支持，取决于相机型号 |
| 最低系统 | 取决于 AndroidX；建议 API 26+ | API 30+ | 可做到 API 24+，本文建议 API 26+ |
| 接入复杂度 | 低 | 中 | 高 |
| 首屏速度 | 最快 | 中 | 中到慢 |
| 内存压力 | 低 | 中到高 | 高 |
| 输出画质 | 相机内嵌 JPEG 画质 | 系统 DNG 显影结果 | 基础显影，可自行调参 |
| 相机兼容性 | 文件有内嵌预览即可 | DNG | DNG、RAF 及更多 RAW |
| 适用场景 | 相册列表、文件选择器 | 仅 DNG 的查看器 | 同时支持 DNG / RAF 的正式产品 |

### 2.1 推荐结论

- **列表页**：始终优先方案一，避免同时完整解码多张 RAW。
- **仅支持 DNG 且最低版本为 API 30+**：详情页可使用方案二。
- **必须同时支持 DNG 和 RAF**：详情页统一使用方案三。
- **推荐生产组合**：方案一快速出图，后台再使用方案三替换为完整显影结果。

---

## 3. 公共设计

### 3.1 建议目录

```text
app/src/main/
├── java/com/example/openaiclient/raw/
│   ├── RawImageDecoder.kt
│   ├── EmbeddedPreviewDecoder.kt
│   ├── DngNativeDecoder.kt
│   ├── LibRawDecoder.kt
│   └── RawInputResolver.kt
├── cpp/
│   ├── CMakeLists.txt
│   ├── dng_decoder.cpp
│   └── libraw_decoder.cpp
└── AndroidManifest.xml
```

### 3.2 统一返回模型

上层不应该依赖某个具体 native 库的错误码。建议先定义统一结果：

```kotlin
package com.example.openaiclient.raw

import android.graphics.Bitmap

sealed interface RawDecodeResult {
    data class Success(
        val bitmap: Bitmap,
        val source: Source,
    ) : RawDecodeResult

    data class Failure(
        val reason: Reason,
        val cause: Throwable? = null,
    ) : RawDecodeResult

    enum class Source {
        EMBEDDED_PREVIEW,
        ANDROID_DNG_DECODER,
        LIBRAW,
    }

    enum class Reason {
        UNSUPPORTED_FORMAT,
        UNSUPPORTED_API_LEVEL,
        MISSING_EMBEDDED_PREVIEW,
        NON_SEEKABLE_INPUT,
        CORRUPTED_FILE,
        OUT_OF_MEMORY,
        CANCELLED,
        UNKNOWN,
    }
}
```

### 3.3 不要把 `content://` 当作文件路径

Storage Access Framework、系统相册以及云端文件提供者通常返回 `content://` URI。下面两种输入方式都要支持：

1. **可 seek 的文件描述符**：方案二可以直接使用。
2. **管道或不可 seek 的文件描述符**：先复制到应用缓存，再交给 native 解码器。

通用缓存复制工具：

```kotlin
package com.example.openaiclient.raw

import android.content.Context
import android.net.Uri
import java.io.File

object RawInputResolver {

    private const val COPY_BUFFER_SIZE_BYTES = 256 * 1024

    /**
     * 将 URI 内容复制到应用私有缓存，供需要真实、可 seek 路径的 native 解码器使用。
     *
     * @param context Application Context。
     * @param uri Storage Access Framework 或 MediaStore URI。
     * @param suffix 可信的文件后缀，例如 `.dng` 或 `.raf`。
     * @return 位于应用 cacheDir 的临时文件；调用方负责在使用完成后删除。
     */
    fun copyToCache(
        context: Context,
        uri: Uri,
        suffix: String,
    ): File {
        require(suffix in SUPPORTED_SUFFIXES) {
            "Unsupported RAW suffix: $suffix"
        }

        val target = File.createTempFile("raw_input_", suffix, context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open selected RAW file" }

                target.outputStream().buffered(COPY_BUFFER_SIZE_BYTES).use { output ->
                    input.copyTo(output, COPY_BUFFER_SIZE_BYTES)
                }
            }
            return target
        } catch (error: Exception) {
            if (!target.delete()) {
                // 正式项目应使用既有日志工具记录路径已脱敏的清理失败事件。
            }
            throw error
        }
    }

    private val SUPPORTED_SUFFIXES = setOf(".dng", ".raf")
}
```

注意：

- 不要把原始 URI、真实文件名、GPS 或用户目录写入日志。
- 缓存文件必须位于应用私有目录。
- 解码完成后删除临时 RAW；异常和取消路径也必须清理。
- 对云端 URI，复制过程本身可能触发下载，需要显示进度和取消按钮。

---

## 4. 方案一：提取 DNG / RAF 内嵌 JPEG 预览图

### 4.1 原理

很多相机会在 RAW 文件中附带 JPEG 缩略图或预览图，用于机内回放和文件浏览。提取这张 JPEG 不需要读取传感器像素，也不需要执行去马赛克，因此速度和内存占用远低于完整显影。

AndroidX `ExifInterface` 支持读取 DNG、RAF 等 RAW 文件的 EXIF 信息，但它的职责是元数据和缩略图读取，不是完整 RAW 显影。

### 4.2 依赖

```groovy
dependencies {
    implementation 'androidx.exifinterface:exifinterface:1.4.2'
}
```

项目的依赖仓库需要包含 Google Maven：

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
```

### 4.3 完整 Kotlin 示例

```kotlin
package com.example.openaiclient.raw

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class EmbeddedPreviewDecoder(
    private val applicationContext: Context,
) {

    /**
     * 读取 RAW 文件中的内嵌 JPEG，并按照 EXIF 方向进行旋转或镜像。
     *
     * @param uri DNG 或 RAF 的 content URI。
     * @return 文件没有内嵌缩略图时返回 null。
     */
    suspend fun decode(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        applicationContext.contentResolver
            .openFileDescriptor(uri, "r")
            ?.use { descriptor ->
                coroutineContext.ensureActive()

                val exif = ExifInterface(descriptor.fileDescriptor)
                val jpegBytes = exif.thumbnailBytes ?: return@use null
                coroutineContext.ensureActive()

                val decoded = BitmapFactory.decodeByteArray(
                    jpegBytes,
                    0,
                    jpegBytes.size,
                ) ?: return@use null

                applyExifOrientation(
                    source = decoded,
                    rotationDegrees = exif.rotationDegrees,
                    isFlipped = exif.isFlipped,
                )
            }
    }

    private fun applyExifOrientation(
        source: Bitmap,
        rotationDegrees: Int,
        isFlipped: Boolean,
    ): Bitmap {
        if (rotationDegrees == 0 && !isFlipped) return source

        val matrix = Matrix().apply {
            if (isFlipped) {
                postScale(-1f, 1f)
            }
            postRotate(rotationDegrees.toFloat())
        }

        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        ).also { oriented ->
            if (oriented !== source) {
                source.recycle()
            }
        }
    }
}
```

### 4.4 调用示例

```kotlin
viewModelScope.launch {
    val preview = embeddedPreviewDecoder.decode(selectedUri)
    if (preview == null) {
        // 转入方案二或方案三，不要把“无缩略图”当作损坏文件。
    } else {
        // 更新 UI State。
    }
}
```

### 4.5 优点

- 首屏通常只需要几十毫秒到几百毫秒。
- 不需要 NDK 或 JNI。
- DNG 和 RAF 可以走同一套 Kotlin 代码。
- 相机生成的预览通常已经带有白平衡、色调和胶片模拟效果。

### 4.6 限制与回退

- 文件可能没有缩略图。
- `thumbnailBytes` 可能只是很小的 EXIF Thumbnail，不是全尺寸 Preview。
- 预览图不一定反映 RAW 文件的全部动态范围。
- 相机预览可能已经应用富士胶片模拟，和自行显影结果不同。
- `BitmapFactory` 不应被当成完整 RAW 解码器。

建议回退顺序：

```text
ExifInterface.thumbnailBytes
  ├─ 成功 → 立即显示
  └─ null / 解码失败
       ├─ DNG + API 30+ → 方案二
       └─ DNG / RAF → 方案三
```

### 4.7 验收标准

- DNG 与 RAF 各至少覆盖 3 个相机型号。
- 横拍、竖拍、镜像方向显示正确。
- 没有缩略图时返回 `null`，不崩溃。
- 损坏 EXIF 不阻塞后续完整解码回退。
- 列表快速滚动时不重复解码同一 URI，结果进入内存或磁盘缓存。

---

## 5. 方案二：使用 Android NDK `AImageDecoder` 完整解码 DNG

### 5.1 原理与边界

Android NDK 从 API 30 开始提供 `AImageDecoder`。系统文档明确列出 Digital Negative（DNG）支持，解码器可以从可 seek 的文件描述符读取数据，并把结果写入调用方提供的 RGBA 缓冲区。

此方案：

- 适合 API 30+ 的 DNG 查看器。
- 不支持 Fujifilm RAF。
- 不需要在应用中打包 LibRaw。
- 实际兼容性仍需用目标设备和目标相机样本验证。

### 5.2 Gradle 配置

```groovy
android {
    defaultConfig {
        minSdk 26

        externalNativeBuild {
            cmake {
                cppFlags '-std=c++17 -Wall -Wextra -Werror'
            }
        }

        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
        }
    }

    externalNativeBuild {
        cmake {
            path file('src/main/cpp/CMakeLists.txt')
        }
    }
}
```

应用最低版本可以低于 30，但必须保证 API 30 以下的设备绝不加载或调用包含 `AImageDecoder` 符号的动态库。

### 5.3 CMake 配置

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(raw_dng_decoder LANGUAGES CXX)

add_library(
    raw_dng_decoder
    SHARED
    dng_decoder.cpp
)

find_library(android_library android)
find_library(jnigraphics_library jnigraphics)
find_library(log_library log)

target_link_libraries(
    raw_dng_decoder
    PRIVATE
    ${android_library}
    ${jnigraphics_library}
    ${log_library}
)
```

### 5.4 Kotlin 封装

```kotlin
package com.example.openaiclient.raw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

class DngNativeDecoder(
    private val applicationContext: Context,
) {

    init {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            "AImageDecoder DNG decoding requires API 30 or newer"
        }
        System.loadLibrary("raw_dng_decoder")
    }

    /**
     * 使用系统 NDK 解码 DNG。
     *
     * @param uri 指向 DNG 文件的 URI。
     * @param maxLongEdge 输出 Bitmap 最长边上限，必须大于 0。
     */
    suspend fun decode(
        uri: Uri,
        maxLongEdge: Int = DEFAULT_MAX_LONG_EDGE,
    ): Bitmap = withContext(Dispatchers.Default) {
        require(maxLongEdge > 0) { "maxLongEdge must be greater than 0" }

        applicationContext.contentResolver
            .openFileDescriptor(uri, "r")
            ?.use { descriptor ->
                val sourceSize = requireNotNull(nativeReadSize(descriptor.fd)) {
                    "Unable to read DNG dimensions"
                }
                require(sourceSize.size == 2) { "Invalid DNG dimensions" }

                val targetSize = fitInside(
                    width = sourceSize[0],
                    height = sourceSize[1],
                    maxLongEdge = maxLongEdge,
                )
                val bitmap = Bitmap.createBitmap(
                    targetSize.first,
                    targetSize.second,
                    Bitmap.Config.ARGB_8888,
                )

                val result = nativeDecode(
                    fd = descriptor.fd,
                    target = bitmap,
                )
                check(result == NATIVE_SUCCESS) {
                    "DNG decoding failed with result=$result"
                }
                bitmap
            } ?: error("Unable to open selected DNG")
    }

    private fun fitInside(
        width: Int,
        height: Int,
        maxLongEdge: Int,
    ): Pair<Int, Int> {
        require(width > 0 && height > 0) { "Invalid source dimensions" }
        val longEdge = maxOf(width, height)
        if (longEdge <= maxLongEdge) return width to height

        val scale = maxLongEdge.toDouble() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }

    private external fun nativeReadSize(fd: Int): IntArray?

    private external fun nativeDecode(
        fd: Int,
        target: Bitmap,
    ): Int

    private companion object {
        const val DEFAULT_MAX_LONG_EDGE = 4096
        const val NATIVE_SUCCESS = 0
    }
}
```

### 5.5 C++ / JNI 示例

```cpp
#include <android/bitmap.h>
#include <android/imagedecoder.h>
#include <jni.h>
#include <unistd.h>

namespace {

class ScopedDecoder {
public:
    ~ScopedDecoder() {
        if (decoder != nullptr) {
            AImageDecoder_delete(decoder);
        }
        if (duplicatedFd >= 0) {
            close(duplicatedFd);
        }
    }

    int open(const int sourceFd) {
        duplicatedFd = dup(sourceFd);
        if (duplicatedFd < 0) {
            return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
        }
        if (lseek(duplicatedFd, 0, SEEK_SET) < 0) {
            return ANDROID_IMAGE_DECODER_SEEK_ERROR;
        }
        return AImageDecoder_createFromFd(duplicatedFd, &decoder);
    }

    AImageDecoder* decoder = nullptr;

private:
    int duplicatedFd = -1;
};

}  // namespace

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_example_openaiclient_raw_DngNativeDecoder_nativeReadSize(
    JNIEnv* env,
    jobject,
    jint fd
) {
    ScopedDecoder scoped;
    const int result = scoped.open(fd);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        return nullptr;
    }

    const AImageDecoderHeaderInfo* header =
        AImageDecoder_getHeaderInfo(scoped.decoder);
    const jint dimensions[] = {
        AImageDecoderHeaderInfo_getWidth(header),
        AImageDecoderHeaderInfo_getHeight(header),
    };

    jintArray output = env->NewIntArray(2);
    if (output == nullptr) {
        return nullptr;
    }
    env->SetIntArrayRegion(output, 0, 2, dimensions);
    return output;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_example_openaiclient_raw_DngNativeDecoder_nativeDecode(
    JNIEnv* env,
    jobject,
    jint fd,
    jobject target
) {
    AndroidBitmapInfo bitmapInfo{};
    if (AndroidBitmap_getInfo(env, target, &bitmapInfo) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return ANDROID_IMAGE_DECODER_BAD_PARAMETER;
    }
    if (bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        return ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
    }

    ScopedDecoder scoped;
    int result = scoped.open(fd);
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        return result;
    }

    result = AImageDecoder_setAndroidBitmapFormat(
        scoped.decoder,
        ANDROID_BITMAP_FORMAT_RGBA_8888
    );
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        return result;
    }

    result = AImageDecoder_setTargetSize(
        scoped.decoder,
        static_cast<int32_t>(bitmapInfo.width),
        static_cast<int32_t>(bitmapInfo.height)
    );
    if (result != ANDROID_IMAGE_DECODER_SUCCESS) {
        return result;
    }

    const size_t minimumStride = AImageDecoder_getMinimumStride(scoped.decoder);
    if (bitmapInfo.stride < minimumStride) {
        return ANDROID_IMAGE_DECODER_INVALID_CONVERSION;
    }

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, target, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        return ANDROID_IMAGE_DECODER_INTERNAL_ERROR;
    }

    result = AImageDecoder_decodeImage(
        scoped.decoder,
        pixels,
        bitmapInfo.stride,
        bitmapInfo.stride * bitmapInfo.height
    );
    AndroidBitmap_unlockPixels(env, target);
    return result;
}
```

### 5.6 必须处理的边界条件

- `AImageDecoder_createFromFd()` 要求文件描述符可读取且可 seek。
- JNI 内部使用 `dup()`，避免改变 Java 层持有的文件描述符生命周期。
- 必须先删除 decoder，再关闭 duplicate fd。
- `AImageDecoder` 不是线程安全对象，一个实例只能在一个线程使用。
- API 30 以下不得加载这个 `.so`；否则可能出现动态链接符号缺失。
- 云端文件提供者返回管道时，先用 `RawInputResolver.copyToCache()` 落盘。
- `nativeReadSize()` 返回空数组或 `null` 时必须转换为明确错误，不要继续分配 Bitmap。
- 生产代码要把 native result 通过 `AImageDecoder_resultToString()` 转成脱敏日志。

### 5.7 优缺点

优点：

- 不需要额外打包大型 RAW 库。
- 系统负责 DNG 解析和色彩输出。
- 支持直接写入目标尺寸 Bitmap，降低峰值内存。

缺点：

- 无法处理 RAF。
- 受 Android 版本和设备系统实现约束。
- 对显影算法和参数的控制小于 LibRaw。
- 仍需 JNI，无法只靠 Kotlin 完成。

### 5.8 验收标准

- API 30、31、34、当前目标 API 的真机各至少验证一台。
- 支持无压缩与常见压缩 DNG 样本。
- 目标尺寸不超过 `maxLongEdge`。
- 不可 seek 输入能正确回退到缓存文件或方案三。
- API 26～29 打开应用不会因为 `.so` 提前加载而崩溃。

---

## 6. 方案三：LibRaw + Android NDK/JNI 完整解码 DNG 与 RAF

### 6.1 为什么选择 LibRaw

LibRaw 专门用于解析数码相机 RAW 文件，支持 DNG、RAF 以及多种其他 RAW 格式。对于“同一套代码同时处理 DNG 和 RAF”的需求，这是三种方案中覆盖最完整的选择。

建议固定经过验证的版本，不要直接跟踪 `master`。截至本文更新时可评估 `0.22.1`；升级时重新执行完整相机样本回归。该版本包含 DNG 内存尺寸溢出等安全修复，因此不建议长期停留在更旧版本。

### 6.2 许可证

LibRaw 提供 LGPL 2.1 与 CDDL 1.0 双许可证选择。正式发布前应由项目负责人确认：

- 最终选择的许可证模式。
- 动态链接或静态链接的合规方式。
- 是否修改 LibRaw 源码。
- 应用内开源声明、许可证文本和源代码提供义务。

本文只提供工程建议，不构成法律意见。

### 6.3 推荐集成方式

生产项目优先选择以下方式之一：

1. 将官方 LibRaw release 源码作为固定版本的 third-party source 构建。
2. 在 CI 中构建各 ABI 的 `libraw.a`，应用工程只链接受控的预编译静态库。

不建议直接依赖长期无人维护的随机 Android Wrapper。若采用社区 Wrapper，至少审查：

- 包含的 LibRaw 版本。
- 16 KB page size 支持。
- `arm64-v8a` 支持。
- JNI 内存释放和取消路径。
- AAR 是否包含不需要的 ABI。
- 上游许可证与安全补丁更新方式。

### 6.4 建议 third-party 目录

```text
app/src/main/cpp/
├── CMakeLists.txt
├── libraw_decoder.cpp
└── third_party/
    └── libraw/
        ├── include/
        └── prebuilt/
            ├── arm64-v8a/libraw.a
            ├── armeabi-v7a/libraw.a
            └── x86_64/libraw.a
```

仓库还应记录：

```text
third_party/libraw/VERSION
third_party/libraw/LICENSE.LGPL
third_party/libraw/LICENSE.CDDL
third_party/libraw/BUILDING-ANDROID.md
```

### 6.5 Gradle 配置

```groovy
android {
    defaultConfig {
        minSdk 26

        externalNativeBuild {
            cmake {
                cppFlags '-std=c++17 -O2 -Wall -Wextra'
                arguments '-DANDROID_STL=c++_shared'
            }
        }

        ndk {
            abiFilters 'arm64-v8a', 'armeabi-v7a', 'x86_64'
        }
    }

    externalNativeBuild {
        cmake {
            path file('src/main/cpp/CMakeLists.txt')
        }
    }
}
```

### 6.6 CMake 配置：链接预编译 LibRaw

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(libraw_android_bridge LANGUAGES CXX)

set(LIBRAW_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libraw)

add_library(libraw STATIC IMPORTED)
set_target_properties(
    libraw
    PROPERTIES
    IMPORTED_LOCATION
    ${LIBRAW_ROOT}/prebuilt/${ANDROID_ABI}/libraw.a
)

add_library(
    libraw_android_bridge
    SHARED
    libraw_decoder.cpp
)

target_include_directories(
    libraw_android_bridge
    PRIVATE
    ${LIBRAW_ROOT}/include
)

find_library(jnigraphics_library jnigraphics)
find_library(log_library log)
find_library(z_library z)

target_link_libraries(
    libraw_android_bridge
    PRIVATE
    libraw
    ${jnigraphics_library}
    ${log_library}
    ${z_library}
)
```

如果构建的 LibRaw 启用了 JPEG、LCMS 或 OpenMP，还需要链接对应依赖。CI 必须对每个 ABI 执行链接验证，不能只在开发机验证 `arm64-v8a`。

### 6.7 Kotlin 封装

```kotlin
package com.example.openaiclient.raw

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class LibRawDecoder(
    private val applicationContext: Context,
) {

    init {
        System.loadLibrary("libraw_android_bridge")
    }

    /**
     * 使用 LibRaw 将 DNG 或 RAF 显影为 ARGB_8888 Bitmap。
     *
     * @param uri RAW 文件 URI。
     * @param format 原始格式，用于确定缓存文件后缀。
     * @param halfSize 是否启用 LibRaw 半尺寸快速显影。
     */
    suspend fun decode(
        uri: Uri,
        format: RawFormat,
        halfSize: Boolean = true,
    ): Bitmap = withContext(Dispatchers.Default) {
        val cachedRaw = withContext(Dispatchers.IO) {
            RawInputResolver.copyToCache(
                context = applicationContext,
                uri = uri,
                suffix = format.suffix,
            )
        }

        try {
            coroutineContext.ensureActive()
            nativeDecodeFile(
                path = cachedRaw.absolutePath,
                halfSize = halfSize,
            ) ?: error("LibRaw returned no image")
        } finally {
            if (!cachedRaw.delete()) {
                // 正式项目使用既有日志工具记录脱敏后的清理失败事件。
            }
        }
    }

    private external fun nativeDecodeFile(
        path: String,
        halfSize: Boolean,
    ): Bitmap?
}

enum class RawFormat(val suffix: String) {
    DNG(".dng"),
    RAF(".raf"),
}
```

### 6.8 LibRaw C++ / JNI 核心示例

下面示例展示最小的 8-bit sRGB 显影与 Bitmap 拷贝。生产代码仍需补充结构化错误码、取消回调、尺寸上限和更完整的异常映射。

```cpp
#include <android/bitmap.h>
#include <jni.h>
#include <libraw/libraw.h>

#include <cstdint>

namespace {

void throwIllegalState(JNIEnv* env, const char* message) {
    jclass exceptionClass = env->FindClass("java/lang/IllegalStateException");
    if (exceptionClass != nullptr) {
        env->ThrowNew(exceptionClass, message);
    }
}

jobject createArgb8888Bitmap(
    JNIEnv* env,
    const int width,
    const int height
) {
    jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (bitmapClass == nullptr || configClass == nullptr) {
        return nullptr;
    }

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
    if (argbField == nullptr || createMethod == nullptr) {
        return nullptr;
    }

    jobject config = env->GetStaticObjectField(configClass, argbField);
    return env->CallStaticObjectMethod(
        bitmapClass,
        createMethod,
        width,
        height,
        config
    );
}

}  // namespace

extern "C"
JNIEXPORT jobject JNICALL
Java_com_example_openaiclient_raw_LibRawDecoder_nativeDecodeFile(
    JNIEnv* env,
    jobject,
    jstring path,
    jboolean halfSize
) {
    const char* nativePath = env->GetStringUTFChars(path, nullptr);
    if (nativePath == nullptr) {
        return nullptr;
    }

    LibRaw processor;
    processor.imgdata.params.use_camera_wb = 1;
    processor.imgdata.params.output_color = 1;  // sRGB
    processor.imgdata.params.output_bps = 8;
    processor.imgdata.params.half_size = halfSize == JNI_TRUE ? 1 : 0;

    int result = processor.open_file(nativePath);
    env->ReleaseStringUTFChars(path, nativePath);
    if (result != LIBRAW_SUCCESS) {
        throwIllegalState(env, libraw_strerror(result));
        return nullptr;
    }

    result = processor.unpack();
    if (result != LIBRAW_SUCCESS) {
        throwIllegalState(env, libraw_strerror(result));
        return nullptr;
    }

    result = processor.dcraw_process();
    if (result != LIBRAW_SUCCESS) {
        throwIllegalState(env, libraw_strerror(result));
        return nullptr;
    }

    int imageError = LIBRAW_SUCCESS;
    libraw_processed_image_t* image =
        processor.dcraw_make_mem_image(&imageError);
    if (image == nullptr || imageError != LIBRAW_SUCCESS) {
        if (image != nullptr) {
            LibRaw::dcraw_clear_mem(image);
        }
        throwIllegalState(env, libraw_strerror(imageError));
        return nullptr;
    }

    if (
        image->type != LIBRAW_IMAGE_BITMAP ||
        image->bits != 8 ||
        image->colors < 3
    ) {
        LibRaw::dcraw_clear_mem(image);
        throwIllegalState(env, "Unexpected LibRaw output format");
        return nullptr;
    }

    jobject bitmap = createArgb8888Bitmap(
        env,
        static_cast<int>(image->width),
        static_cast<int>(image->height)
    );
    if (bitmap == nullptr || env->ExceptionCheck()) {
        LibRaw::dcraw_clear_mem(image);
        return nullptr;
    }

    AndroidBitmapInfo bitmapInfo{};
    if (
        AndroidBitmap_getInfo(env, bitmap, &bitmapInfo) !=
            ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888
    ) {
        LibRaw::dcraw_clear_mem(image);
        throwIllegalState(env, "Unable to access target Bitmap");
        return nullptr;
    }

    void* pixelBuffer = nullptr;
    if (
        AndroidBitmap_lockPixels(env, bitmap, &pixelBuffer) !=
        ANDROID_BITMAP_RESULT_SUCCESS
    ) {
        LibRaw::dcraw_clear_mem(image);
        throwIllegalState(env, "Unable to lock target Bitmap");
        return nullptr;
    }

    const size_t sourceStride = image->width * image->colors;
    auto* destination = static_cast<std::uint8_t*>(pixelBuffer);

    for (unsigned int y = 0; y < image->height; ++y) {
        const std::uint8_t* sourceRow = image->data + y * sourceStride;
        std::uint8_t* targetRow = destination + y * bitmapInfo.stride;

        for (unsigned int x = 0; x < image->width; ++x) {
            const std::uint8_t* sourcePixel = sourceRow + x * image->colors;
            std::uint8_t* targetPixel = targetRow + x * 4;

            targetPixel[0] = sourcePixel[0];  // R
            targetPixel[1] = sourcePixel[1];  // G
            targetPixel[2] = sourcePixel[2];  // B
            targetPixel[3] = 255;             // A
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    LibRaw::dcraw_clear_mem(image);
    processor.recycle();
    return bitmap;
}
```

### 6.9 只使用 LibRaw 提取内嵌预览

如果 `ExifInterface` 只能取得小缩略图，还可以让 LibRaw 尝试提取相机内嵌预览：

```cpp
LibRaw processor;
int result = processor.open_file(path);
if (result == LIBRAW_SUCCESS) {
    result = processor.unpack_thumb();
}

int thumbnailError = LIBRAW_SUCCESS;
libraw_processed_image_t* thumbnail =
    processor.dcraw_make_mem_thumb(&thumbnailError);

if (thumbnail != nullptr) {
    // 多数相机会返回 LIBRAW_IMAGE_JPEG；交给 BitmapFactory 解码即可。
    LibRaw::dcraw_clear_mem(thumbnail);
}
```

`unpack_thumb()` 必须在 `dcraw_make_mem_thumb()` 之前调用。返回内容可能是 JPEG，也可能是 RGB Bitmap，不能无条件假设数据就是 JPEG。

### 6.10 建议参数

| 参数 | 预览推荐值 | 高质量导出 | 说明 |
|:--|:--|:--|:--|
| `use_camera_wb` | `1` | `1` 或用户设置 | 使用拍摄时白平衡 |
| `output_color` | `1` | `1` / ICC 流程 | `1` 为 sRGB |
| `output_bps` | `8` | `16` | 16-bit 不应直接塞进普通 ARGB_8888 |
| `half_size` | `1` | `0` | 预览降低分辨率和耗时 |
| `no_auto_bright` | 产品决定 | 产品决定 | 是否禁用自动提亮 |
| `user_qual` | 默认 | 样本评估后固定 | 去马赛克质量与速度权衡 |

富士 RAF 可能使用 X-Trans CFA。不要假设 Bayer 样本通过就代表 RAF 质量达标，应分别检查：

- 细小重复纹理。
- 斜线和文字边缘。
- 高 ISO 彩噪。
- 绿色植被与摩尔纹。
- 压缩 RAF 和未压缩 RAF。

### 6.11 取消任务

Kotlin 协程取消不会自动打断正在运行的 C++。生产实现应使用 LibRaw 进度回调或取消标记：

```text
ViewModel Job cancel
  ↓ 设置 native atomic cancel flag
LibRaw progress callback 检查 flag
  ↓
返回 LIBRAW_CANCELLED_BY_CALLBACK
  ↓
JNI 释放 image、processor、Bitmap 中间对象
```

每个解码任务使用独立 `LibRaw` 实例，不要跨线程共享同一实例。

### 6.12 优缺点

优点：

- DNG、RAF 使用同一解码入口。
- 支持范围远大于系统解码器。
- 可以控制白平衡、输出位深、色彩空间和去马赛克参数。
- 未来可扩展 CR2、CR3、NEF、ARW 等格式。

缺点：

- NDK 构建、ABI、许可证与安全更新成本高。
- 完整显影的 CPU 和内存压力显著。
- LibRaw 自带后处理适合基础转换，不等于专业 RAW 编辑器画质。
- 新相机或新压缩 RAF 仍可能需要升级库。

### 6.13 验收标准

- 目标富士机型逐一验证，不以扩展名作为唯一兼容性依据。
- DNG、压缩 RAF、未压缩 RAF 均有自动化或固定样本回归。
- Native Heap 峰值在产品设定范围内。
- 页面退出后任务可取消，缓存 RAW 和 native buffer 均被清理。
- 损坏文件不会导致 native crash。
- `arm64-v8a` 支持 16 KB memory page 设备。

---

## 7. 组合式生产流程

建议不要只选择一个方案，而是采用两阶段显示：

```text
用户选择 DNG / RAF
       ↓
读取 EXIF：尺寸、方向、相机型号、拍摄时间
       ↓
方案一提取内嵌 JPEG
  ├─ 成功：立即显示低成本预览
  └─ 失败：显示占位图
       ↓
后台完整解码
  ├─ DNG + API 30+ + 只要求系统显影 → 方案二
  └─ DNG / RAF 统一能力 → 方案三
       ↓
限制最长边、应用方向、生成最终 Bitmap
       ↓
原子替换 UI 中的预览图
```

### 7.1 推荐接口

```kotlin
package com.example.openaiclient.raw

import android.net.Uri
import kotlinx.coroutines.flow.Flow

interface RawImageDecoder {

    fun decode(
        uri: Uri,
        format: RawFormat,
    ): Flow<RawDecodeResult>
}
```

Flow 可以先发送内嵌预览，再发送完整解码结果：

```text
Success(source = EMBEDDED_PREVIEW)
Success(source = LIBRAW)
```

UI 必须使用 URI 或内容哈希作为稳定 key，防止列表复用时把上一张图片的异步结果显示到下一项。

---

## 8. 内存、线程与缓存

### 8.1 Bitmap 内存估算

`ARGB_8888` 每像素占 4 bytes：

| 分辨率 | 仅最终 Bitmap | 说明 |
|:--|--:|:--|
| 1920 × 1080 | 约 7.9 MiB | 普通屏幕预览 |
| 4096 × 2731 | 约 42.7 MiB | 约 11 MP |
| 6000 × 4000 | 约 91.6 MiB | 24 MP |
| 7728 × 5152 | 约 151.9 MiB | 约 40 MP |

这还没有计算：

- 压缩 RAW 输入。
- 解包后的 CFA 数据。
- 去马赛克中间缓冲区。
- LibRaw RGB 输出缓冲区。
- Java/Kotlin Bitmap。
- UI 缩放或旋转时产生的第二张 Bitmap。

完整 RAW 显影峰值达到最终 Bitmap 的 2～4 倍并不罕见。

### 8.2 推荐限制

- 列表预览最长边：`512～1024 px`。
- 详情预览最长边：`2048～4096 px`。
- 完整分辨率只在明确的“导出”操作中使用。
- 同时只运行一个完整 RAW 显影任务。
- `Dispatchers.IO` 负责 URI 复制；`Dispatchers.Default` 或专用单线程池负责 CPU 显影。
- 不要在 Compose 重组中直接启动解码。

### 8.3 缓存层级

```text
L1：内存缓存
  key = URI + lastModified + targetSize + decoderVersion

L2：磁盘预览缓存
  保存解码后的 JPEG/WebP，不保存重复 RAW

临时 RAW 缓存
  仅用于不可 seek URI，解码结束立即删除
```

LibRaw 升级、白平衡算法调整或输出色彩空间变化时，必须改变 `decoderVersion`，避免读取旧显影缓存。

---

## 9. 颜色与画质

### 9.1 sRGB 预览

普通 Android UI 最稳妥的目标是 8-bit sRGB `ARGB_8888`：

- Compose、Canvas 和普通 ImageView 直接兼容。
- 内存可控。
- 容易压缩成 JPEG/WebP 缓存。

### 9.2 高位深输出

如果需要后续编辑：

- LibRaw 使用 `output_bps = 16`。
- 不要立即压缩到 `ARGB_8888`。
- 使用 ShortArray/native buffer、16-bit TIFF 或其他明确支持高位深的中间格式。
- `RGBA_F16` 每像素 8 bytes，内存压力是 `ARGB_8888` 的两倍。

### 9.3 白平衡与相机风格

相机内嵌 JPEG 和 LibRaw 默认显影结果可能明显不同：

- 富士内嵌 JPEG 可能带胶片模拟。
- RAW 白平衡只是一组元数据，可被重新解释。
- 相机厂商的色彩矩阵、曲线、降噪和锐化不一定完全公开。

产品验收应先定义目标：

- “接近相机 JPEG”；或
- “中性的 RAW 基础显影”；或
- “为后续编辑保留最大动态范围”。

三个目标不能用同一组参数同时满足。

---

## 10. 安全与隐私

RAW 解码器直接处理外部二进制文件，应按不可信输入设计：

- 固定维护中的 LibRaw 版本，及时跟进整数溢出、越界和内存分配修复。
- 解码前限制输入文件大小，例如产品定义 `MAX_RAW_FILE_BYTES`。
- 解码前读取并验证尺寸，拒绝异常宽高和整数乘法溢出。
- 所有 native 内存分配都要检查返回值。
- 不把 GPS、文件路径、文件名、相机序列号写入日志。
- 分享或上传前按产品隐私策略移除 EXIF GPS。
- 缓存目录使用应用私有目录，不申请不必要的全盘存储权限。
- 对高风险场景可把 native 解码放到隔离进程，降低 native crash 对主进程的影响。

---

## 11. 错误处理

| 场景 | 对用户提示 | 程序行为 |
|:--|:--|:--|
| 没有内嵌预览 | 正在生成预览 | 自动进入完整解码 |
| RAF 交给系统 DNG 解码器 | 不支持此格式 | 改走 LibRaw |
| 相机型号尚不支持 | 暂不支持该相机 RAW | 保留文件，不反复重试 |
| 云端 URI 下载失败 | 无法读取云端照片 | 提供重试 |
| 输入不可 seek | 无需提示 | 自动复制到 cache |
| 文件损坏 | 照片文件不完整或已损坏 | 停止解码、清理资源 |
| 内存不足 | 照片尺寸过大，请使用较低预览质量 | 降级到 half-size 或内嵌预览 |
| 用户取消 | 无提示或轻提示 | 终止任务并清理缓存 |

Native 错误日志应记录：

- 解码器名称与版本。
- 脱敏后的格式类型。
- 错误码。
- 输入尺寸和目标尺寸。
- 是否启用 half-size。

不得记录：

- URI 全文。
- 真实文件路径。
- 用户文件名。
- GPS 或相机序列号。

---

## 12. 测试矩阵

### 12.1 文件样本

| 类别 | 至少覆盖 |
|:--|:--|
| DNG | Bayer、压缩、未压缩、手机 DNG、相机 DNG |
| RAF | Bayer RAF、X-Trans RAF、压缩、未压缩 |
| EXIF | 8 种 Orientation、含 GPS、不含缩略图 |
| 异常文件 | 截断、空文件、错误扩展名、超大尺寸声明 |
| URI | MediaStore、本地 SAF、云端 SAF、不可 seek pipe |

### 12.2 设备矩阵

- API 26～29：方案一和方案三；确认方案二动态库不加载。
- API 30～31：验证早期 `AImageDecoder`。
- 当前 targetSdk 对应系统：完整回归。
- 低内存设备：验证降级和 OOM 防护。
- 16 KB page size 的 arm64 设备：验证 native library 可加载。

### 12.3 自动化测试

纯 JVM 测试：

- 文件后缀与 MIME 判定。
- 目标尺寸计算。
- 错误码到 `RawDecodeResult.Reason` 的映射。
- 缓存 key 与 decoderVersion。

Instrumented Test：

- 使用 assets 中的小型 DNG / RAF 固定样本。
- EXIF 缩略图读取。
- JNI 解码返回尺寸与像素格式。
- 页面退出后的取消和缓存清理。
- 旋转方向。

视觉回归：

- 对固定样本输出缩小 PNG。
- 与已确认的 golden image 比较 SSIM / 感知差异。
- LibRaw 升级时人工复核肤色、绿色植被、高光和 X-Trans 细节。

性能测试：

- 首张内嵌预览耗时。
- 完整 DNG / RAF 解码耗时。
- Native Heap 峰值。
- 连续打开 20 张 RAW 后是否持续增长。
- 取消操作的最大响应时间。

---

## 13. 分阶段落地建议

### 阶段一：快速预览

- 集成 `ExifInterface`。
- 建立 URI 输入、方向处理和预览缓存。
- 完成无缩略图回退状态。

验收：列表可以流畅浏览 DNG / RAF，不执行完整 RAW 显影。

### 阶段二：DNG 完整解码验证

- 仅在 API 30+ 实验 `AImageDecoder`。
- 用真实目标设备建立 DNG 兼容清单。
- 评估是否满足产品画质。

验收：目标 DNG 样本能按指定最长边稳定输出 Bitmap。

### 阶段三：LibRaw DNG / RAF 统一解码

- 固定 LibRaw 版本与构建产物。
- 接入 JNI 错误映射、取消、尺寸和内存限制。
- 建立目标富士机型样本库。
- 完成开源许可证声明。

验收：目标 DNG / RAF 样本在支持设备上完整通过功能、性能和视觉回归。

### 阶段四：生产优化

- 两阶段渐进显示。
- 内存和磁盘缓存。
- 隔离进程评估。
- 16-bit 编辑或服务器高质量导出。

---

## 14. 最终选型建议

如果产品目标是普通相册或附件选择：

```text
只实施方案一。
```

如果产品只处理 DNG，并且最低支持 API 30：

```text
方案一负责首屏 + 方案二负责完整 DNG。
```

如果产品明确要求 DNG 和 RAF：

```text
方案一负责快速首屏 + 方案三负责统一完整显影。
方案二只作为可选的 DNG 系统能力，不应成为 RAF 流程的依赖。
```

若还要求专业画质、复杂降噪、镜头校正或批量导出，手机端只负责预览，完整显影放到服务端会更容易控制画质、耗时与内存。

---

## 15. 参考资料

- [Android NDK Image Decoder 指南](https://developer.android.com/ndk/guides/image-decoder)
- [Android NDK AImageDecoder API](https://developer.android.com/ndk/reference/group/image-decoder)
- [AndroidX ExifInterface API](https://developer.android.com/reference/androidx/exifinterface/media/ExifInterface.html)
- [AndroidX ExifInterface Release Notes](https://developer.android.com/jetpack/androidx/releases/exifinterface)
- [Android Bitmap 内存指南](https://developer.android.com/topic/performance/memory/guide/bitmaps)
- [LibRaw 官方文档](https://www.libraw.org/docs)
- [LibRaw C++ API](https://www.libraw.org/node/32)
- [LibRaw 支持相机列表](https://www.libraw.org/supported-cameras)
- [LibRaw 0.22.1 Release Notes](https://www.libraw.org/news/libraw-0-22-1-release)
