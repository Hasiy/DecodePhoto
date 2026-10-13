# DecodePhoto 项目实现与构建说明

本文记录 DecodePhoto 的工程结构、格式处理路径、构建方式、当前验证结果和验收边界。
面向使用者的功能简介请查看仓库根目录的 [`README.md`](../README.md)。

## 1. 工程配置

| 项目 | 当前配置 |
|---|---|
| Application ID | `top.hasiy.rawviewer` |
| Android namespace | `top.hasiy.rawviewer` |
| 应用版本 | `1.0`（versionCode 1） |
| Android SDK | minSdk 26 / targetSdk 35 / compileSdk 35 |
| JDK / JVM Target | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.10.1 |
| Kotlin / Compose Plugin | 2.1.20 |
| NDK | 27.1.12297006 |
| CMake | 3.22.1 |
| Compose BOM | 2025.06.01 |
| AndroidX Media3 | 1.8.1 |
| AndroidX ExifInterface | 1.4.2 |

## 2. 模块结构

### `:app`

Android 应用模块，包含：

- Jetpack Compose 页面与页面状态管理；
- RAW、HEIF/HEIC、JPEG 和 AVIF 的解码流程编排；
- EXIF/XMP 元数据读取；
- RGB 直方图与 RGB ↔ HSB/HSV 工具；
- HDR / SDR / 默认显示对比页面；
- Live Photo、Motion Photo 解析与 Media3 播放；
- Android NDK `AImageDecoder` 对照路径。

### `:libraw-android`

独立 Android Library，封装 LibRaw 0.22.1、JNI、CMake 和取消机制，为 DNG、RAF
提供统一的完整显影入口。

### `:libheif-android`

独立 Android Library，封装 libheif 1.23.5、libde265 1.1.3、JNI 和 CMake，作为
HEIF/HEIC 的软件解码路径。

## 3. 图片处理路径

| 格式 | 可用路径 | 当前输出定位 |
|---|---|---|
| DNG | 内嵌 JPEG、API 30+ NDK AImageDecoder、LibRaw | RAW 预览或 8-bit sRGB 显影 |
| RAF | 内嵌 JPEG、LibRaw | RAW 预览或 8-bit sRGB 显影 |
| HEIF / HEIC | Android 系统解码、API 30+ NDK 对照、libheif | 系统结果或软件 fallback |
| JPEG | Android 系统解码 | 普通照片或 Ultra HDR Gain Map 图片 |
| AVIF | Android 系统支持时解码 | 当前系统可识别的静态图片结果 |

选图后自动解码，RAW 先显示内嵌 JPEG，再以 LibRaw 显影结果替换；完整显影失败或
取消时保留预览。HEIF/HEIC 自动模式在 API 34+ 或探测到 HDR transfer 时优先 libheif，
旧系统上的普通 SDR 图优先系统解码，失败后尝试另一条路径。元数据与照片解码独立执行，
直方图在图片显示后后台补齐。

## 4. HDR / SDR 对比页面

主页面提供独立的“HDR / SDR 显示对比”入口。同一张图片可切换以下模式：

| 模式 | Bitmap | Window 色彩模式 |
|---|---|---|
| 默认 | 保留系统解码结果和原始 Gain Map | `COLOR_MODE_DEFAULT` |
| HDR | 保留系统解码结果和原始 Gain Map | 满足条件时使用 `COLOR_MODE_HDR` |
| SDR | 使用移除 Gain Map 的 `ARGB_8888` 基础图 | `COLOR_MODE_DEFAULT` |

HDR 模式只在以下条件全部成立时启用：

1. 系统为 Android 14（API 34）或以上；
2. 解码后的 Bitmap 确认包含 Gain Map；
3. 当前 Display 报告 HDR 能力。

页面同时展示动态范围判定、Bitmap Config、ColorSpace、Gain Map 尺寸与参数、屏幕
HDR 类型和可用的 HDR/SDR headroom。API 版本过低、屏幕不支持 HDR 或图片没有
Gain Map 时，会显示结构化回退原因。

Window 色彩模式由子页面生命周期管理：离开页面、加载新图片、解码失败或切换 SDR
时恢复 `COLOR_MODE_DEFAULT`。由于色彩模式作用于整个 Window，页面采用同图模式切换，
而不是在同一个 Window 中伪造三个拥有不同 HDR 状态的并排区域。

已实现 Ultra HDR Gain Map、libheif 10/12-bit `RGBA_F16` 输出和 BT.2020 HLG/PQ
色彩空间映射。2026-10-13 增加旧系统 BT.2020 HLG/PQ 的 CPU SDR 映射、视频传递函数
与系统解码能力探测、源像素线性亮度直方图及 Gain Map 可视化。非 BT.2020 HDR 原色
在库路径中明确拒绝；AVIF 仍依赖系统解码器。数学测试与合成样本不能替代真实相机
色彩和屏幕亮度验收，详见[增强说明](photo-viewer-enhancements.md)。

