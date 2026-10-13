# DecodePhoto

DecodePhoto 是一款面向 Android 的本地照片解析与显示实验应用，聚焦 RAW、
HEIF/HEIC、HDR、Live Photo 与 Motion Photo 等格式的查看、对比和元数据分析。

应用支持 Android 8.0（API 26）及以上版本，照片处理均在设备本地完成。

## 支持格式

| 类型 | 格式 |
|---|---|
| RAW 照片 | DNG、RAF |
| 普通照片 | JPEG |
| 高效率图片 | HEIF、HEIC、AVIF |
| 动态照片 | Apple Live Photo、Android Motion Photo 及部分厂商 Live 图变体 |

## 项目功能

- **多路径照片解码**：针对 RAW、HEIF 和 HEIC 提供不同解码方式，可在应用中查看并比较结果。
- **RAW 渐进显示**：优先展示可用的内嵌预览，再更新为完整 RAW 显影结果。
- **自动显示与失败回退**：选图后自动解码，元数据与直方图后台补齐；完整显影失败或取消时保留已有预览。
- **全屏与手势查看**：支持双指缩放、拖动、双击放大和按钮缩放，检查当前解码结果的细节。
- **解码 A/B 对比**：内嵌预览与 LibRaw、系统与 libheif 共用缩放和拖动，通过滑杆比较 SDR 结果。
- **RAW 显影参数**：曝光补偿、相机/自动/日光白平衡、半尺寸/完整尺寸处理，短暂防抖后更新预览。
- **导出与分享**：将当前解码结果导出为 JPEG/PNG，选择尺寸和 JPEG 质量；JPEG 可移除 GPS，HDR 转为 SDR 输出。
- **最近打开与恢复**：保存最近十组照片及配对视频、解码方式和 RAW 参数，文件标识使用 Android Keystore 加密。
- **HDR / SDR 显示对比**：通过独立页面切换默认、HDR 和 SDR 三种显示模式，并展示图片与当前屏幕的 HDR 能力信息。
- **Live Photo 与 Motion Photo**：识别动态照片中的静态图与视频内容，支持播放、停止和静音控制。
- **EXIF / XMP 查看**：展示照片中可读取的拍摄信息、方向、厂商信息和动态照片元数据。
- **元数据工具**：搜索标签或内容、复制单个字段，导出完整 JSON/文本报告。
- **动态照片导出**：提取原始视频，选择视频位置保存 JPEG 关键帧，并展示视频传递函数和系统解码能力。
- **HDR 分析**：Gain Map 可视化与源像素线性亮度直方图；BT.2020 PQ/HLG 使用明确的 CPU SDR 色调映射。
- **RGB 直方图**：对当前照片的红、绿、蓝通道分布进行可视化分析。
- **RGB ↔ HSB/HSV 转换**：提供独立颜色工具页，支持双向换算、输入校验和颜色预览。
- **本地文件与隐私保护**：通过 Android 系统文件选择器读取照片，不上传图片，并避免在日志中记录文件名、路径和定位信息。

## 详细文档

- [项目实现、模块与构建说明](docs/project-implementation.md)
- [查看器增强功能、回归样本与验证](docs/photo-viewer-enhancements.md)
- [HDR / SDR 完整处理与后续实施方案](docs/hdr-sdr-complete-implementation.md)
- [Live Photo / Motion Photo 实现说明](docs/live-photo-motion-photo-implementation.md)
- [HEIF / HEIC 测试矩阵](docs/heif-heic-test-matrix.md)
- [DNG / RAF 解码方案](android-dng-raf-decoding-guide.md)
- [第三方组件、版本与许可证](THIRD_PARTY_NOTICES.md)
