# Apple Live Photo 与 Android Motion Photo 实现方案

## 1. 文档目的

本文定义 `RawPhotoViewer` 在现有 DNG、RAF、HEIF、HEIC 静态图片查看能力上，增加以下 Live 图能力的实现方案：

- Apple Live Photo（HEIC/JPEG 与 MOV 配对资源）。
- Android Motion Photo 1.0。
- 旧版 Google MicroVideo Motion Photo。
- 小米动态照片。
- vivo Live Photo。
- OPPO Live Photo。

本文同时作为实现基线和验收说明；具体完成状态见下一节。

## 1.1 当前实现状态

2026-10-13 增加视频容器提取、可选时间点 JPEG 关键帧保存、传递函数和系统解码能力探测，
并以文件引用租约保护播放/导出生命周期。详见[查看器增强及验证](photo-viewer-enhancements.md)。

已实现：

- Android Motion Photo 1.0 Container XMP 解析。
- 旧版 Google `GCamera:MicroVideoOffset` 读取兼容。
- 视频范围、ISO BMFF `ftyp`、视频轨道、尺寸和时长验证。
- Apple HEIC/JPEG + MOV/MP4 双文件导入。
- Apple QuickTime Content Identifier 读取，以及 MakerNote 存在唯一 UUID 时的强配对。
- 小米、vivo、OPPO 标准 Motion Photo 的来源标识。
- Media3 播放、停止、静音、生命周期释放与静态图降级。
- Live 元数据和现有全部 EXIF/XMP 同页展示。
- 私有缓存、取消清理、过期缓存清理和输入边界限制。
- XMP、ISO BMFF 与格式识别单元测试。

仍需真实原始样本或厂商能力后完成：

- 不符合 Google Motion Photo 标准的小米私有变体及小米 SDK 接入。
- vivo、OPPO 未公开私有容器变体。
- 不同 iOS 版本 MakerNote 结构的完整字段级解析。
- Apple、Pixel、小米、vivo、OPPO 真机样本矩阵。

## 2. 当前项目基线

当前项目的相关能力如下：

| 项目 | 当前状态 |
|---|---|
| Android 版本 | `minSdk 26`、`targetSdk 35`、`compileSdk 35` |
| UI | Jetpack Compose 单 Activity |
| 状态管理 | `RawViewerViewModel` + `StateFlow` |
| 文件入口 | `ActivityResultContracts.OpenDocument` 单文件选择 |
| 已支持图片 | DNG、RAF、HEIF、HEIC |
| HEIF/HEIC 解码 | 系统解码、API 30+ NDK 解码、libheif fallback |
| EXIF/XMP | 枚举 AndroidX ExifInterface 1.4.2 的所有非空 `TAG_*` 属性 |
| 输入安全 | 私有缓存、200 MiB 输入限制、不可 seek 输入回退 |
| 视频播放 | 尚未引入 Media3，也没有 Live 图播放状态 |

现有 HEIF/HEIC 解码只负责生成静态 Bitmap。实现 Live 图时必须继续保留完整原始文件，不能在完成静态图解码后丢弃可能附加在文件尾部的视频数据。

## 3. 结论与设计原则

Live 图解析必须根据文件内容判断格式，不能根据当前设备的 `Build.MANUFACTURER` 判断。

原因是照片会在 Apple、小米、vivo、OPPO、Pixel 以及其他设备之间传输，打开照片的设备品牌不等于照片的来源品牌。

采用以下整体策略：

1. 使用统一的 `LivePhotoResolver` 调度多个格式适配器。
2. 先解析公开标准，再解析旧标准，最后进入厂商扩展适配器。
3. 每次识别都返回可审计的格式证据和可信度。
4. 对未知或损坏格式安全降级为普通静态照片。
5. 视频提取使用流式复制和文件切片，不把整个文件读入内存。
6. 所有视频数据交给 Media3 解码，不在应用内自行实现视频编解码器。
7. Animated HEIF Sequence 与 Live Photo 分开建模，不把两者视为相同格式。

## 4. 格式差异

