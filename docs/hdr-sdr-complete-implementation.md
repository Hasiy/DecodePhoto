# HDR / SDR 完整处理实现方案

## 1. 文档目的

本文为 `RawPhotoViewer` 增加完整 HDR / SDR 照片与 Live Photo 视频处理能力的实施方案。

本文保留原始设计方案。2026-10-13 已补充 BT.2020 HLG/PQ CPU SDR tone mapping、
视频传递函数/解码能力探测、源像素线性亮度直方图和 Gain Map 可视化。
当前算法、边界及实际验证见[查看器增强说明](photo-viewer-enhancements.md)。
真实相机、多厂商和旧 API 的完整样本矩阵仍未完成，不能把全部 HDR 格式标为已完整验收。

文档基线日期：2026-09-30；实施状态更新：2026-10-13。后文阶段表与清单保留设计基线，
最新已实施状态以增强说明为准。

## 2. “完整处理 HDR”的定义

本项目是查看器，不是图片编辑器。本文中的“完整处理”覆盖以下闭环：

1. 识别输入文件的动态范围类型、位深、色域和传递函数。
2. 选择不会无意丢失 HDR 信息的解码路径。
3. 在 HDR 设备上显示 HDR，在 SDR 或旧系统上给出确定、可解释的 SDR 回退。
4. 保留并展示 Ultra HDR gainmap，不把 SDR 基础图误报为 HDR 结果。
5. 对 HLG/PQ 10-bit 或 12-bit HEIF/HEIC/AVIF 保留高位深和色彩元数据。
6. 对 HDR Live Photo / Motion Photo 视频使用 HDR 合适的 Surface 和系统解码器。
7. 直方图、状态标签、错误提示与当前实际渲染路径一致。
8. 通过自动化测试、黄金样本和真实 HDR 设备证明结果，而不只证明“文件能打开”。

以下能力不属于本阶段：

- HDR 拍摄、相机预览或 JPEG/R 编码。
- 对 HDR 图片进行滤镜、贴纸、局部调整后重新编码。
- RAW 文件的专业级 scene-linear HDR 调色和导出。
- Dolby Vision 图片私有格式解析。
- 以截图作为 HDR 亮度验收依据；Android 截图会被转换为 SDR。

## 3. 当前项目基线

### 3.1 工程配置

| 项目 | 当前值 |
|---|---|
| 模块 | `:app`、`:libheif-android`、`:libraw-android` |
| Gradle / Android Gradle Plugin | 8.11.1 / 8.10.1 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 26 |
| Java / Kotlin JVM target | 17 / 17 |
| Kotlin / Compose 插件 | 2.1.20 |
| Compose BOM | 2025.06.01 |
| Media3 | 1.8.1 |
| AndroidX ExifInterface | 1.4.2 |
| NDK | 27.1.12297006 |
| CMake | 3.22.1 |
| libheif | 1.23.5（仓库内置源码） |
| LibRaw | 0.22.1（仓库内置源码） |

### 3.2 当前行为

| 路径 | 当前输出 | HDR 信息现状 |
|---|---|---|
| `ImageDecoder` | 软件 Bitmap，保留系统返回的 ColorSpace 与 Gain Map | API 34+ 检查 Gain Map/HLG/PQ，并按源色彩空间选择目标 ColorSpace |
| API 26–27 `BitmapFactory` | `ARGB_8888` | SDR 输出 |
| NDK `AImageDecoder` | `RGBA_8888` | SDR 输出 |
| LibRaw | 8-bit sRGB `ARGB_8888` | RAW 被渲染为 SDR |
| libheif | 8-bit 使用 `ARGB_8888`；10/12-bit 高精度路径使用 `RGBA_F16` | 读取 bit depth、NCLX/ICC；API 34+ 将 BT.2020 HLG/PQ 映射为对应 HDR ColorSpace，其他情况回退 SDR |
| Compose `Image` | 根据内容、API 与 Display 能力选择 Bitmap 版本 | HDR 页面进入/离开及内容切换时管理 `COLOR_MODE_HDR` |
| RGB 直方图 | 对 Bitmap 的 0–255 R/G/B 计数 | 只反映 SDR 基础像素，不反映 gainmap 应用后的 HDR 亮度 |
| Media3 `PlayerView` | ExoPlayer 默认播放 | 可能由系统透传 HDR，但没有检测、能力提示和验收 |

### 3.3 当前关键缺口

- BT.2020 HLG/PQ 已有参考 CPU 映射；非 BT.2020 HDR 库路径明确拒绝，系统结果仍依赖平台。
- RGB 图仍代表 SDR 通道分布；HDR 页另提供源线性亮度与 Gain Map 分析，尚无 GPU 渲染读回。
- Live 视频已探测 transfer 和系统解码器；厂商 HDR 播放亮度与视频帧映射仍需真实样本验收。
- 尚未用固定的 Ultra HDR、HLG、PQ 与 HDR 视频样本跑完跨 API、跨厂商真机矩阵。

## 4. 支持范围与平台分级

### 4.1 内容类型

