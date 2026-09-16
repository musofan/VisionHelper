# 01 · 系统调研：Meta 官方体系 vs 跨平台开放方案

> 调研时间：2026-09 · 信息来源：Meta for Developers 官方文档、meta-quest / oculus-samples GitHub、k2-fsa (sherpa-onnx)、Ultralytics、Meta AI (SAM 3)、PICO 开发者平台

## 0. 设备与系统前提

| 项 | 事实 | 对本产品的影响 |
|---|---|---|
| Quest 3 系统本质 | 定制 Android（HorizonOS），可开启开发者模式侧载普通 APK | 普通 Android APK（非 OpenXR 应用）可安装运行，但**拿不到透视摄像头**，只能靠相框内的 2D 视窗 + 手柄/语音 |
| Quest 3 摄像头 | 4 个透视相机仅开放给 **HorizonOS 签名的系统应用 / 受限的 Passthrough 权限**，第三方 App 无法直接读原始透视帧 | 方案一也**不能**直接"看到你看到的画面"；共识：识别输入需要外部相机或用户手动截图 |
| Pico 4 / AI 眼镜 | Pico OS 同为 Android 系，开发者模式可侧载 APK；PICO 交互 SDK 提供 Unity 接入 | 通用 Android APK 可跨设备安装（方案二的核心诉求） |
| APK 形态 2D App 在 VR 头显中的可用性 | Quest 支持"相框（Panel）"中运行普通安卓 App（2D App on Quest） | 2D APK + 语音是最低成本可落地形态 |

## 1. 方案一：基于 Meta 官方体系

### 1.1 可直接调用的官方能力清单（已核实）

| 能力 | 官方组件 | 接入方式 | 与本产品的匹配点 | 限制 |
|---|---|---|---|---|
| 3D/透视场景渲染 | **Meta Spatial SDK**（Kotlin 原生） | Gradle 依赖，纯 Kotlin/Android 项目 | 用 Kotlin APK 直接构建 3D 界面，把识别结果标在空间中 | 仅 Meta 设备 |
| 透视（Passthrough） | OpenXR `XR_FB_passthrough`、`XR_META_environment_depth`（遮挡） | OpenXR loader (C) 或 Spatial SDK 封装 | 展示层可直接叠在真实世界上 | 不能导出透视原始帧 |
| 场景理解 | `XR_FB_scene_capture` / `XR_FB_scene`（Scene API） | OpenXR | 官方已做"家具级"物体检测+网格，可作识别结果的锚点 | 检测结果**无语义标签 API**，仅几何 |
| 空间锚点 | `XR_FB_spatial_entity` 系列 | OpenXR | "长期佩戴记录"需要把记录钉在物体位置 | 仅 Meta 设备 |
| 语音输入 | **Meta Voice SDK（Wit.ai 集成）**：`wit-services`、语音 SDK Android 库 | Gradle（官方 android voice-sdk AAR） | 免费语音识别 + 意图（intent）+ 实体抽取，支持自训练 NLU | wit.ai 需联网；2025 年后 Meta 收紧了服务政策，商用需走 Meta 平台审核 |
| 语音合成 | 无官方离线 TTS（系统 TTS 可用） | Android `TextToSpeech` | 够用但音质一般 | — |
| AI 助手 | Meta AI（系统级） | 不可编程 | — | 无法嵌入自己 App |
| 录屏/截图 | 系统 MediaProjection 在 HorizonOS 上受限 | — | 需真机验证，可能需系统签名 | 高风险项 |
| SAM 3 / Segment Anything | Meta AI 开源权重（`facebook/sam3` 等，2025-12 发布，支持**文本 prompt 分割**） | PyTorch 权重 → 需自转 ONNX | "加框打标签"的理想模型；但官方未提供端侧推理包 | 需自建导出管线 |

### 1.2 方案一结论

- **可复用**：Spatial SDK（界面）、Scene API/锚点（空间记录）、Passthrough（展示）、Voice SDK（ASR）。
- **不能解决**：透视帧获取（识别输入源）、通用物体语义标签、跨设备。
- **隐性成本**：必须注册 Meta 开发者账号 + 设备签名 + （上商店需审核）；wit.ai 服务条款变动风险；版本被 Meta 锁死。

## 2. 方案二：跨平台 / 不依赖 Meta 生态

### 2.1 识别与"加框打标签"

