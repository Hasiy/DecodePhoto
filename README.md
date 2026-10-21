# DecodePhoto

DecodePhoto 是一款面向 Android 的本地照片解析与显示实验应用，聚焦 RAW、
HEIF/HEIC、HDR、Live Photo 与 Motion Photo 等格式的查看、对比和元数据分析。

应用支持 Android 8.0（API 26）及以上版本，照片处理均在设备本地完成。

## 下载与安装

从 [GitHub Releases](https://github.com/Hasiy/DecodePhoto/releases) 下载 APK 和对应的
`SHA256SUMS.txt`。当前应用版本为 **1.0**，应用 ID 为 `top.hasiy.rawviewer`。

- 安装包覆盖 `arm64-v8a`、`armeabi-v7a`、`x86_64`，通常无需自行选择 CPU 版本。
- 首个发布包为 **Debug 实验预览版**，使用 Android 调试签名，不是正式签名的生产版本。
- 在 Android 上打开 APK，按系统提示允许当前下载来源安装应用；安装后从系统文件选择器打开照片。
- 若已安装版本的签名不同，Android 会拒绝覆盖安装；卸载会清除本应用的最近记录和设置。

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

## 快速使用

1. **查看照片**：在首页选择 DNG、RAF、JPEG、HEIF/HEIC 或 AVIF，应用自动解码。
   RAW 如有内嵌预览会先显示，再更新为完整显影结果；可切换解码方式比较兼容性。
2. **检查细节**：打开全屏查看进行缩放、拖动，或在解码 A/B 页面用滑杆比较两条路径。
   RAW 可调整曝光（−3～+3 EV）、白平衡和处理尺寸。
3. **查看元数据**：进入 EXIF 详情搜索、复制字段，或导出 JSON/文本报告；HEIF 另有容器详情页。
4. **分析 HDR**：从独立 HDR / SDR 对比入口选择图片，查看显示模式、色彩空间、Gain Map
   和线性亮度分布。再次进入该页面时恢复上一张图片。
5. **播放动态照片**：Android Motion Photo 可直接选择原图；Apple Live Photo 需同时选择
   配对的 JPEG/HEIC 与 MOV/MP4。识别成功后可播放视频、导出原视频或保存选定位置的帧。
6. **保存结果**：导出当前解码图片为 JPEG/PNG，选择输出尺寸和质量；也可通过系统分享菜单交给其他应用。

JPEG 导出默认移除 GPS；完整元数据报告仍可能包含 GPS、拍摄时间等原始信息，分享前请自行检查。
导出的是当前解码结果，不会恢复为传感器原始数据，也不会修改源照片。HDR 图片导出为 SDR。

## 兼容性与验证范围

- Android 8.0 是最低安装要求，不代表每种格式都能在每台设备上解码；AVIF、HDR 显示和视频播放依赖系统、屏幕及编解码器能力。
- 主预览按内存策略限制最长边为 2048/4096 像素，A/B 与 HDR 对比为 2048 像素；放大显示不会增加解码分辨率。
- RAW 完整显影输出为 8-bit sRGB；A/B 比较使用 SDR 结果。HDR 对比页的 SDR HEIF 优先保留系统识别的 ICC/Display P3 色域。
- 亮度直方图反映源图参考亮度，不是屏幕实测亮度；系统截图也不能证明 HDR 屏幕效果。
- 2026-10-13 的回归记录：50 项单元测试、22 项 OPPO PLJ110（Android 17 / API 37）真机测试通过。
  包含固定 HEIF、合成 DNG/HDR/视频、导出及缓存并发测试，详见[验证说明](docs/photo-viewer-enhancements.md)。
- 真实压缩 RAF、10/12-bit PQ/HLG HEIF、多厂商动态照片及旧 Android 设备仍需更完整的样本验证。
  系统保存对话框后的实际文件尚未完成手动验收。

## 详细文档

- [项目实现、模块与构建说明](docs/project-implementation.md)
- [查看器增强功能、回归样本与验证](docs/photo-viewer-enhancements.md)
- [HDR / SDR 完整处理与后续实施方案](docs/hdr-sdr-complete-implementation.md)
- [Live Photo / Motion Photo 实现说明](docs/live-photo-motion-photo-implementation.md)
- [HEIF / HEIC 测试矩阵](docs/heif-heic-test-matrix.md)
- [DNG / RAF 解码方案](android-dng-raf-decoding-guide.md)
- [第三方组件、版本与许可证](THIRD_PARTY_NOTICES.md)
