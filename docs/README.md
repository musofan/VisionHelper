# VisionHelper — 调研与产品规划文档

> 基于 Meta Quest 3 的 VR 眼镜辅助记录工具：物体识别与标记 + AI 语音交互
> 文档版本：v1.0（2026-09-16）· 配套可行性验证见 `docs/03-feasibility-report.md`

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-research.md](01-research.md) | 两套方案的系统调研与对比（Meta 官方体系 vs 跨平台开放方案） |
| [02-product-plan.md](02-product-plan.md) | 产品定义、技术选型结论、架构设计与开发路线图 |
| [03-feasibility-report.md](03-feasibility-report.md) | 本机已跑通的 4 项可行性验证结果（含数据） |
| [04-v0.1-usage.md](04-v0.1-usage.md) | **v0.1 体验版**：装进 Quest 的步骤、界面操作、已知限制与排查 |

## 一句话结论

**推荐"方案二为主 + 方案一为适配层"的混合路线**：以纯 Android/Kotlin APK + ONNX Runtime 端侧推理 + sherpa-onnx 离线语音为基座（可跑 Quest 3 也可以跑 Pico 4 / AI 眼镜），通过条件依赖（runtime 检测）在 Meta 设备上叠加 Passthrough / 场景 understanding 等官方能力。全部核心链路已在本机完成可行性验证。

## 核心目标（用户需求原文）

1. 长期佩戴中辅助记录工作时看到的信息，适当场景截图/录屏留档。
2. **物体识别与标记**：调用识别算法（如 SAM 3）对视野内物体识别、加框、打标签。
3. **AI 接入与语音交互**：语音对话框，语音问答获取信息；提醒支持"先文字后播报"。

## 仓库当前状态

**v0.1 体验版已实现并通过构建 + 单测**（APK 44MB，一键推送到头显：`tools/push_to_quest.sh`）

```
android/                                  # 正式 App（Kotlin，零 AndroidX）
  app/src/main/java/com/visionhelper/
    MainActivity.kt                       # 主界面：识别画面 + 语音对话框 + 记录
    ProbeActivity.kt                      # 设备诊断（相机/麦克风/录制能力探针）
    vision/
      Detection.kt / LabelCatalog.kt      # 检测结果模型 + COCO80 中文标签词表
      YoloOnnxDetector.kt                 # ONNX Runtime 端侧推理（letterbox 预处理）
      YoloPostProcess.kt                  # 纯 Kotlin 后处理（可 JVM 单测）
    source/FrameSource.kt                 # 输入源抽象：演示图 / Camera2
    voice/
      LlmClient.kt                        # OpenAI 兼容协议客户端（零依赖）
      TtsEngine.kt / AsrEngine.kt         # 系统 TTS / ASR 封装
      ChatOrchestrator.kt                 # "先文字后播报"状态机
    store/RecordStore.kt                  # 记录库（JPEG + JSON）
    ui/OverlayView.kt / ChatBubble.kt     # 框+标签渲染 / 对话气泡
  app/src/main/assets/                    # yolov8n.onnx(12.8MB) + demo_bus.jpg
  app/src/test/.../YoloPostProcessTest.kt # 4 个 JVM 单测（对照 Python 参考实现）
tests/feasibility/                        # 4 项可行性验证（全部通过）
  test_yolo_onnx.py / test_sherpa_asr.py / test_sherpa_tts.py / expect.py
tools/
  push_to_quest.sh                        # 一键 构建→安装→启动
  fetch_models.sh                         # 重新获取模型资产
docs/                                     # 本文档体系
```

### v0.1 已验证 / 未验证

| 项 | 状态 |
|---|---|
| APK 构建（arm64-v8a 含 ONNX Runtime） | ✅ 已验证 |
| 后处理数学（letterbox 反算 / NMS / IoU） | ✅ 4 个 JVM 单测通过，并与 Python 参考实现逐位比对 |
| YOLOv8n 端侧推理精度与耗时 | ✅ 本机 Python 实测（38.6ms/帧，检出 4 物体） |
| 头显内实际运行 | ⏳ 需真机（本机无可用的 arm64 模拟器镜像） |
| 头显相机（`HEADSET_CAMERA`）/ 录屏 | ⏳ 需 Meta 开发者签名与真机验证 |

## 关键网络环境备注（影响构建/开发）

- `dl.google.com`、`github.com` release 大文件在本机易截断/404。
- 可用镜像：`maven.aliyun.com`（google/public/gradle-plugin）、`hf-mirror.com`、`pypi.tuna.tsinghua.edu.cn`。Gradle 仓库配置已切阿里云优先（`android/settings.gradle.kts`）。
- 模型下载走 sherpa-onnx GitHub release 或 hf-mirror.com。