| 候选 | 体积 | 端侧/云 | 加框 | 任意类别标签（开集） | 移动部署 |
|---|---|---|---|---|---|
| **YOLOv8n/YOLO11n (Ultralytics, ONNX)** | 12.8MB(n) | 端侧 CPU/NPU | ✅ 框+置信度 | ❌ 仅 COCO 80 类（可自定义训练） | ✅ onnxruntime AAR，本机实测 38.6ms/帧 |
| **RT-DETR / RF-DETR (ONNX)** | ~40MB | 端侧 | ✅ | ❌ 闭合集 | ✅ 同上 |
| **Grounding DINO (开集检测)** | >700MB | 云/工作站 | ✅ | ✅ 文本类别 prompt | ❌ 端侧不可行 → 走云端 API |
| **SAM 2 / SAM 3** | SAM2-small ~180MB | 端侧勉强（分割 mask，非类别） | mask | ✅（SAM3 文本 prompt） | SAM2-tiny 可试；SAM3 端侧不现实 |
| **MobileSAM / FastSAM / EfficientSAM** | 10~40MB | 端侧 | mask | ❌（无类别头，需配合检测器） | ✅ 官方支持 ONNX 导出（已核实） |
| **云 API（OpenAI vision / 阿里通义 Qwen-VL / 百度）** | 0 | 云 | 由模型返回结构化框+标签 | ✅ | ✅ 任意 Android |
| **MediaPipe Object Detector (Google)** | ~8MB | 端侧 | ✅ | ❌ 闭合集 | ✅ Android 原生最顺 |

**推荐组合（端侧为主）**：YOLOv8n ONNX（框）+ 自定义类别标签（领域词表映射）→ 后期叠加 MobileSAM 做精细 mask。**云增强**：难例/开集需求走 Qwen-VL / GPT-4o-mini vision API 返回 JSON（框+标签+描述）。

### 2.2 语音交互（ASR → LLM → TTS）

| 环节 | 离线方案（本机已验证 ✅） | 云方案 |
|---|---|---|
| ASR | **sherpa-onnx + SenseVoice-Small int8**（163MB，zh/en/ja/ko/yue，带标点，RTF≈0.04）✅ 实测转写准确 |wit.ai / OpenAI Realtime / 阿里 Paraformer 实时 API |
| LLM | 端侧小模型（llama.cpp/Qwen2.5-0.5~1.5B，头显上勉强） | OpenAI / 通义 / DeepSeek API（推荐） |
| TTS | **sherpa-onnx + VITS zh (hf-theresa 等)**（<100MB，RTF≈0.74）✅ 实测可合成中文 | OpenAI TTS / 阿里 CosyVoice |
| 唤醒词 | sherpa-onnx KWS（可选） | 云端不可用 |

**关键事实（已核实）**：sherpa-onnx 官方提供 **Android AAR**（`com.k2fsa.sherpa:onnx`，Maven Central），SenseVoice/VITS 模型可直接打进 assets 或下载到 App 沙盒，完全离线、无账号。

### 2.3 方案二结论

全部核心链路（检测框标签 / 离线中文 ASR / 离线中文 TTS / 云 LLM）都有**可落地的非 Meta 依赖**，且每条链路本机已跑通（见 03 报告）。跨设备（Quest 3 2D 相框模式、Pico 4、甚至安卓手机）共享同一 APK。

## 3. 两方案对比总表

| 维度 | 方案一（Meta 官方） | 方案二（跨平台） |
|---|---|---|
| 透视画面输入 | ❌ 拿不到原始帧 | ❌ 同样拿不到（设备限制，非生态限制） |
| 物体识别+框+标签 | 无现成 API，需自接模型 | ✅ YOLOv8n ONNX / MediaPipe / 云 VL API |
| 语音 ASR | ✅ Voice SDK/wit.ai（联网、绑 Meta） | ✅ SenseVoice 离线（本机实测 RTF 0.04） |
| 语音 TTS | 系统 TTS，一般 | ✅ VITS 离线（实测 RTF 0.74） |
| LLM 对话 | 需自接第三方 API（一样要账号） | ✅ 同左（完全一致） |
| 空间锚定/3D 标注 | ✅ Spatial SDK + Scene API（独家优势） | ❌ 只有 2D 相框 overlay |
| 截图/录屏 | 受限（MediaProjection 需验证） | 受限（同左） |
| Pico 等其他设备 | ❌ | ✅ |
| 审核/签名成本 | 开发者账号 + 设备签名 | 无 |
| 供应链风险 | wit.ai 政策、HorizonOS 版本 | 开源自主可控 |

## 4. 最终选型建议（详见 02 文档）

**分层架构，方案二为基座，方案一为可选增强层**：

1. **Core（跨设备通用 APK）**：Kotlin + Camera2（外接相机/手机后摄；Quest 上为演示输入源）→ onnxruntime(YOLOv8n) → 2D overlay 框+标签。
2. **Voice（跨设备通用）**：sherpa-onnx ASR/TTS 离线 + 云 LLM 对话；"先文字后播报"由 UI 协议保证（气泡先行，TTS 延迟触发）。
3. **Meta 增强层（运行时探测 `XR_FB_passthrough`/HorizonOS）**：在 Quest 上通过 Spatial SDK 提供空间锚点与 3D 面板；不在 Meta 设备则自动降级为 2D。
4. **记录层**：识别快照（图+框+标签+时间+语音备注）本地 Room 数据库，可导出；录屏用 MediaProjection（设备逐台验证）。

风险第一优先级：**透视帧不可获取** → 识别输入源需在真机上确认（外接摄像头 / 手机热点取流 / 用户主动拍照三备选），这是进入开发前唯一需要真机验证的硬问题。
