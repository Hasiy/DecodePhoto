# DecodePhoto 查看器增强

实施日期：2026-10-13。功能在本地工作区实现，未提交或发布。

## 查看与处理

选图后直接开始自动解码，EXIF、ProRAW、HEIF 容器及动态照片检测独立补齐。图片先显示，
RGB 直方图随后计算。RAW 的完整显影失败或取消时保留可用预览并展示具体错误。

全屏页面支持双指缩放、拖动、双击放大、放大/缩小/重置按钮。放大针对当前解码结果，
不能把内嵌预览或 4096 px 输出恢复成传感器原图。A/B 页支持选择两条解码路径，使用同一
变换和分隔滑杆显示 SDR 对照，避免一个 Window 的 HDR 状态影响两侧比较。

RAW 参数包括 −3 至 +3 EV、相机/自动/日光白平衡、半尺寸/完整尺寸 demosaicing。
参数调整防抖 600 ms 后重新显影，也可点击应用。LibRaw 的原有自动亮度仍保留，EV 是
相对该基线的补偿。完整尺寸选项影响显影内部计算；最终预览仍受输出尺寸及内存安全限制。

## 导出与元数据

照片导出与分享使用当前显示结果，最长边 256–4096 px、JPEG 质量 1–100。缩小不会
放大原有预览。HDR 导出使用 SDR：Ultra HDR 使用基础图，PQ/HLG 使用 CPU 映射。
JPEG 透明区域合成为白色，PNG 保留透明通道。JPEG 仅写回允许的相机/拍摄字段，
方向归一为正常；默认移除 GPS，不复制可能隐藏定位信息的 XMP/MakerNote。PNG 不写 EXIF。

元数据页面搜索标签或值，支持复制单个字段，JSON/文本导出包含全部可读标签，
包括原有 GPS/XMP。用户通过系统保存对话框选择目标；分享使用只暴露 exports 子目录的
FileProvider，只授予临时读权限。分享编码文件保留最多一天，在后续导出时清理。

动态照片可提取已验证的视频容器，或选择时间位置保存 JPEG 关键帧。关键帧由系统
MediaMetadataRetriever 解码，HDR 视频帧的实际 SDR 映射取决于系统。视频文件在播放/
导出期间持有引用，切换照片不会提前删除。

## 会话与资源

最近十组记录保存 still/video URI、时间、格式、解码模式和 RAW 参数。JSON 使用
Keystore AES-GCM 加密后写入私有 SharedPreferences，备份关闭。不保存明文 URI，
不在日志输出文件名、EXIF 或 GPS。启动恢复上一组选图及页面；权限失效时提示重新选择。
HDR 对比页独立加密保存上一张照片，并恢复显示模式；仅在首次进入该页时恢复照片，
首页启动不读取或解码隐藏的 HDR 会话。清除最近记录不删除原始照片。

输入租约共享 HEIF 检查与解码缓存，空闲缓存最多两份；重新选择同一 URI 时失效旧缓存，
已运行的 native 任务仍可持有旧文件直到完成。取得缓存条目与增加引用计数在同一锁内，
新条目带有效引用发布，避免失效或空闲淘汰删除正在取得的文件。复制循环检查取消并限制 200 MiB。
主预览最长边依设备为 2048/4096 px；A/B、HDR 页为 2048 px。
LibRaw 在 Android Bitmap 分配之前做面积采样缩小，但 LibRaw 内部 RAW/显影缓存仍可能
较大。LibRaw 与 libheif 共用主要像素解码锁。高分辨率真实相机样本仍需峰值内存验收。

## HDR 数学与边界

BT.2020 PQ 采用 ST 2084 EOTF，HLG 采用 BT.2100 inverse OETF 与固定 1000 nit、gamma 1.2
参考 OOTF；相对 SDR 白点为 203 nit。亮度在 0.5 倍 SDR 白点以下保持线性，之后使用
连续指数 shoulder 压缩高光，按亮度同比缩放 RGB，再从线性 BT.2020 转为 sRGB并夹紧色域。
该曲线是本项目明确、可测试的基线，不宣称为相机厂商或专业编辑软件的匹配算法。

