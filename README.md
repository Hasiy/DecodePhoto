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
- **HDR / SDR 显示对比**：通过独立页面切换默认、HDR 和 SDR 三种显示模式，并展示图片与当前屏幕的 HDR 能力信息。
- **Live Photo 与 Motion Photo**：识别动态照片中的静态图与视频内容，支持播放、停止和静音控制。
- **EXIF / XMP 查看**：展示照片中可读取的拍摄信息、方向、厂商信息和动态照片元数据。
- **RGB 直方图**：对当前照片的红、绿、蓝通道分布进行可视化分析。
- **RGB ↔ HSB/HSV 转换**：提供独立颜色工具页，支持双向换算、输入校验和颜色预览。
- **本地文件与隐私保护**：通过 Android 系统文件选择器读取照片，不上传图片，并避免在日志中记录文件名、路径和定位信息。

## 详细文档

- [项目实现、模块与构建说明](docs/project-implementation.md)
- [HDR / SDR 完整处理与后续实施方案](docs/hdr-sdr-complete-implementation.md)
- [Live Photo / Motion Photo 实现说明](docs/live-photo-motion-photo-implementation.md)
- [HEIF / HEIC 测试矩阵](docs/heif-heic-test-matrix.md)
- [DNG / RAF 解码方案](android-dng-raf-decoding-guide.md)
- [第三方组件、版本与许可证](THIRD_PARTY_NOTICES.md)
