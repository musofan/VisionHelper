#!/usr/bin/env bash
# 重新获取端侧模型资产（仓库已内置，仅在需要重新下载/更新时使用）
#
# 本机网络注意事项（实测）：dl.google.com 与 github release 的大文件容易截断/404，
# 优先使用 hf-mirror.com 或带 --retry 的断点续传。

set -euo pipefail
ASSETS="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/android/app/src/main/assets"
mkdir -p "$ASSETS"

echo "==> YOLOv8n ONNX (12.8MB) → assets/yolov8n.onnx"
# 来源：Ultralytics 官方导出的 yolov8n.onnx（ONNX opset 12，输入名 images，输出 [1,84,8400]）
if [[ ! -f "$ASSETS/yolov8n.onnx" ]]; then
  curl -L --retry 10 --retry-all-errors -o "$ASSETS/yolov8n.onnx" \
    "https://hf-mirror.com/Xenova/yolov8n/resolve/main/onnx/model.onnx" || {
      echo "下载失败。可自行导出：" >&2
      echo "  pip install ultralytics && yolo export model=yolov8n.pt format=onnx imgsz=640" >&2
      exit 1
    }
else
  echo "    已存在，跳过"
fi

echo "==> 演示图 → assets/demo_bus.jpg"
if [[ ! -f "$ASSETS/demo_bus.jpg" ]]; then
  curl -L --retry 10 --max-time 60 -o "$ASSETS/demo_bus.jpg" "https://ultralytics.com/images/bus.jpg"
else
  echo "    已存在，跳过"
fi

echo "==> 校验"
ls -la "$ASSETS"
python3 - "$ASSETS/yolov8n.onnx" <<'PY'
import sys
p = sys.argv[1]
head = open(p, "rb").read(8)
assert head[:2] == b"\x08\x08", f"不是 ONNX protobuf（前 8 字节: {head!r}）"
print("onnx 文件头校验通过")
PY