## 5. Live Photo 与 Motion Photo

### Android Motion Photo

- 读取 Container XMP 和旧版 `GCamera:MicroVideoOffset`；
- 验证视频范围、ISO BMFF `ftyp` 与实际视频轨道；
- 验证通过后把尾部视频提取到应用私有缓存；
- 根据照片 EXIF Make 与标准结构证据标识部分厂商来源。

### Apple Live Photo

- 同时选择 HEIC/JPEG 静态图与 MOV/MP4 视频；
- 读取 QuickTime Content Identifier；
- MakerNote 可读取唯一标识时执行强配对验证；
- 使用 Media3 播放视频，支持停止和静音切换。

详细流程、限制和样本矩阵见
[`live-photo-motion-photo-implementation.md`](live-photo-motion-photo-implementation.md)。

## 6. 元数据与分析工具

- 使用 AndroidX ExifInterface 读取可访问的 EXIF/XMP 标签；
- 元数据读取与照片解码相互独立；
- RGB 直方图对当前解码结果进行有界像素取样；
- RGB ↔ HSB/HSV 工具支持范围校验、Hue 归一化和颜色预览。

## 7. 资源与隐私边界

- 通过 Storage Access Framework 获取用户选择的文件；
- native 解码前将不可直接使用的输入复制到应用私有缓存；
- 输入文件上限为 200 MiB；
- native 解码限制为 160 MP、单边不超过 32768 px；
- libheif 整图软件解码另有限制：8-bit 源图最多 96 MP，F16 源图最多 64 MP，单块分配上限 512 MiB、libheif 跟踪的总内存上限 768 MiB；这些限制不包含所有外部 codec 和 Android Bitmap 分配，不能替代真机峰值内存验收；
- LibRaw 在 `unpack()` 前检查已知尺寸，并将 `max_raw_memory_mb` 限制为 512 MiB；
- 主流程按设备内存等级限制最长边为 2048 或 4096 px；LibRaw 在创建 Android Bitmap
  前限制输出尺寸，内部 RAW/显影缓冲仍按传感器尺寸分配；
- 共享输入缓存采用引用计数，最多保留两份空闲输入；取消复制会清理未完成文件；
- LibRaw/libheif 的主要像素解码共用后台互斥预算，避免多个页面同时进行高峰值 native 解码；
- HDR 对比页限制输出最长边为 2048 px，以控制原图和 SDR Base 同时存在时的内存；
- 未发布的中间 Bitmap、文件租约和 native 请求由失败/取消路径清理；已发布 Bitmap
  可被查看、比较和导出共用，由 Android 在最后一个引用释放后回收，避免渲染器访问已 recycle 的图像；
- 日志不记录 URI、真实路径、文件名、照片像素、EXIF 内容或 GPS 信息。

## 8. 构建与安装

Windows 环境建议使用仓库脚本，将 Gradle 临时目录限制在项目内：

```powershell
.\gradle-local.ps1 testDebugUnitTest lintDebug assembleDebug
```

Debug APK 输出位置：

```text
app/build/outputs/apk/debug/app-debug.apk
```

安装到已连接的 Android 设备：

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

构建目标 ABI：

- `arm64-v8a`
- `armeabi-v7a`
- `x86_64`

## 9. 当前验证状态

2026-10-08 的历史验证结果（不代表 2026-10-13 改动的验收）：

- `testDebugUnitTest`：43 项测试通过，0 failure / error / skipped；
- `lintDebug`：0 error；
- `assembleDebug`：成功；
- 三个目标 ABI 的 native 模块完成 Debug 构建；
- Debug APK 已成功安装到 OPPO PLJ110 真机，HEIF 页面已由用户确认可显示；
- 自动化验证时设备处于安全锁屏，instrumented Compose 测试未完成；
- 尚未使用仓库内固定的 Ultra HDR、DNG、RAF、HEIF/HEIC 与 Live Photo 样本完成完整真机矩阵。

构建成功只能证明代码、资源和 native 模块可以编译链接，不能替代照片色彩、HDR 亮度、
相机型号兼容性、X-Trans、压缩 RAF、厂商私有 Live 图或跨 Android 版本行为的验收。

## 10. 相关文档

- [HDR / SDR 完整处理与后续实施方案](hdr-sdr-complete-implementation.md)
- [2026-10-13 查看器增强及验证](photo-viewer-enhancements.md)
- [Live Photo / Motion Photo 实现说明](live-photo-motion-photo-implementation.md)
- [HEIF / HEIC 测试矩阵](heif-heic-test-matrix.md)
- [DNG / RAF 解码方案](../android-dng-raf-decoding-guide.md)
- [第三方组件、版本与许可证](../THIRD_PARTY_NOTICES.md)