| 内容 | HDR 设备目标 | SDR/旧设备目标 |
|---|---|---|
| 普通 SDR JPEG/HEIF/HEIC/AVIF | 原样按正确色域显示 | 原样显示 |
| Ultra HDR JPEG（SDR base + gainmap） | Android 14+ 应用 gainmap | 显示 SDR base，保留文件不修改 |
| ISO 21496-1 gainmap 图片 | 平台/API 支持时应用 gainmap | 显示 base，并标明回退原因 |
| 10/12-bit HLG HEIF/HEIC/AVIF | 保留 BT.2020 + HLG | 经明确 tone mapping 输出 SDR |
| 10/12-bit PQ/HDR10 HEIF/HEIC/AVIF | 保留 BT.2020 + PQ | 经明确 tone mapping 输出 SDR |
| DNG/RAF | 仍按 LibRaw 渲染为 SDR | SDR |
| HDR Live/Motion Photo 视频 | ExoPlayer + `SurfaceView` HDR 播放 | 系统回退或提示不支持 |

RAW 的高位深不等于 HDR 显示格式。DNG/RAF 是相机原始或接近 scene-linear 的数据；在没有明确输出色彩空间、曝光映射和 HDR mastering 策略前，必须把现有 LibRaw 结果标记为 `SDR_RENDER_FROM_RAW`，不能仅因输入为 12/14-bit 就标记为 HDR。

### 4.2 Android API 分级

| API | 能力等级 | 要求 |
|---|---|---|
| 26–27 | SDR 兼容层 | 普通 Bitmap/RAW SDR；HDR 内容必须明确 tone-map 或回退，不宣称 HDR 显示 |
| 28–33 | 高精度/广色域兼容层 | 可使用 `ImageDecoder` 和 `RGBA_F16`，但 HLG/PQ 目标 ColorSpace 受平台限制；Ultra HDR 只显示 SDR base |
| 34–35 | 完整 Ultra HDR 主路径 | `Bitmap.hasGainmap()`、Gainmap、HDR 窗口、Compose HDR 绘制、`Display.hdrSdrRatio` |
| 36+ | 扩展 gainmap 元数据 | 可使用 gainmap direction、alternative primaries 等新增 API；需要升级 compileSdk 后单独适配 |

首个可发布目标应是：

- API 34+：Ultra HDR 完整显示。
- API 26+：所有支持格式均有安全的 SDR 回退。
- HLG/PQ 静态图片：系统路径优先；原生路径完成高位深和 tone mapping 后再对外承诺一致性。

## 5. 核心概念与判定原则

### 5.1 位深、色域和动态范围必须分开

- 10-bit 只代表量化精度，不自动代表 HDR。
- Display P3 是广色域，不自动代表 HDR。
- BT.2020 是色域/primaries，不自动代表 HDR。
- HLG、PQ 是 HDR 传递函数。
- Ultra HDR 是兼容 SDR 的基础图加 gainmap。
- ICC/NCLX 缺失时不能靠扩展名猜测 HDR。

### 5.2 动态范围判定优先级

按以下证据从强到弱分类：

1. API 34+ 解码后的 `Bitmap.hasGainmap() == true` → `ULTRA_HDR_GAINMAP`。
2. 容器 NCLX transfer characteristic 为 HLG → `HDR_HLG`。
3. 容器 NCLX transfer characteristic 为 SMPTE ST 2084/PQ → `HDR_PQ`。
4. 平台解码 Bitmap 的 ColorSpace 明确为 BT.2020 HLG/PQ → 对应 HDR 类型。
5. 只有 bit depth > 8、BT.2020 primaries，但 transfer 不明 → `HDR_UNKNOWN`，不可自动宣称正确 HDR。
6. 以上均无 → `SDR`。

文件扩展名、MIME、厂商品牌名称和“Super HDR”等营销名称不能替代结构化探测。

## 6. 目标数据模型

当前实现已通过 `PhotoDecodeOutput`、`DecodedColorInfo`、`HdrComparisonImage` 等模型携带
bit depth、Bitmap config、ColorSpace、动态范围、SDR fallback 与 Gain Map 信息。以下模型
保留为原始设计参考；最终代码按页面职责拆分命名：

```kotlin
enum class DynamicRangeKind {
    SDR,
    SDR_RENDER_FROM_RAW,
    ULTRA_HDR_GAINMAP,
    HDR_HLG,
    HDR_PQ,
    HDR_UNKNOWN,
}

enum class TransferFunction {
    SRGB,
    LINEAR,
    HLG,
    PQ,
    UNKNOWN,
}

data class ColorEncoding(
    val bitDepth: Int,
    val colorSpaceName: String?,
    val primaries: String?,
    val transfer: TransferFunction,
    val matrix: String?,
    val fullRange: Boolean?,
    val hasIccProfile: Boolean,
)

data class GainmapInfo(
    val width: Int,
    val height: Int,
    val channelCount: Int,
    val displayRatioForSdr: Float,
    val displayRatioForFullHdr: Float,
    val ratioMin: List<Float>,
    val ratioMax: List<Float>,
    val gamma: List<Float>,
)

data class DisplayCapability(
    val supportsHdr: Boolean,
    val supportedHdrTypes: Set<String>,
    val isWideColorGamut: Boolean,
    val hdrSdrRatio: Float?,
)

sealed interface RenderPayload {
    val bitmap: Bitmap

    data class Sdr(override val bitmap: Bitmap) : RenderPayload

    data class UltraHdr(
        override val bitmap: Bitmap,
        val gainmapInfo: GainmapInfo,
    ) : RenderPayload

    data class HdrBitmap(
        override val bitmap: Bitmap,
        val transfer: TransferFunction,
    ) : RenderPayload
}

data class DecodedPhoto(
    val payload: RenderPayload,
    val dynamicRange: DynamicRangeKind,
    val colorEncoding: ColorEncoding,
    val source: DecodeSource,
    val fallbackReason: HdrFallbackReason? = null,
)
```

