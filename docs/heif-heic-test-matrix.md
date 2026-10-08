# HEIF / HEIC 验收矩阵

## 自动化覆盖

| 项目 | 覆盖方式 | 当前状态 |
|---|---|---|
| HEIF/HEIC MIME 与扩展名识别 | `PhotoFormatDetectorTest` | 已实现 |
| 4096 px 等比缩放计算 | `ImageSizeCalculatorTest` | 已实现 |
| 三 ABI 原生编译与链接 | `assembleDebug` | 已实现 |
| Android Lint | `lintDebug` | 已实现 |

## 真机样本矩阵

仓库不内置用户照片。发布前须使用无隐私或已脱敏样本，在对应系统版本逐项执行：

| 样本 | API 26–27 | API 28–29 | API 30+ | 预期 |
|---|---|---|---|---|
| iPhone 8-bit HEIC，带旋转与 GPS | BitmapFactory | ImageDecoder | ImageDecoder + NDK 对照 | 方向一致；EXIF/GPS 可见 |
| iPhone 10-bit HEIC | BitmapFactory SDR 回退 | ImageDecoder（依系统能力） | 系统、NDK、libheif | 可显示；API 34+ 的 libheif 路径保留为 `RGBA_F16`，旧系统明确标记 SDR 回退 |
| Android HEIC | BitmapFactory | ImageDecoder | 三路径对照 | 尺寸与方向一致 |
| `.heif` 扩展名 | BitmapFactory | ImageDecoder | 三路径对照 | 格式识别正确 |
| 无 EXIF 文件 | 各系统路径 | 各系统路径 | 各系统路径 | 显示 0 项，不影响解码 |
| 损坏/截断文件 | 系统失败 | 系统失败 | 系统失败 | 自动模式 fallback，最终给出错误 |
| 超过 200 MiB 文件 | 拒绝 | 拒绝 | 拒绝 | 不进入原生解码 |
| 超过 32768 px 或 1.6 亿像素 | 拒绝 | 拒绝 | 拒绝 | 不分配超限 Bitmap |
| libheif 8-bit 源图超过 96 MP 或 F16 源图超过 64 MP | 软件路径拒绝 | 软件路径拒绝 | 软件路径拒绝，自动模式尝试系统解码 | 在整图 RGBA 分配前拒绝；保留 HEIF 元数据查看能力 |

还需比较峰值内存、首帧时间、色彩、HDR tone mapping 与厂商相册显示。真机结果应记录
设备型号、Android 版本、样本来源、解码路径、像素尺寸、EXIF 条目数和失败日志类别；
不得在日志中记录 URI、文件名、GPS 或 EXIF 值。
