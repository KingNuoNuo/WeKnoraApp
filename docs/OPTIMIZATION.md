# 1.1.31 体积及性能优化记录

日期：2026-11-03。基准为本机已发布的 1.1.30 Release APK；单位 MiB = 1,048,576 字节。

| 指标 | 1.1.30 | 1.1.31 |
| --- | ---: | ---: |
| APK 字节数 | 71,907,630 | 18,422,825 |
| APK MiB | 68.58 | 17.57 |
| DEX 字节数（未压缩） | 57,672,912 | 4,972,816 |

APK 减少 74.38%。保留原有四种 CPU 架构、PDF 阅读/批注/OCR 依赖；没有通过移除功能获得上述体积结果。

## 代码调整

- Release 开启 R8 代码优化、混淆和资源裁剪，使用 Android 默认优化配置。参见 [Android 官方文档](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)。
- 保留两个自定义 PDF Fragment 的类名和无参构造，供 FragmentManager 重建。仅抑制 PDFBox 未安装的可选 JP2Decoder 声明；PDFBox 在本项目仅用于书签读取。
- 目录使用 ListView 复用可见行，展开时重算可见索引，不再一次创建所有可见章节控件。
- 读取目录直接复用应用私有缓存文件；最近 8 个目录结果使用有界 LRU 内存缓存。
- 复用图片请求对象，连接切换导致图片加载器替换时关闭旧实例。
- PPTX 按幻灯片逐页构建 HTML，不再同时保存所有 XML DOM；修复 slide1/slide2/slide10 数字排序。

## 验证与边界

- 27 项单元测试通过，包含新增 PPTX 数字排序及文本转义回归。
- Release 构建及 APK 签名校验通过。混淆映射保存在 app/build/outputs/mapping/release/mapping.txt，发布时应保留对应文件以便还原崩溃栈。
- 没有测量 Android 15/16 真机的启动时间、峰值内存或帧率，也未完成裁剪版 UI 功能实测。以上性能调整是减少重复 I/O 和控件/解析对象分配，不代表已有量化的速度提升结果。