实现时可按现有项目风格拆分，但必须保留这几类信息；不要继续把所有结果压成无语义的 `Bitmap`。

## 7. 目标架构

```text
Storage Access Framework URI
            │
            ▼
PhotoFormatResolver
            │
            ▼
PhotoCapabilityInspector
  ├─ MIME / signature / dimensions
  ├─ bit depth
  ├─ ICC / NCLX
  ├─ HLG / PQ
  └─ gainmap（解码后确认）
            │
            ▼
HdrDecodeCoordinator
  ├─ SystemHdrPhotoDecoder        API 28+，API 34+ Ultra HDR 主路径
  ├─ LegacySdrPhotoDecoder        API 26–27
  ├─ LibHeifHdrDecoder            原生高位深/元数据路径
  ├─ LibRawDecoder                RAW → SDR
  └─ HdrToneMapper                HDR → SDR 明确回退
            │
            ▼
DecodedPhoto + DisplayCapability
            │
      ┌─────┴─────────┐
      ▼               ▼
HdrWindowController   HistogramAnalyzer
      │               ├─ SDR RGB
      │               ├─ gainmap
      │               └─ HDR luminance
      ▼
Compose Image / Media3 SurfaceView
```

### 7.1 建议新增职责

| 组件 | 建议位置 | 职责 |
|---|---|---|
| `PhotoCapabilityInspector` | `app/.../photo/inspect/` | 汇总格式、bit depth、ICC/NCLX、transfer、gainmap 证据 |
| `SystemHdrPhotoDecoder` | `app/.../photo/decode/` | 平台解码、保留 gainmap/ColorSpace、产出 `DecodedPhoto` |
| `HdrDecodeCoordinator` | `app/.../photo/decode/` | 选择路径、失败回退、记录可展示原因 |
| `HdrWindowController` | `app/.../photo/display/` | HDR/SDR 窗口切换、Display 能力监听和恢复 |
| `HdrToneMapper` | `libheif-android` 或独立 native 层 | HLG/PQ → SDR 的确定性转换 |
| `HdrHistogramAnalyzer` | `app/.../photo/analysis/` | SDR、gainmap、HDR luminance 分析 |
| `HdrVideoCapabilityInspector` | `app/.../live/` | Media3 Format 与 Display/decoder HDR 能力匹配 |

不建议在 `MainActivity` 或 Composable 内解析容器、扫描像素或执行 tone mapping。

## 8. 静态图片解码策略

### 8.1 Android 系统路径

系统路径是 API 34+ Ultra HDR 的首选路径。

需要调整：

1. 保留当前尺寸和像素总量安全检查。
2. 对 HDR 候选图片使用 `ImageDecoder.MEMORY_POLICY_DEFAULT`，不要使用 `LOW_RAM`。
3. 不要强制把 HDR 候选图解成 `ARGB_8888`。
4. 优先让系统选择最适合来源的 Bitmap Config 和 ColorSpace。
5. API 34+ 解码后检查：
   - `bitmap.hasGainmap()`；
   - `bitmap.config`；
   - `bitmap.colorSpace`；
   - `bitmap.isWideGamut`；
   - gainmap contents 与元数据。
6. HLG/PQ 目标 ColorSpace 只在 API 34+ 显式请求；Android 官方说明 API 34 起 `ImageDecoder.setTargetColorSpace()` 才接受 BT.2020 HLG 这类非 ICC 参数曲线。
7. 渲染 Bitmap 和分析 Bitmap 分离：
   - 渲染 Bitmap 保留系统默认/硬件友好的配置与 gainmap；
   - 直方图另解一个小尺寸软件分析 Bitmap，避免为了 `getPixels()` 降低主图质量。

Ultra HDR 路径示意：

```kotlin
val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
    validateDimensions(info.size)
    decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
    decoder.setTargetSize(targetWidth, targetHeight)
}

val dynamicRange = if (
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
    bitmap.hasGainmap()
) {
    DynamicRangeKind.ULTRA_HDR_GAINMAP
} else {
    classifyFromColorSpace(bitmap.colorSpace)
}
```

代码示意不等于最终实现；必须验证缩放、旋转后 gainmap 是否仍与 base 对齐。

### 8.2 API 26–27 路径

API 26–27 没有 Ultra HDR Bitmap API。策略固定为：

- 普通 SDR：`BitmapFactory` 解码。
- Ultra HDR JPEG：作为普通 JPEG 显示 SDR base。
- HLG/PQ HEIF：如系统不能可靠解码，交给 libheif；libheif 必须执行明确 tone mapping 后返回 sRGB `ARGB_8888`。
- UI 标记“已回退 SDR”，不能显示“HDR 已启用”。

### 8.3 libheif 高位深路径

仓库中的 libheif 1.23.5 已包含以下基础能力：