API 26–33 使用临时编码 F16 Bitmap 后 CPU 映射；API 34+ 可保留 HDR 色彩空间，
也可强制映射到 SDR。非 BT.2020 的 HDR 库路径明确拒绝，系统 fallback 仍依平台能力。
源像素使用 native 逐行浮点读取，避免 API 29 getColor 依赖和全图像素数组。
HDR 对比页的 SDR HEIF 优先系统解码，以保留系统识别的原始 ICC/Display P3 色域；
PQ/HLG HEIF 优先高精度库路径，两条路径失败时均可回退到另一条。

亮度直方图最多约 65536 次取样，64 个 bin 覆盖相对 SDR 白点 −8 至 +8 EV。
Ultra HDR 使用完整 Gain Map 比率（W=1）重建亮度；结果是源图参考亮度，不是屏幕
实时 headroom、绝对实测亮度或最终 GPU 渲染读回。Gain Map 另提供有界尺寸的灰度预览。
视频探测读取传递函数并查询系统解码器，支持时联动 HDR Window；编码支持不等于
厂商实际 HDR 亮度验收。截图会受系统 SDR 映射影响，不能作为 HDR 亮度证明。

## 回归样本

- JPEG：合成旋转图、截断数据、GPS 元数据与导出方向。
- HEIF：复用已随 libheif 源码提供的 rainbow-451x461.heic、with-alpha-512x512.heic；
  只打包进测试 APK，检查 native 缩放及透明通道。
- DNG：生成固定 768×512 Bayer TIFF/DNG，检查输出尺寸和曝光；另以带标准 EXIF JPEG
  缩略图但扩展名误标为 DNG 的文件，检查完整 RAW 解码被拒绝时 ViewModel 保留预览。
- HDR：固定 PQ 数值、HLG transfer 数值、合成 F16 PQ 像素、合成 Gain Map。
- 视频：生成固定两秒 AVC MP4，检查原容器复制、关键帧解码。
- Compose：搜索、缩放按钮、A/B 解码选择、首页入口及元数据导航。
- 会话与缓存：AES-GCM 恢复、共享输入和退役租约生命周期。
- Review 回归：并发失效/空闲淘汰时租约文件可读；隐藏 HDR 会话延迟到页面打开且仅恢复一次；
  修改固定 HEIF 的 ICC XYZ primary tags 为 Display P3，检查原始色域及像素与系统路径一致。

合成 DNG 不代表真实相机 DNG；真实压缩 RAF、10/12-bit HLG/PQ HEIF、Apple MOV 配对及
多厂商动态照片、旧 Android API 真机颜色表现仍需要实际样本矩阵。

## 验证命令

```powershell
.\gradle-local.ps1 testDebugUnitTest lintDebug assembleDebug :app:connectedDebugAndroidTest --console=plain
```

设备：OPPO PLJ110，Android 17 / API 37。

2026-10-13 验证结果：

- `testDebugUnitTest`：50 项通过，0 failure/error。
- `connectedDebugAndroidTest`：22 项通过，0 failure/skipped，包含 DNG 显影、RAW 回退、
  固定 HEIF、透明度、旋转、HDR 数值、视频、Compose 页面，以及上述 3 项 Review 回归。
- `lintDebug`：0 error；app 保留 26 项 warning、3 项 hint（含目标 SDK、资源/样式等提示）。
- `assembleDebug`：arm64-v8a、armeabi-v7a、x86_64 native 构建成功。
- 最终预览布局调整另执行单元测试、Lint、Debug 构建与安装；固定 HEIF 的自动解码、
  全屏缩放、系统/库 A/B 对比已在真机手动验收。
- 导出编码、GPS 移除、元数据和视频导出已通过仪器测试；系统保存对话框已打开，
  但本次尚未手动确认保存后的文件，不能视为完整 SAF 保存流程验收。

Android 17 最初触发旧 Espresso 的 InputManager 反射兼容失败，测试依赖升级为 AndroidX
Test runner/core 1.7.0、JUnit extension 1.3.0、Espresso 3.7.0 后通过。此更新只影响测试。
测试安装期间 OPPO 显示安装及应用启动提示，仅允许本次 DecodePhoto 测试操作。

## 参考

- [Android Gainmap 数学](https://developer.android.com/reference/android/graphics/Gainmap)
- [ITU BT.2390 HDR 参考](https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2390-4-2018-PDF-E.pdf)
- [AndroidX Test 发布记录](https://developer.android.com/jetpack/androidx/releases/test)
