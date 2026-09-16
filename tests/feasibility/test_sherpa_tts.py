#!/usr/bin/env python3
"""可行性测试 C：端侧中文 TTS（sherpa-onnx + VITS zh）。

模拟 APK 内离线语音播报路径：模型约 120MB，纯 onnxruntime，
支持中文/混合，无需联网。Quest/Pico 上的播报可直接用此路径
（Quest 的 Android TextToSpeech 引擎不可用，必须自备 TTS 或走云端）。

运行前提：
  pip install sherpa-onnx
  下载并解压模型（约 120MB）：
  https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-zh-hf-theresa.tar.bz2
用法：
  python test_sherpa_tts.py <模型目录> [文本]
"""
import sys, time, wave
from pathlib import Path

import sherpa_onnx


def main() -> int:
    model_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    text = sys.argv[2] if len(sys.argv) > 2 else "视觉助手已就绪，检测到三个目标物体，已为您标记。"
    if not model_dir or not model_dir.exists():
        print("[SKIP] 未提供模型目录（下载命令见文件头注释）")
        return 1

    onnx_file = next(model_dir.glob("*.onnx"))
    fst_files = ",".join(str(p) for p in sorted(model_dir.glob("*.fst")))
    # vits-zh-hf 系列依赖 jieba 词典目录；若目录内含 espeak-ng phontab 才需要传 data_dir
    dict_dir = model_dir / "dict"
    data_dir = str(dict_dir) if (dict_dir / "phontab").exists() else ""
    cfg = sherpa_onnx.OfflineTtsConfig(
        model=sherpa_onnx.OfflineTtsModelConfig(
            vits=sherpa_onnx.OfflineTtsVitsModelConfig(
                model=str(onnx_file),
                lexicon=str(model_dir / "lexicon.txt"),
                tokens=str(model_dir / "tokens.txt"),
                data_dir=data_dir,
            ),
        ),
        rule_fsts=fst_files,
        max_num_sentences=1,
    )
    if not cfg.validate():
        print("[FAIL] TTS 配置校验失败（检查模型文件是否齐全）")
        return 1
    tts = sherpa_onnx.OfflineTts(cfg)
    out = Path("tts_out.wav")

    t0 = time.perf_counter()
    import numpy as np

    audio = tts.generate(text, sid=0, speed=1.0)
    el = time.perf_counter() - t0
    samples = np.asarray(audio.samples)
    dur = len(samples) / audio.sample_rate
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(audio.sample_rate)
        import numpy as np
        w.writeframes((np.asarray(audio.samples) * 32767).astype("int16").tobytes())

    print(f"[OK] VITS zh TTS | 合成 {dur:.2f}s 音频 | 耗时 {el*1000:.0f}ms | RTF={el/dur:.2f}")
    print(f"     输出: {out.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