- `heif_image_handle_get_luma_bits_per_pixel()`；
- `heif_image_handle_get_nclx_color_profile()`；
- 16-bit interleaved `RRGGBB/RRGGBBAA` 输出格式；
- 10/12-bit HEVC/AV1 解码能力取决于编译进来的 codec。

当前桥接层已采用两阶段流程：先探测容器与色彩元数据，再根据 bit depth、动态范围和
平台能力选择 8-bit `heif_chroma_interleaved_RGBA` 或 16-bit
`heif_chroma_interleaved_RRGGBBAA_LE` 输出：

#### 探测阶段

原生层返回：

- width / height；
- luma/chroma bit depth；
- NCLX primaries、transfer、matrix、full-range；
- ICC profile 是否存在及字节大小；
- alpha；
- codec 和 primary item 信息。

#### 解码阶段

| 输入 | 原生输出 | Android 输出 |
|---|---|---|
| SDR ≤ 8-bit | 8-bit RGBA | `ARGB_8888` + 正确 ColorSpace |
| SDR 10/12-bit | 16-bit RGB | `RGBA_F16` 或高质量 tone-map 到 SDR |
| HLG/PQ，HDR 显示可用 | 16-bit RGB + NCLX | `RGBA_F16` + 对应 BT.2020 ColorSpace |
| HLG/PQ，HDR 显示不可用 | 16-bit RGB | `HdrToneMapper` → sRGB `ARGB_8888` |

注意：libheif 的 16-bit unsigned channel 不能直接按位复制到 Android `RGBA_F16`。`RGBA_F16` 存储的是 half-float，需要按目标 ColorSpace 和 transfer 语义进行数值转换。

当前 vendored public headers 没有发现可直接依赖的稳定 gainmap/tmap C API。因此：

- Ultra HDR JPEG/gainmap 优先使用 Android API 34+ 系统路径。
- 不把 libheif 高位深支持等同于 gainmap 支持。
- 若后续需要 HEIF gainmap/tmap，必须先锁定 libheif 版本、公开 API、文件样本和兼容格式，再单独设计升级。

### 8.4 RAW 路径

保持现有 LibRaw 输出策略：

- `output_color = 1`（sRGB）；
- `output_bps = 8`；
- 输出 `ARGB_8888`；
- 动态范围标记为 `SDR_RENDER_FROM_RAW`。

如果未来增加 RAW HDR 显示，需要独立方案处理曝光、白点、相机色彩矩阵、scene-linear 到 display-referred 的映射，不纳入本次实现。

## 9. HDR → SDR Tone Mapping

不能依赖“转成 8-bit”作为 tone mapping。正确回退至少需要：

1. 根据 NCLX/ICC 解析输入 primaries 和 transfer。
2. 将 HLG/PQ code value 转换到线性光或绝对/相对亮度域。
3. 按内容/显示目标执行 highlight roll-off。
4. 从 BT.2020 等输入色域映射到目标 sRGB 或 Display P3。
5. 应用目标 OETF。
6. 抖动并量化到 8-bit，减少 banding。

建议默认策略：

| 输入 | SDR tone mapping 基线 |
|---|---|
| PQ | 以 BT.2390 思路做 knee/roll-off，SDR reference white 可配置并固定测试值 |
| HLG | 按 BT.2100 system gamma/显示峰值转换，再映射到 SDR |
| HDR_UNKNOWN | 不猜测；优先系统解码，否则提示不受支持 |

Tone mapper 必须是可替换接口，并返回使用的参数，方便 UI 和测试报告记录。第一版不要提供用户可调“看起来更亮”的滑杆，否则很难建立确定的验收基准。

## 10. Ultra HDR Gainmap

### 10.1 读取

API 34+：

- 使用 `Bitmap.hasGainmap()` 确认；
- 通过 `Bitmap.getGainmap()` 获取 `Gainmap`；
- 读取 gainmap contents、ratio min/max、gamma、epsilon 和 display ratio；
- 根据 contents 的 Bitmap Config 判断单通道或 RGB gainmap；
- 不假设 gainmap 与 base 尺寸相同。

### 10.2 显示

Android 的硬件 Canvas 在 HDR Activity Window 和有足够 HDR headroom 时自动应用 gainmap。Compose 官方示例直接使用：

```kotlin
Image(bitmap = bitmap.asImageBitmap(), contentDescription = null)
```

关键不是另写 Compose shader，而是：

- Bitmap 必须仍携带 gainmap；
- Window 必须处于 `COLOR_MODE_HDR`；
- Canvas 必须走硬件加速；
- 设备和当前 Display 必须支持 HDR；
- 页面离开 HDR 内容后要恢复默认色彩模式。

### 10.3 编辑和变换

当前项目只缩放/旋转用于显示。实现时必须验证 Android Bitmap API 是否同步处理 gainmap；不要先提取 base、手动生成普通 Bitmap 再绘制，否则会丢失 gainmap。

如后续加入裁剪、滤镜或合成，需要同步修改 gainmap。普通像素编辑可能保留旧 gainmap，但旧亮度信息已不再对应修改后的内容。

## 11. HDR 窗口与显示能力

当前实现由 `HdrDisplayPolicy` 计算显示决策，并由页面级 Compose effect 根据当前图片与
Display 能力设置或恢复 Window 色彩模式。

### 11.1 决策规则