| 类型 | 常见文件形态 | 主要识别依据 | 支持策略 |
|---|---|---|---|
| Apple Live Photo | HEIC/JPEG + MOV 两个文件 | 静态图 MakerNote 与 MOV QuickTime Content Identifier | 双文件导入、验证配对、播放 MOV |
| Android Motion Photo 1.0 | 单个 JPEG/HEIC/AVIF，尾部附加 MP4 | XMP Motion Photo 标志与 Container Directory | 按标准计算视频范围并提取 |
| 旧版 Google MicroVideo | 单文件，尾部附加 MP4 | `GCamera:MicroVideoOffset` 等旧 XMP | 保留兼容适配器 |
| 小米动态照片 | 通常为 JPEG 与 MP4 合并文件 | Google 标准字段或小米扩展/SDK | 标准解析优先，小米 SDK 可选 |
| vivo Live Photo | 公开技术规范不完整 | 标准字段与真实样本签名 | 样本驱动适配，未知格式降级 |
| OPPO Live Photo | 公开技术规范不完整 | 标准字段与真实样本签名 | 样本驱动适配，未知格式降级 |

### 4.1 Apple Live Photo

Apple Photos 将 Live Photo 作为静态图片资源和 `pairedVideo` 视频资源管理。Apple 的拍摄接口文档说明，Live Photo MOV 包含 QuickTime Content Identifier，并与静态图 MakerNote 中的标识关联。

参考：

