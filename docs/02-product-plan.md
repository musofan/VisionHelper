# 02 · 产品规划、架构与开发路线图

## 1. 产品定义

**VisionHelper**：佩戴式工作辅助记录工具。用户长期佩戴头显/AI 眼镜工作时，App 持续（或按需）对视野内容做**物体识别 → 加框 → 打标签**，把关键画面连同语音备注存档；提供**语音对话框**进行问答与提醒，提醒遵循"文字气泡先行、TTS 随后播报"协议，避免打断且不漏信息。

### MVP 功能边界（v0.1）

| # | 功能 | 验收标准 |
|---|---|---|
| F1 | 取流与截图 | 相机/外源帧 → 预览 + 手动截图存档（含时间戳） |
| F2 | 物体识别标注 | YOLOv8n ONNX 端侧推理，实时框+标签 overlay，≥5 FPS@640 |
| F3 | 语音指令 | "开始记录 / 停止 / 保存" 离线识别触发（SenseVoice） |
| F4 | 语音对话 | 语音问 → 云 LLM 答 → 文字气泡先显示 → TTS 播报 |
| F5 | 记录库 | 识别快照（图/框/标签/备注）入库 Room，列表回看，可导出 JSON |

v0.2+：SAM2 精细分割、开集云识别（Qwen-VL）、空间锚点（Meta 层）、录屏、唤醒词。

## 2. 技术架构（方案二基座 + Meta 增强层）

```
┌────────────────────────── APK (Kotlin, minSdk 26) ──────────────────────────┐
│  UI 层: Compose                                                              │
│   ├─ 相框模式 Panel（Quest 2D App / Pico / 手机 通用）                        │
│   └─ Meta 增强层（runtime 探测 Spatial SDK → 3D 锚点面板, 可选编译 variant） │
│                                                                              │
│  视觉管线:                                                                   │
│   FrameSource (Camera2 / 外接 RTSP / 手动选图)                                │
│     → YoloDetector (onnxruntime-android, yolov8n.onnx, NMS)                  │
│     → LabelMapper (COCO→业务词表; 可选 Qwen-VL 云端开集兜底)                 │
│     → OverlayRenderer (Compose Canvas 框+标签)                               │
│     → SnapshotStore (JPEG+JSON → Room)                                       │
│                                                                              │
│  语音管线 (sherpa-onnx 全离线):                                              │
│   mic 16k PCM → VoiceActivityDetector → SenseVoice ASR(int8)                 │
│     → 指令路由（本地命令） / LLM 客户端（HTTPS, OpenAI/通义兼容协议）         │
│     → 回复 → ChatBubble(先显示) → VITS TTS(后播报, 可打断)                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

### 关键依赖（已核实可用，注意镜像）

| 用途 | 依赖 | 版本/来源 |
|---|---|---|
| 检测 | `com.microsoft.onnxruntime:onnxruntime-android` | Maven Central |
| 语音 | `com.k2fsa.sherpa:onnx`（sherpa-onnx AAR） | Maven Central / jitpack |
| 模型 | yolov8n.onnx(12.8MB)、SenseVoice int8(163MB)、vits-zh-theresa(<100MB) | GitHub release / hf-mirror.com，首启下载至沙盒 |
| LLM | OpenAI 兼容 REST（可配 base_url → 通义/DeepSeek 等） | 用户自备 key |
| Meta 层（可选） | Meta Spatial SDK（Gradle）、Meta Voice SDK AAR | Meta 官方 maven |

## 3. 路线图

### Phase 0 · 可行性验证（✅ 已完成，本仓库）
- ✅ YOLOv8n ONNX 端侧推理（38.6ms/帧 @Mac CPU，检出 4 物体）
- ✅ SenseVoice 离线中文 ASR（RTF 0.04，转写准确）
- ✅ VITS 离线中文 TTS（RTF 0.74）
- ✅ Android 探针 APK 构建成功（app-debug.apk 2.4MB：Camera2 截图 + 16k PCM 采集 + 权限流程）
- ⬜ **真机验证**：Quest 3（2D 相框模式 & 开发者模式侧载）、Pico 4 各装一次探针 APK；确认 MediaProjection 录屏可行性（唯一未解硬问题）

### Phase 1 · MVP 骨架（1.5~2 周）
1. 正式 App 结构（替换 probe）：Compose UI + Hilt + Room
2. FrameSource 抽象：Camera2 实现 + 手动选图；YoloDetector 接入 onnxruntime-android（复用本仓库 Python 验证的后处理逻辑移植到 Kotlin）
3. Overlay 渲染 + F2 验收（真机 5FPS）
4. SnapshotStore + F5 记录库

### Phase 2 · 语音闭环（1~2 周）
5. sherpa-onnx AAR 集成 + 模型首启下载器（断点续传，走 hf-mirror 兜底）
6. ASR 指令路由（F3）+ 云 LLM 客户端（OpenAI 兼容协议、流式）（F4 前半）
7. "先文字后播报"会话协议（气泡状态机：thinking→text→speaking，TTS 可打断）+ VITS（F4 验收）

### Phase 3 · Meta 增强与打磨（2 周）
8. Quest 3 侧载调优（性能/发热/续航）；Spatial SDK variant：3D 面板 + 空间锚点
9. 录屏（MediaProjection）+ 快照分享
10. 外接输入源 RTSP（如用户有外部相机）；开集云识别兜底

### 里程碑验收即 MVP 功能表 F1~F5。

## 4. 风险登记

| 风险 | 等级 | 对策 |
|---|---|---|
| 透视帧无法获取（两方案共同） | 高 | 输入源三备选：外接相机 RTSP / 手机取流 / 手动快照；Phase 0 真机验证 |
| Quest 2D App 相框性能/限制 | 中 | 真机实测；必要时出 OpenXR variant |
| 头显发热限制端侧推理占空比 | 中 | 推理节流（如 2 FPS 检测 + 事件触发加密）；NPU delegate 尝试 |
| 录屏受系统限制 | 中 | 逐台验证；不可用则退化为截图流 |
| 模型体积与首启下载 | 低 | int8 量化已选；镜像下载器 |
| 云 LLM 账号/费用 | 低 | 协议层多供应商可切换 |