```text
内容是 HDR？ ── 否 ──> COLOR_MODE_DEFAULT
     │
     是
     ▼
API/Display/decoder 支持对应 HDR？ ── 否 ──> SDR fallback + 原因
     │
     是
     ▼
COLOR_MODE_HDR + 显示 HDR 状态和 hdrSdrRatio
```

### 11.2 生命周期要求

- 只在实际显示 HDR 静态图或 HDR 视频时启用 HDR Window。
- 文件切换、解码失败、停止 HDR Live 视频或 Activity 销毁时恢复 `COLOR_MODE_DEFAULT`。
- 折叠屏内外屏、外接显示器和多窗口移动时重新查询 Display 能力。
- API 34+ 注册 `hdrSdrRatio` 变化监听，离开 Composition 时解除注册。
- Window 控制属于 Activity/display 层，不进入 ViewModel。
- ViewModel 只暴露“内容需要 HDR”和“已选择的回退结果”。

### 11.3 设备能力

需要记录但不写入敏感日志：

- `Display.HdrCapabilities.supportedHdrTypes`；
- `Display.isWideColorGamut`；
- API 34+ `Display.isHdrSdrRatioAvailable`；
- API 34+ `Display.hdrSdrRatio`；
- Bitmap Config、ColorSpace、gainmap presence；
- decoder path 和 fallback reason。

## 12. Compose UI

成功页面增加以下只读信息：

| 字段 | 示例 |
|---|---|
| 动态范围 | `Ultra HDR (gainmap)` / `HDR HLG` / `SDR` |
| 当前显示 | `HDR 已启用 · 3.2× headroom` / `已回退 SDR` |
| 位深 | `10-bit` |
| 色域 | `BT.2020` / `Display P3` / `sRGB` |
| 传递函数 | `HLG` / `PQ` / `sRGB` |
| Bitmap | `RGBA_F16` / `ARGB_8888` |
| Gainmap | `RGB · 1008 × 756` |
| 解码器 | `Android ImageDecoder` / `libheif` |
| 回退原因 | `设备不支持 HDR` / `API < 34` / `transfer 未知` |

状态颜色不能作为唯一语义，所有状态都需要文字和 TalkBack 描述。

建议加入显示模式：

- `自动`：HDR 设备显示 HDR，否则 SDR。
- `强制 SDR 预览`：使用 SDR base 或 tone-mapped 结果，便于比较。
- `Gainmap 查看`：只用于诊断，明确标注它不是最终照片。

不提供“强制 HDR”绕过能力检查；设备不支持时应禁用并解释原因。

## 13. 直方图设计

当前 0–255 RGB 直方图继续作为 SDR/base 分析，但标题改为“SDR 基础图 RGB 直方图”。

HDR 增加两类分析：

### 13.1 Gainmap 直方图

- 单通道：显示 gain 值分布。
- RGB gainmap：分别显示 R/G/B gain 分布。
- 横轴不能标成 0–255 亮度，应转换为实际 gain 或 stops。
- UI 显示 `ratioMin`、`ratioMax` 和 gamma，避免把压缩编码值当成真实增益。

### 13.2 HDR 亮度直方图

- 在线性光/相对亮度域统计，不从 tone-mapped 8-bit Bitmap 反推。
- 横轴建议使用 stops over SDR white，或在有可靠 mastering/display metadata 时使用 nits。
- 标记 SDR white、当前显示峰值和裁切区域。
- 分析像素继续使用有界取样，默认上限约 26 万像素。

对 Ultra HDR 的“最终合成 HDR 直方图”需要手动应用 gainmap 数学或读取 GPU 渲染结果，成本较高。第一阶段只要求 SDR base + gainmap 两张图，第二阶段再实现合成亮度直方图。

## 14. HDR Live Photo / Motion Photo 视频

当前使用 Media3 ExoPlayer + `PlayerView`。官方说明 ExoPlayer 默认支持 HDR 播放，但最终能力取决于容器、codec profile、平台 decoder 和 Display。

实现要求：

1. 保持 `SurfaceView`，不要为动画或变换改成 `TextureView`；`TextureView` 的 HDR 支持有限，可能发生 HDR → SDR 转换、裁切和 banding。
2. 从 Media3 `Format.colorInfo` 读取 color space、range 和 transfer。
3. 使用 Display HDR capabilities 判断 HLG/HDR10/HDR10+/Dolby Vision 能否显示。
4. 监听 decoder initialization/format 变化，UI 显示实际轨道信息和 decoder 名称。
5. HDR 视频播放时启用 HDR Window；停止播放后按静态图状态恢复。
6. 不支持时允许系统选择 SDR fallback，或给出“当前设备无法 HDR 播放”的明确提示。
7. Apple Live Photo 常见 HEVC Main10、HLG/Dolby Vision 和厂商私有 metadata 都需要真实样本验证。

静态图是 SDR、配对视频是 HDR，或反过来，必须分别建模；不能用静态图的动态范围代表整个 Live Asset。

## 15. 解码与回退决策表

