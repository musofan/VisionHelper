# 03 · 可行性验证报告（本机实测）

> 环境：macOS (Apple Silicon, Python 3.12 venv) / Android: OpenJDK 17 + Gradle 8.14 + AGP（阿里云镜像）
> 测试脚本：`tests/feasibility/`（模型在 `/tmp/vh-research/`，重跑命令见各脚本头注释）

## A. 端侧物体检测（YOLOv8n → ONNX Runtime）

```
$ python tests/feasibility/test_yolo_onnx.py bus.jpg yolov8n.onnx
  person  conf=0.891 box=(670,380,809,879)
  person  conf=0.883 box=(221,407,343,856)
  person  conf=0.878 box=(50,397,244,905)
  bus     conf=0.842 box=(34,229,797,767)
[OK] onnxruntime=1.30.0 provider=CPU(4 threads) input=[1,3,640,640]
     推理均值=38.6ms  检出=4个物体
```

- 模型 12.8MB，纯 onnxruntime，无 PyTorch 依赖 → 可直接打进 APK（onnxruntime-android AAR 同推理接口）。
- 38.6ms ≈ 26 FPS（Mac CPU）；Quest 3 上预留 5~10 FPS（节流策略已写入 02 文档）。
- "打标签"：COCO 80 类 → 业务词表映射；开集/自定义标签走云 VL API 兜底。
- SAM 系补充：MobileSAM/FastSAM/EfficientSAM 官方支持 ONNX 导出（已核实），Phase 3 可加精细 mask。

## B. 离线中文 ASR（sherpa-onnx + SenseVoice-Small int8）

```
$ python tests/feasibility/test_sherpa_asr.py <sensevoice-int8-dir> test_zh.wav
[OK] SenseVoice int8 | 音频 3.45s | 耗时 141ms | RTF=0.04
     识别结果: 请帮我记录一下桌面上的工具清单。
```

- 模型 163MB（int8），zh/en/ja/ko/yue，带标点，**完全离线无账号**。
- RTF 0.04：1 秒音频 40ms 出结果，实时语音指令毫无压力。
- 官方 Android AAR（`com.k2fsa.sherpa:onnx`）可直接集成。

## C. 离线中文 TTS（sherpa-onnx + VITS zh-hf-theresa）

```
$ python tests/feasibility/test_sherpa_tts.py <vits-zh-hf-theresa-dir>
[OK] VITS zh TTS | 合成 4.13s 音频 | 耗时 3048ms | RTF=0.74
     输出: tts_out.wav
```

- 模型 <100MB，离线中文女声；RTF 0.74 可实时播报。
- 备选更多音色：hf-mirror 上 `vits-zh-*` 系列。

## D. Android 探针 APK（构建链路验证）

```
$ ./gradlew assembleDebug   # JAVA_HOME=openjdk@17, 阿里云 maven 镜像
BUILD SUCCESSFUL in 44s
app/build/outputs/apk/debug/app-debug.apk (2.4MB)
```

- 包含：CameraProbe.kt（Camera2 取流+截图）、AudioCapture.kt（16kHz/16bit/单声道 PCM，ASR 输入规格）、权限流程（CAMERA/RECORD_AUDIO）。
- Gradle 仓库已配置阿里云镜像优先（`dl.google.com` 大文件在本机会截断，实测 403/截断）。
- 探针 APK 即可侧载 Quest 3 / Pico 做真机验证（Phase 0 收尾）。

## 结论

视觉（框+标签）、语音输入、语音输出、Android 构建四条链路全部本机验证通过；剩余唯一硬问题为头显侧画面输入源与录屏权限（需真机，见 02 文档风险登记）。
