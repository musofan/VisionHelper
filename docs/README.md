# VisionHelper — 调研与产品规划文档

> 基于 Meta Quest 3 的 VR 眼镜辅助记录工具：物体识别与标记 + AI 语音交互
> 文档版本：v1.0（2026-09-16）· 配套可行性验证见 `docs/03-feasibility-report.md`

## 文档索引

| 文档 | 内容 |
|---|---|
| [01-research.md](01-research.md) | 两套方案的系统调研与对比（Meta 官方体系 vs 跨平台开放方案） |
| [02-product-plan.md](02-product-plan.md) | 产品定义、技术选型结论、架构设计与开发路线图 |
| [03-feasibility-report.md](03-feasibility-report.md) | 本机已跑通的 4 项可行性验证结果（含数据） |

## 一句话结论

**推荐"方案二为主 + 方案一为适配层"的混合路线**：以纯 Android/Kotlin APK + ONNX Runtime 端侧推理 + sherpa-onnx 离线语音为基座（可跑 Quest 3 也可以跑 Pico 4 / AI 眼镜），通过条件依赖（runtime 检测）在 Meta 设备上叠加 Passthrough / 场景 understanding 等官方能力。全部核心链路已在本机完成可行性验证。

## 核心目标（用户需求原文）

1. 长期佩戴中辅助记录工作时看到的信息，适当场景截图/录屏留档。
2. **物体识别与标记**：调用识别算法（如 SAM 3）对视野内物体识别、加框、打标签。
3. **AI 接入与语音交互**：语音对话框，语音问答获取信息；提醒支持"先文字后播报"。

## 仓库当前状态

```
android/                  # 探针 APK（已成功构建 app-debug.apk, 2.4MB）
  app/src/main/java/com/visionhelper/probe/
    CameraProbe.kt        # Camera2 取流 + 截图保存
    AudioCapture.kt       # 16kHz/16bit/单声道 PCM 采集（喂 ASR）
    MainActivity.kt       # 权限申请 + 状态面板
tests/feasibility/        # 4 项可行性测试（全部验证通过）
  test_yolo_onnx.py       # A: YOLOv8n ONNX 端侧检测
  test_sherpa_asr.py      # B: SenseVoice 离线中文 ASR
  test_sherpa_tts.py      # C: VITS 离线中文 TTS
docs/                     # 本文档体系
```

## 关键网络环境备注（影响构建/开发）

- `dl.google.com`、`github.com` release 大文件在本机易截断/404。
- 可用镜像：`maven.aliyun.com`（google/public/gradle-plugin）、`hf-mirror.com`、`pypi.tuna.tsinghua.edu.cn`。Gradle 仓库配置已切阿里云优先（`android/settings.gradle.kts`）。
- 模型下载走 sherpa-onnx GitHub release 或 hf-mirror.com。