| 内容 | 条件 | 首选路径 | 回退 |
|---|---|---|---|
| SDR JPEG | 全 API | 系统 decoder | 错误提示 |
| Ultra HDR JPEG | API 34+ + HDR Display | 系统 decoder + gainmap + HDR Window | SDR base |
| Ultra HDR JPEG | API < 34 或 SDR Display | 系统/BitmapFactory SDR base | 标记 SDR fallback |
| 8-bit HEIF/HEIC | API 28+ | `ImageDecoder` | libheif 8-bit SDR |
| 10/12-bit HLG/PQ HEIF | API 34+、系统支持 | `ImageDecoder` 高精度 HDR | libheif HDR 或 tone-mapped SDR |
| 10/12-bit HLG/PQ HEIF | 旧 API | libheif + tone mapper | 不支持提示 |
| AVIF HDR | 系统支持 | `ImageDecoder` | libheif codec 可用时解码，否则不支持 |
| DNG | API 30+ 对照 | `AImageDecoder` SDR 或 LibRaw SDR | embedded JPEG SDR |
| RAF | 全 API | LibRaw SDR | embedded JPEG SDR |
| HDR Live 视频 | decoder + Display 支持 | ExoPlayer + SurfaceView | SDR fallback/提示 |

回退发生时保留输入文件不变，并在 UI State 中设置结构化 `HdrFallbackReason`：

```kotlin
enum class HdrFallbackReason {
    API_LEVEL_UNSUPPORTED,
    DISPLAY_NOT_HDR,
    DECODER_NOT_AVAILABLE,
    UNSUPPORTED_TRANSFER,
    COLOR_METADATA_MISSING,
    GAINMAP_NOT_PRESERVED,
    MEMORY_LIMIT,
}
```

## 16. 资源、性能与线程

### 16.1 内存预算

4096 × 4096 的近似像素内存：

| Config | 每像素 | 单张约占用 |
|---|---:|---:|
| `ARGB_8888` | 4 bytes | 64 MiB |
| `RGBA_F16` | 8 bytes | 128 MiB |

如果同时持有 base、gainmap、HDR Bitmap、SDR fallback 和分析副本，很容易超过移动设备预算。因此：

- HDR 主图最长边默认值应单独评估，不能直接沿用 SDR 的 4096 px。
- 解码前根据 Config 预估内存。
- 同一时刻只保留当前渲染结果和小尺寸分析图。
- 新文件选择、页面离开和解码取消时释放旧引用。
- native buffer、`heif_image`、Bitmap pixels lock 必须在所有错误路径释放。
- tone mapping 和直方图在 `Dispatchers.Default`/native worker 执行。
- 文件复制和容器读取在 `Dispatchers.IO` 执行。
- 每个长循环保留取消检查。

### 16.2 缓存

- 不缓存未经限制的 `RGBA_F16` 全尺寸结果。
- 缓存 key 必须包含 URI identity、修改时间、目标尺寸、动态范围模式和 tone-map 参数。
- 私有缓存继续执行大小上限和结束清理。
- 不把用户照片、gainmap 或完整 metadata 写入日志。

## 17. 错误处理与日志

日志只记录技术类别：

- format、decoder source、bit depth、transfer 类别；
- dimensions、Bitmap Config；
- gainmap presence 和尺寸；
- fallback reason；
- exception class；
- Display HDR capability 类型。

禁止记录：

- URI、真实路径、文件名；
- EXIF 值、GPS；
- 照片像素或 gainmap 数据；
- 可识别用户设备内容的信息。

所有 catch block 都要写日志并转换为 Error/Fallback UI State。HDR 探测失败不能默默当成 HDR 成功；如果安全显示 SDR base，可以成功显示但必须带 fallback reason。

## 18. 文件级实施清单

建议变更范围：

```text
app/src/main/java/top/hasiy/rawviewer/
├── MainActivity.kt                         # 接入 HdrWindowController
├── RawViewerViewModel.kt                   # DecodedPhoto / fallback UI State
├── HistogramChart.kt                       # SDR/gainmap/HDR 模式
├── photo/
│   ├── model/HdrModels.kt                  # 动态范围和色彩模型
│   ├── inspect/PhotoCapabilityInspector.kt
│   ├── decode/HdrDecodeCoordinator.kt
│   ├── decode/SystemHdrPhotoDecoder.kt
│   ├── display/HdrWindowController.kt
│   └── analysis/HdrHistogramAnalyzer.kt
└── live/
    ├── inspect/HdrVideoCapabilityInspector.kt
    └── ui/LivePhotoPlayer.kt               # HDR Format/Surface 状态

libheif-android/src/main/
├── java/top/hasiy/libheif/
│   ├── LibHeifNativeDecoder.kt             # metadata + 高位深结果
│   └── LibHeifModels.kt
└── cpp/libheif_decoder.cpp                 # NCLX、bit depth、16-bit 输出

app/src/test/                                # 分类、tone-map、直方图单测
app/src/androidTest/                         # gainmap/Window/Compose 测试
docs/hdr-sdr-test-matrix.md                  # 真机结果表，实施阶段新增
```

不要在同一提交中顺便重构 Live Photo 解析器、文件缓存或 RAW 解码器；HDR 变更要能按阶段回滚和比较。

## 19. 自动化测试

### 19.1 单元测试

至少覆盖：

- HLG/PQ/NCLX/ICC/bit depth 分类。
- bit depth > 8 但 transfer 未知时返回 `HDR_UNKNOWN`。
- GainmapInfo 字段转换与通道判断。
- decoder 选择和所有 fallback reason。
- API/Display 能力组合决策。
- PQ/HLG tone-map 的黑点、中灰、SDR white、高光 roll-off。
- BT.2020 → sRGB gamut mapping 边界。
- 8-bit/16-bit/half-float 像素转换。
- SDR RGB、gainmap stops、HDR luminance 直方图。
- 取消、超限尺寸和内存预算。