- [PHAssetResourceType.pairedVideo](https://developer.apple.com/documentation/photos/phassetresourcetype/pairedvideo)
- [AVCapturePhotoSettings.livePhotoMovieMetadata](https://developer.apple.com/documentation/avfoundation/avcapturephotosettings/livephotomoviemetadata?language=objc)

因此 Android 端必须同时取得图片和 MOV。用户只选择 HEIC/JPEG 时，如果对应 MOV 没有被文件提供者返回，应用无法恢复动态内容。

### 4.2 Android Motion Photo

Android Motion Photo 1.0 使用单文件容器。静态图片是主资源，视频通常附加在文件尾部，通过 XMP Container Directory 描述各资源的语义、MIME、长度和 padding。

参考：[Android Motion Photo Format 1.0](https://developer.android.com/media/platform/motion-photo-format)

规范同时指出，照片经过编辑后可能保留 Motion Photo XMP 标志，但视频数据已经被删除。因此不能仅依赖 `Camera:MotionPhoto=1`，还必须验证视频范围和实际 MP4 轨道。

### 4.3 小米动态照片

小米官方文档将动态照片描述为 JPEG 与 MP4 合并的特殊单文件结构，并提供 Live Photo SDK。通用解析器应先尝试 Android Motion Photo 标准，小米 SDK 只处理标准解析器无法覆盖的已验证扩展格式。

参考：

- [小米动态照片客户端开发](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2003)
- [小米 Live Photo SDK 版本说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2001)

小米 SDK 的仓库权限、账号授权和依赖冲突必须在集成前确认，不能作为通用构建的强制依赖。

### 4.4 vivo 与 OPPO

厂商产品页面可以确认 vivo、OPPO 已支持 Live Photo 拍摄、声音和第三方分享，但公开开发资料不足以作为可靠的二进制格式规范。

参考：

- [vivo S50](https://www.vivo.com.cn/vivo/s50/)
- [OPPO Find X8 Pro](https://www.oppo.com/cn/smartphones/series-find-x/find-x8-pro/)

因此 vivo 和 OPPO 的扩展解析必须以不同机型、系统版本和编辑流程产生的原始样本为依据，不在缺少样本时猜测视频偏移。

## 5. 总体架构

```text
一个或多个 Uri
        │
        ▼
LivePhotoInputResolver
  ├─ 判断单文件或双文件输入
  ├─ 检查文件长度和 MIME
  ├─ 保留完整原文件
  └─ 不可 seek 时复制到应用私有缓存
        │
        ▼
LivePhotoResolver
  ├─ AppleLivePhotoAdapter
  ├─ AndroidMotionPhotoAdapter
  ├─ LegacyMicroVideoAdapter
  ├─ XiaomiLivePhotoAdapter
  ├─ VivoLivePhotoAdapter
  └─ OppoLivePhotoAdapter
        │
        ▼
LivePhotoAsset
  ├─ 静态图 Uri
  ├─ 独立视频 Uri 或文件切片
  ├─ 展示时间点
  ├─ 视频时长、尺寸和音轨
  ├─ 格式识别证据
  └─ Apple 配对可信度
        │
        ├────────────────┐
        ▼                ▼
现有静态图解码链路      Media3 ExoPlayer
        │                │
        └────────┬───────┘
                 ▼
          Compose Live 图页面
```

建议新增目录：

```text
app/src/main/java/top/hasiy/rawviewer/live/
├── model/
│   ├── LivePhotoAsset.kt
│   ├── LivePhotoKind.kt
│   └── PairingConfidence.kt
├── input/
│   └── LivePhotoInputResolver.kt
├── detect/
│   └── LivePhotoResolver.kt
├── adapter/
│   ├── AppleLivePhotoAdapter.kt
│   ├── AndroidMotionPhotoAdapter.kt
│   ├── LegacyMicroVideoAdapter.kt
│   ├── XiaomiLivePhotoAdapter.kt
│   ├── VivoLivePhotoAdapter.kt
│   └── OppoLivePhotoAdapter.kt
├── parser/
│   ├── XmpPacketReader.kt
│   ├── MotionPhotoXmpParser.kt
│   ├── IsoBmffBoxReader.kt
│   └── Mp4TrackInspector.kt
├── cache/
│   └── LivePhotoCacheManager.kt
├── playback/
│   └── LivePhotoPlayerController.kt
└── ui/
    └── LivePhotoPlayer.kt
```

## 6. 核心数据模型

建议的数据模型如下。正式编码时需按照项目 Kotlin、Compose、ViewModel 和测试规范补充 KDoc、常量定义与线程约束。

```kotlin
enum class LivePhotoKind {
    APPLE_PAIRED,
    ANDROID_MOTION_PHOTO,
    LEGACY_MICRO_VIDEO,
    XIAOMI,
    VIVO,
    OPPO,
}

enum class PairingConfidence {
    VERIFIED,
    PROBABLE,
    UNVERIFIED,
    MISMATCHED,
}

sealed interface LiveVideoSource {
    data class UriSource(val uri: Uri) : LiveVideoSource

    data class FileSlice(
        val sourceUri: Uri,
        val offset: Long,
        val length: Long,
    ) : LiveVideoSource

    data class CacheFile(val file: File) : LiveVideoSource
}

data class LivePhotoAsset(
    val stillUri: Uri,
    val videoSource: LiveVideoSource,
    val kind: LivePhotoKind,
    val presentationTimestampUs: Long?,
    val durationUs: Long?,
    val width: Int?,
    val height: Int?,
    val hasAudio: Boolean?,
    val pairingConfidence: PairingConfidence,
    val detectionEvidence: List<String>,
)
```

适配器接口建议：

```kotlin
interface LivePhotoAdapter {
    suspend fun probe(input: LivePhotoInput): LivePhotoProbeResult

    suspend fun resolve(input: LivePhotoInput): LivePhotoAsset
}
```

`probe` 只读取受限头部、尾部和元数据区域，不做整文件复制。`resolve` 只在确认格式后执行视频切片或独立资源解析。

## 7. 文件选择与输入处理

### 7.1 选择模式

现有 `OpenDocument` 单选入口调整为两个明确场景：

1. “选择照片”：接受一个单文件 Motion Photo、普通图片或现有 RAW/HEIF 文件。
2. “选择 Apple Live Photo”：允许同时选择一个 HEIC/JPEG 与一个 MOV。

也可以采用统一多选入口，但必须处理以下合法数量：

- 一个文件：按单文件格式探测。
- 两个文件：先尝试 Apple 配对，再分别降级处理。
- 两个以上：拒绝自动配对，要求用户缩小选择范围。

Android Photo Picker 支持多选图片和视频，并可在旧系统上回退至 `ACTION_OPEN_DOCUMENT`。参考：[Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker)

### 7.2 URI 与 seek

每个输入先尝试：

1. 读取 `AssetFileDescriptor` 的长度和起始偏移。
2. 使用文件描述符执行随机访问。
3. 如果 provider 不支持 seek，则流式复制到应用私有缓存。

不能把非 seekable `content://` 直接交给需要路径或随机访问的 native/容器解析代码。

`PhotoInputResolver` 当前只允许 `PhotoFormat.entries` 中的后缀，Live 图实现需要新增独立的 `LivePhotoInputResolver`，不要直接扩大现有 RAW 输入解析器的职责。

## 8. 格式探测顺序

固定采用以下顺序：

1. Apple 双文件输入和资源标识配对。
2. Android Motion Photo 1.0 Container XMP。
3. 旧版 Google `MicroVideoOffset`。
4. 小米 SDK 或已验证的小米扩展字段。
5. vivo 已验证样本签名。
6. OPPO 已验证样本签名。
7. 静态图片降级。

每个适配器返回：

- 是否匹配。
- 匹配的字段和容器证据。
- 是否完成严格验证。
- 视频范围或独立视频 Uri。
- 无法播放的明确原因。

不得使用以下逻辑：

```kotlin
if (Build.MANUFACTURER == "Xiaomi") {
    // 按小米格式解析
}
```

## 9. Apple Live Photo 解析

### 9.1 配对依据

按可靠性从高到低使用：

1. MOV QuickTime Content Identifier 与静态图 MakerNote 标识一致。
2. 标识无法读取，但文件来源、基础名称、创建时间和方向一致。
3. 只有基础名称相似时，标记为未验证，不自动播放。

配对结果：

| 状态 | 含义 | 行为 |
|---|---|---|
| `VERIFIED` | 标识一致 | 正常播放 |
| `PROBABLE` | 多项外部属性一致，但私有标识无法读取 | 播放并显示低风险提示 |
| `UNVERIFIED` | 只有文件名等弱证据 | 不自动绑定，要求用户确认 |
| `MISMATCHED` | 标识明确不同 | 拒绝配对 |

### 9.2 元数据解析

- 使用有边界检查的 ISO BMFF box reader 读取 MOV `moov`、`meta`、`keys` 和 `ilst` 等相关 box。
- 不进行整文件 box 扫描或递归无上限解析。
- MakerNote 是厂商私有结构，需要用真实 iPhone 导出样本验证字段位置。
- 在没有正式证据前，不把文件名或拍摄时间当作 Apple 配对协议。

### 9.3 播放

Apple MOV 是独立视频资源，验证后可直接交给 Media3，不需要重新编码。若配对失败，仍展示静态图，并给出“未找到配对视频”或“图片与视频不匹配”的原因。

## 10. Android Motion Photo 解析

### 10.1 当前标准字段

解析：

- `Camera:MotionPhoto`
- `Camera:MotionPhotoVersion`
- `Camera:MotionPhotoPresentationTimestampUs`
- Container Directory
- Container Item 的 MIME、Semantic、Length、Padding

解析后必须验证：

1. Motion Photo 标志开启。
2. Container Directory 中存在视频项。
3. 所有长度、padding 和 offset 使用 `Long` 计算。
4. 加法和减法没有整数溢出。
5. 视频范围完全位于文件内部。
6. 视频起始位置存在合法的 ISO BMFF `ftyp` box。
7. `MediaExtractor` 能发现至少一条视频轨道。

### 10.2 旧版 Google 兼容

兼容以下旧字段：

- `GCamera:MicroVideo`
- `GCamera:MicroVideoVersion`
- `GCamera:MicroVideoOffset`
- `GCamera:MicroVideoPresentationTimestampUs`

旧格式常用 `MicroVideoOffset` 表示从文件末尾回退到视频起点的字节数。它不作为新格式写入依据，但必须保留读取能力。

### 10.3 视频提取

```text
取得 seekable 输入
    ↓
验证 offset 和 length
    ↓
按固定缓冲区复制指定区间
    ↓
写入 app 私有 cache/*.mp4
    ↓
MediaExtractor 验证轨道、时长和尺寸
    ↓
交给 Media3 播放
```

不把整个 Motion Photo 或 MP4 一次性读入 `ByteArray`。

## 11. 小米、vivo、OPPO 适配

### 11.1 小米

两级解析：

1. 先使用 Android Motion Photo 标准及旧版 Google 解析器。
2. 标准失败后，再调用可选的小米 SDK 适配器。

建议将厂商 SDK 隔离为独立 Gradle 模块或 product flavor：

```text
live-core
live-xiaomi-sdk
```

这样即使 SDK 仓库权限缺失或依赖冲突，Apple 和标准 Android Motion Photo 功能仍可构建。

### 11.2 vivo 与 OPPO

首先建立样本矩阵：

- 相机原始 Live Photo。
- 相册编辑、裁切、滤镜后另存。
- 系统分享后保存。
- 微信、QQ 等应用转发后保存。
- 不同 OriginOS、ColorOS 版本。
- JPEG 与 HEIC 静态主图。
- AVC 与 HEVC 视频。
- 有音轨、无音轨、HDR、不同方向。

每个样本只保存隐私安全的分析清单：

```text
SHA-256
文件长度
MIME
XMP namespace 与字段名
MP4 ftyp 位置
视频长度
视频编码
视频与音频轨道数
展示时间戳
预期厂商类型
```

没有匹配已验证签名时返回 `UnsupportedVendorVariant`。不得只因为任意位置出现 `ftyp` 字符串就认定存在 Live 视频。

## 12. 播放器和 Compose 页面

使用 AndroidX Media3 ExoPlayer。集成时从项目依赖策略确认并锁定兼容版本，不直接照抄在线文档中的瞬时最新版。

参考：[Media3 ExoPlayer 入门](https://developer.android.com/media/media3/exoplayer/hello-world)

交互建议：

- 初始显示现有解码链路产生的高质量静态图。
- 检测到 Live 内容后显示 `LIVE` 标记。
- 长按图片或点击 `LIVE` 标记播放。
- 播放开始时从静态图淡入视频。
- 播放结束后恢复静态图。
- 默认不无限循环，可提供重新播放。
- 默认静音，用户可以主动打开声音。
- 页面离开、应用进入后台或 Composable dispose 时停止并释放 Player。
- 同一时间只保留一个活跃 Player。

Apple 的界面建议也倾向使用 Live 标记和直接手势，而不是把 Live Photo 显示成普通视频的大播放按钮。参考：[Apple Live Photos HIG](https://developer.apple.com/design/human-interface-guidelines/live-photos)

建议增加以下 UI 状态：

```kotlin
sealed interface LivePlaybackState {
    data object NotAvailable : LivePlaybackState
    data object Detecting : LivePlaybackState
    data class Ready(val asset: LivePhotoAsset) : LivePlaybackState
    data object Preparing : LivePlaybackState
    data object Playing : LivePlaybackState
    data object Paused : LivePlaybackState
    data class Error(val reason: LivePhotoError) : LivePlaybackState
}
```

`RawViewerUiState` 后续应组合一个独立的 Live 状态，而不是继续扩大 `RawContentState` 对静态解码来源的职责。

## 13. EXIF 与 Live 元数据页面

现有全部 EXIF/XMP 列表继续保留，并新增独立的“Live 信息”区域：

- Live 类型。
- 格式版本。
- 命中的适配器。
- 静态图片 MIME。
- 视频 MIME 与编码。
- 视频尺寸和时长。
- 是否包含音轨。
- 展示时间点。
- 视频数据 offset 和 length。
- Apple 配对可信度。
- 格式检测依据。
- 视频验证结果。

页面可以展示完整 EXIF 值，但日志中不得输出：

- URI 与真实路径。
- 文件名。
- GPS 值。
- 设备序列号。
- 完整 MakerNote。
- EXIF/XMP 属性原值。

`detectionEvidence` 只记录字段存在性、命中的规范类型和数值范围，不保存个人元数据原值。

## 14. 资源和安全限制

建议资源限制：

| 资源 | 建议上限 |
|---|---:|
| 静态照片 | 沿用现有 200 MiB |
| 独立或内嵌视频 | 256 MiB |
| 默认视频时长 | 30 秒 |
| 视频分辨率 | 最大 8K |
| 解析嵌套层级 | 固定有限层级 |
| box/XMP 字段数量 | 设置明确上限 |

强制要求：

- 所有偏移与长度使用 `Long`。
- `offset + length` 必须检查溢出。
- 文件切片必须完全落在实际文件范围内。
- 视频提取只写入应用私有缓存。
- 取消、错误、页面关闭和进程下次启动时清理过期缓存。
- 不对选中的照片进行网络上传。
- 不因为 XMP 声称存在视频就读取未验证范围。
- Media3 只接收已经完成边界验证的视频源。

## 15. 测试方案

### 15.1 单元测试

- Android Motion Photo 1.0 Container XMP。
- 不同 XML namespace 前缀。
- Extended XMP。
- 旧版 `MicroVideoOffset`。
- 空值、负值、越界和整数溢出。
- XMP 存在但视频已被编辑器删除。
- 视频起点没有合法 `ftyp`。
- Apple 标识匹配、不匹配和缺失。
- 相同基础文件名但 Apple 标识不同。
- 图片与视频选择顺序颠倒。
- 未知小米、vivo、OPPO 变体。
- 普通 JPEG、HEIC 不被误判。
- Animated HEIF Sequence 不被误判为 Live Photo。

### 15.2 Instrumented 测试

- 可 seek 与不可 seek 的 `content://` provider。
- 缓存复制和视频切片过程取消。
- 屏幕旋转和 Activity 重建。
- 前后台切换。
- 快速进入和离开播放页面。
- 多张 Live Photo 快速切换。
- 音频焦点丢失。
- Player 与临时文件释放。
- API 26、28、30、33、35。

### 15.3 真机样本

- Pixel 当前版 Android Motion Photo。
- Pixel 旧版 MicroVideo。
- Apple JPEG + MOV。
- Apple HEIC + MOV。
- 小米 HyperOS。
- vivo OriginOS。
- OPPO ColorOS。
- AVC、HEVC、有声、无声、HDR、旋转方向样本。

仓库不提交含个人隐私的原图。测试 fixture 必须脱敏；无法脱敏的样本只在本地保存，仓库记录 SHA-256 与预期解析结果。

## 16. 实施阶段与工期

| 阶段 | 内容 | 预计 |
|---|---|---:|
| P0 | 收集 Apple、Pixel、小米、vivo、OPPO 原始样本并建立分析清单 | 2～3 天 |
| P1 | Live 数据模型、输入缓存、XMP 与 MP4 基础解析 | 2～3 天 |
| P2 | Android Motion Photo 当前标准与旧版兼容 | 2～3 天 |
| P3 | Media3 播放器和 Compose UI | 2～3 天 |
| P4 | Apple 双文件选择、标识解析与配对 | 3～5 天 |
| P5 | 小米 SDK 可选适配 | 2～3 天 |
| P6 | vivo、OPPO 样本驱动适配 | 每家 2～5 天 |
| P7 | 真机测试、安全检查、缓存清理和回归 | 3～5 天 |

Apple 与标准 Android Motion Photo 基线预计需要 8～12 个开发日。

包含小米、vivo、OPPO 已知格式时，预计需要 14～25 个开发日。厂商适配时间取决于能否取得未经聊天软件转码的原始样本，以及小米 SDK 的访问权限。

## 17. 推荐实施顺序

1. 先准备样本与隐私安全的预期结果清单。
2. 实现通用输入、缓存和安全文件切片。
3. 实现 Android Motion Photo 1.0。
4. 实现旧版 Google MicroVideo。
5. 接入 Media3 和 Compose 播放 UI。
6. 实现 Apple 双文件选择与强标识配对。
7. 接入可选的小米 SDK。
8. 根据真实样本实现 vivo、OPPO 适配器。
9. 完成 API 与厂商真机矩阵。

优先完成 Android 标准格式，可以最早建立单文件识别、提取、播放和安全校验的完整闭环。Apple 双文件配对与厂商扩展随后复用同一播放器和状态模型。

## 18. 验收标准

- Android Motion Photo 1.0 可以正确识别并播放。
- 旧版 Google Motion Photo 可以兼容播放。
- Apple HEIC/JPEG 与 MOV 可以通过标识可靠配对。
- Apple 只有静态图时明确提示缺少配对视频。
- 小米标准格式可以播放；SDK 可用时支持已验证的小米扩展格式。
- vivo、OPPO 已验证样本可以播放。
- 未知厂商变体不会崩溃或越界读取，只降级显示静态图。
- 页面同时展示现有全部 EXIF 和新增 Live 元数据。
- 解析过程不会一次性加载整个视频到内存。
- 伪造 XMP、截断文件和越界 offset 不会导致崩溃。
- 页面离开后播放器、文件描述符和缓存正确释放。
- 日志中不存在 URI、文件名、GPS、MakerNote 原文等隐私信息。

## 19. 本阶段不包含

- 创建或重新封装 Apple Live Photo。
- 创建小米、vivo、OPPO 动态照片。
- 把普通视频转换为 Live Photo。
- 视频编辑、裁切、滤镜和音轨编辑。
- 将 Live Photo 写回系统相册的厂商专用接口。
- 云端转码或上传服务。

这些能力应在读取和播放稳定后，作为单独需求评估。
