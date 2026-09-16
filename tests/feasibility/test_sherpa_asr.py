#!/usr/bin/env python3
"""可行性测试 B：端侧中文 ASR（sherpa-onnx + SenseVoice-Small int8）。

模拟 APK 内离线语音识别路径：模型 163MB(int8)，纯 onnxruntime 推理，
支持 zh/en/ja/ko/yue，带标点，无需联网、无需云账号。

运行前提：
  pip install sherpa-onnx
  下载并解压模型（约 163MB）：
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2024-07-17.tar.bz2
用法：
  python test_sherpa_asr.py <模型目录> [audio.wav]
"""
import sys, time
from pathlib import Path

import sherpa_onnx
import wave


def read_wav(path: str, target_sr: int = 16000):
    with wave.open(path, "rb") as w:
        assert w.getnchannels() == 1, "需要单声道 wav"
        sr = w.getframerate()
        data = w.readframes(w.getnframes())
    import numpy as np
    audio = np.frombuffer(data, dtype=np.int16).astype(np.float32) / 32768.0
    if sr != target_sr:  # 线性重采样（APK 内用 AudioRecord 直接采 16k，此处仅用于离线文件测试）
        n = int(len(audio) * target_sr / sr)
        audio = np.interp(np.linspace(0, len(audio) - 1, n), np.arange(len(audio)), audio).astype(np.float32)
    return audio, len(audio) / target_sr


def main() -> int:
    model_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else None
    wav = sys.argv[2] if len(sys.argv) > 2 else "test_zh.wav"
    if not model_dir or not model_dir.exists():
        print("[SKIP] 未提供模型目录（下载命令见文件头注释）")
        return 1
    model = next(model_dir.glob("model.int8.onnx"), None) or next(model_dir.glob("model.onnx"))
    tokens = model_dir / "tokens.txt"

    recognizer = sherpa_onnx.OfflineRecognizer.from_sense_voice(
        model=str(model), tokens=str(tokens), use_itn=True,
    )

    audio, dur = read_wav(wav)
    t0 = time.perf_counter()
    s = recognizer.create_stream()
    s.accept_waveform(16000, audio)
    recognizer.decode_stream(s)
    el = time.perf_counter() - t0
    text = s.result.text.strip()
    rtf = el / dur  # 实时率：越小越好
    print(f"[OK] SenseVoice int8 | 音频 {dur:.2f}s | 耗时 {el*1000:.0f}ms | RTF={rtf:.2f}")
    print(f"     识别结果: {text}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