Tone mapper 使用固定输入向量和黄金数值，设置合理 epsilon；不能只做“输出不为空”的测试。

### 19.2 Instrumented / Compose 测试

- API 33：Ultra HDR 文件只显示 SDR base，并显示回退提示。
- API 34/35：`hasGainmap()` 为 true，HDR 内容触发 HDR Window。
- SDR 图片不会错误触发 HDR Window。
- 文件切换 HDR → SDR 后恢复默认 Window 模式。
- HDR 解码失败后 Window 也恢复默认模式。
- Compose 图片重组不会重复解码或重复注册 Display listener。
- gainmap/动态范围/色域字段具备 TalkBack 文本。
- Activity recreate 和前后台切换后状态正确。

### 19.3 Native 测试

- 8-bit、10-bit、12-bit HEIF/AVIF metadata 探测。
- NCLX HLG/PQ/BT.2020 和 ICC 提取。
- 16-bit endian 与 stride。
- `RGBA_F16` 数值转换。
- 损坏 profile、未知 transfer、截断文件。
- 三 ABI：`arm64-v8a`、`armeabi-v7a`、`x86_64`。
- AddressSanitizer/UBSan 可用配置下检查 native 越界和泄漏。

## 20. 真机与样本矩阵

仓库不应提交带隐私的用户照片。使用官方样本、自制无隐私测试图或获得明确授权的素材。

### 20.1 静态图片

| 样本 | 预期 |
|---|---|
| SDR sRGB JPEG | 判定 SDR，HDR Window 不启用 |
| SDR Display P3 JPEG/HEIF | 判定 SDR + wide gamut，颜色与参考一致 |
| Android Ultra HDR JPEG/R | API 34+ 检测 gainmap，HDR 屏更亮；旧设备显示 SDR base |
| 单通道 gainmap | metadata、增益范围和诊断图正确 |
| RGB gainmap | 三通道增益分别读取和显示 |
| 10-bit BT.2020 HLG HEIC | 判定 HLG，HDR 与 SDR fallback 都无明显裁切/banding |
| 10-bit BT.2020 PQ HEIC | 判定 PQ，高光 roll-off 符合黄金结果 |
| 12-bit HEIF | libheif 高位深路径不先量化到 8-bit |
| HDR AVIF | 支持设备显示；不支持设备明确回退 |
| metadata 缺失的 10-bit 文件 | `HDR_UNKNOWN`，不猜测成功 |
| gainmap 损坏但 base 可读 | 显示 SDR base + fallback reason |

### 20.2 Live 视频

| 样本 | 预期 |
|---|---|
| SDR AVC Live/Motion Photo | SDR 播放 |
| HEVC Main10 HLG | 支持设备 HDR SurfaceView 播放 |
| HDR10/PQ | 支持设备 HDR 播放或明确提示不支持 |
| Dolby Vision | 依设备 decoder 能力显示结果，不做软件假支持 |
| HDR 静态图 + SDR 视频 | 两段状态分别正确 |
| SDR 静态图 + HDR 视频 | 播放时切 HDR，结束后恢复 SDR |

### 20.3 设备覆盖

最低建议：

- API 26 或 27 SDR 设备。
- API 33 SDR/广色域设备。
- API 34+ 支持 Ultra HDR 的 Pixel。
- API 34+ 支持 Ultra HDR 的 Samsung。
- 小米、vivo、OPPO 各至少一台 HDR 屏设备。
- 可用时增加折叠屏，验证 Display 切换。

每条结果记录：设备型号、Android 版本、屏幕 HDR 类型、样本 ID、decoder、Bitmap Config、ColorSpace、gainmap、Window mode、fallback reason 和人工观察。不记录用户文件名和 EXIF/GPS。

## 21. 视觉与数值验收

### 21.1 不能作为证据的项目

- APK 构建成功。
- 在普通显示器上的 Android Studio Preview。
- Android 截图看起来更亮；系统截图会 tone-map 到 SDR。
- 文件位深为 10-bit。
- 厂商相册显示“HDR”标签。

### 21.2 有效证据

- `Bitmap.hasGainmap()` 和 Gainmap metadata。
- Window 当前为 `COLOR_MODE_HDR`。
- 当前 Display 支持对应 HDR 类型且 `hdrSdrRatio > 1`。
- HLG/PQ/NCLX/ICC 的结构化探测结果。
- HDR/SDR 数值黄金测试。
- 与 Android 官方 Ultra HDR 样本、参考渲染器和厂商相册对比。
- 真实 HDR 屏现场观察；需要亮度定量时使用测光设备，而不是截图。

## 22. 实施阶段与工期

以下为一名熟悉 Kotlin/Compose 与 C++/JNI 的工程师估算，不含等待测试设备和样本的时间。

| 阶段 | 内容 | 估算 |
|---|---|---:|
| P0 | 样本、测试基线、能力矩阵 | 1–2 天 |
| P1 | `DecodedPhoto`、HDR 模型、探测与 fallback | 2–3 天 |
| P2 | API 34+ Ultra HDR/gainmap + HDR Window + UI | 2–3 天 |
| P3 | 系统 HLG/PQ/ColorSpace/RGBA_F16 路径 | 2–4 天 |
| P4 | libheif metadata、16-bit、half-float bridge | 4–6 天 |
| P5 | HDR → SDR tone mapper 与黄金测试 | 4–7 天 |
| P6 | HDR 视频能力、SurfaceView、窗口联动 | 2–3 天 |
| P7 | SDR/gainmap/HDR 直方图 | 2–3 天 |
| P8 | 真机矩阵、性能、内存、问题修复 | 4–7 天 |

总计约 **23–38 人日**。如果第一版只做 Android 14+ Ultra HDR gainmap 和旧系统 SDR fallback，不包含原生 HLG/PQ tone mapping，可缩小到约 **7–11 人日**。

## 23. 推荐实施顺序

1. 先准备合法、无隐私的 SDR/Ultra HDR/HLG/PQ 样本和黄金结果。
2. 引入动态范围、色彩编码、Display capability 和 fallback 模型。
3. 完成 API 34+ Ultra HDR 系统路径和 HDR Window 生命周期。
4. 将当前 RGB 直方图明确标记为 SDR base。
5. 完成 HLG/PQ 系统高精度路径。
6. 扩充 libheif metadata 和 16-bit JNI bridge。
7. 完成确定性的 HDR → SDR tone mapping。
8. 完成 HDR 视频探测和窗口联动。
9. 增加 gainmap/HDR luminance 直方图。
10. 跑完自动化、三 ABI 和真实 HDR 设备矩阵后再更新 README 支持声明。

## 24. 验收标准

全部满足后才能将功能标记为完整验收。当前代码已覆盖 Gain Map 读取、Window 模式、
HLG/PQ 结构化判定、libheif 高精度输出和模式恢复；下列项目仍以完整样本矩阵的最终结果为准：

- [ ] 普通 SDR 图片不启用 HDR Window，颜色无回归。
- [ ] API 34+ Ultra HDR JPEG 能检测并读取 gainmap。
- [ ] HDR 屏上启用 HDR Window，Compose 正确显示 gainmap 合成结果。
- [ ] API < 34 或 SDR 屏显示 SDR base，并有明确 fallback reason。
- [ ] HLG/PQ 由 metadata 和 ColorSpace 判断，不靠位深或扩展名猜测。
- [ ] 系统 HDR 路径不使用 `MEMORY_POLICY_LOW_RAM`。
- [ ] libheif HDR 路径不在 tone mapping 前量化为 8-bit。
- [ ] HDR → SDR 有明确算法、参数和黄金数值测试。
- [ ] RAW 仍标记为 SDR render，不误报 HDR。
- [ ] HDR Live 视频使用 SurfaceView，并校验 Display/decoder 能力。
- [ ] HDR → SDR、静态图 → 视频、Display 切换时 Window mode 正确恢复。
- [ ] 直方图区分 SDR base、gainmap 和 HDR luminance。
- [ ] 内存、取消、错误和 native 资源释放通过测试。
- [ ] API 26、33、34+ 以及至少两家 OEM 的真机验收完成。
- [ ] `testDebugUnitTest`、相关 instrumented tests、`lintDebug`、`assembleDebug` 通过。
- [ ] README 只写入已通过真实样本/设备验证的能力。

## 25. 关键风险与待确认决策

| 风险/决策 | 建议 |
|---|---|
| bundled libheif gainmap/tmap public API 不明确 | gainmap 先走 API 34+ 系统路径，不把 libheif 当作 gainmap fallback |
| `RGBA_F16` 内存翻倍 | 渲染图与分析图分离；动态尺寸预算 |
| OEM 解码和色彩实现差异 | 保留系统/libheif 对照模式，记录设备矩阵 |
| 缺失或错误 NCLX/ICC | 不猜测 HDR；返回 `HDR_UNKNOWN` 或 SDR fallback |
| API 36 Gainmap 新字段 | 核心方案先兼容 compileSdk 35；扩展元数据需评估升级 compileSdk 36 |
| Compose/HDR 生命周期 | 参考 Android 官方 Compose Ultra HDR 示例，集中到 Window controller |
| HDR 截图不可验证 | 使用 metadata、Display ratio、参考样本和真实屏幕观察 |
| Tone mapping 主观差异 | 第一版锁定算法和参数，黄金测试优先于可调 UI |

## 26. 参考资料

- [Android：Support Ultra HDR](https://developer.android.com/media/grow/ultra-hdr)
- [Android：Display Ultra HDR images](https://developer.android.com/media/grow/ultra-hdr/display)
- [Android：Edit Ultra HDR images](https://developer.android.com/media/grow/ultra-hdr/edit)
- [Android：Ultra HDR Image Format](https://developer.android.com/media/platform/hdr-image-format)
- [Android API：Gainmap](https://developer.android.com/reference/android/graphics/Gainmap)
- [Android API：ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)
- [Android 官方 Platform Sample：Compose Ultra HDR](https://github.com/android/platform-samples/blob/main/samples/graphics/ultrahdr/src/main/java/com/example/platform/graphics/ultrahdr/display/DisplayUltraHDRScreen.kt)
- [Android：HDR video playback](https://developer.android.com/media/grow/hdr-playback)
- [Android Media3：Supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats)
- [libheif 官方仓库](https://github.com/strukturag/libheif)
