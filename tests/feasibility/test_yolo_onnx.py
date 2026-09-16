#!/usr/bin/env python3
"""可行性测试 A：纯 ONNX Runtime 推理 YOLOv8n 物体检测（无 PyTorch 依赖）。

模拟端侧（Quest 3 / Pico / AI 眼镜 APK）部署路径：
  YOLOv8n (.pt) --export--> yolov8n.onnx (12.8MB) --onnxruntime--> 检测框 + 类别
端上只需打包 onnxruntime(AAR) + 模型文件，即可在 ARM CPU 上跑检测。

运行前提：
  pip install onnxruntime opencv-python-headless numpy
用法：
  python test_yolo_onnx.py [image.jpg] [model.onnx]
"""
import sys, time
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort

COCO = ["person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
        "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat","dog",
        "horse","sheep","cow","elephant","bear","zebra","giraffe","backpack","umbrella",
        "handbag","tie","suitcase","frisbee","skis","snowboard","sports ball","kite",
        "baseball bat","baseball glove","skateboard","surfboard","tennis racket","bottle",
        "wine glass","cup","fork","knife","spoon","bowl","banana","apple","sandwich","orange",
        "broccoli","carrot","hot dog","pizza","donut","cake","chair","couch","potted plant",
        "bed","dining table","toilet","tv","laptop","mouse","remote","keyboard","cell phone",
        "microwave","oven","toaster","sink","refrigerator","book","clock","vase","scissors",
        "teddy bear","hair drier","toothbrush"]


def letterbox(img, new=640, color=(114, 114, 114)):
    h, w = img.shape[:2]
    r = min(new / h, new / w)
    nh, nw = round(h * r), round(w * r)
    resized = cv2.resize(img, (nw, nh), interpolation=cv2.INTER_LINEAR)
    canvas = np.full((new, new, 3), color, dtype=np.uint8)
    top, left = (new - nh) // 2, (new - nw) // 2
    canvas[top:top + nh, left:left + nw] = resized
    return canvas, r, left, top


def main() -> int:
    img_path = Path(sys.argv[1] if len(sys.argv) > 1 else "person.jpg")
    onnx_path = Path(sys.argv[2] if len(sys.argv) > 2 else "yolov8n.onnx")
    if not img_path.exists() or not onnx_path.exists():
        print(f"[SKIP] missing {img_path} or {onnx_path}")
        return 1

    so = ort.SessionOptions()
    so.intra_op_num_threads = 4  # 模拟移动端线程约束
    sess = ort.InferenceSession(str(onnx_path), so, providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]

    img = cv2.imread(str(img_path))
    blob, r, dx, dy = letterbox(img)
    x = blob[:, :, ::-1].transpose(2, 0, 1)[None].astype(np.float32) / 255.0

    # warmup + 计时（对齐移动端评测口径）
    for _ in range(3):
        sess.run(None, {inp.name: x})
    t0 = time.perf_counter()
    N = 10
    for _ in range(N):
        out = sess.run(None, {inp.name: x})[0]
    infer_ms = (time.perf_counter() - t0) / N * 1000

    # YOLOv8 输出: (1, 84, 8400) -> 转置后逐行 [cx,cy,w,h,cls...]
    preds = out[0].T if out.shape[1] == 84 else out.reshape(84, -1).T
    boxes, scores, cls_ids = [], [], []
    for row in preds:
        cls_score = row[4:]
        cls_id = int(cls_score.argmax())
        conf = float(cls_score[cls_id])
        if conf < 0.45:
            continue
        cx, cy, w, h = row[:4]
        boxes.append([cx - w / 2, cy - h / 2, w, h])
        scores.append(conf)
        cls_ids.append(cls_id)

    idx = cv2.dnn.NMSBoxes(boxes, scores, 0.45, 0.5)
    dets = []
    for i in np.array(idx).flatten():
        bx, by, bw, bh = boxes[i]
        x0 = int((bx - dx) / r); y0 = int((by - dy) / r)
        x1 = int((bx - dx + bw) / r); y1 = int((by - dy + bh) / r)
        dets.append((COCO[cls_ids[i]], round(scores[i], 3), (x0, y0, x1, y1)))
        print(f"  {COCO[cls_ids[i]]:<14} conf={scores[i]:.3f} box=({x0},{y0},{x1},{y1})")

    print(f"[OK] onnxruntime={ort.__version__} provider=CPU(4 threads) "
          f"input={inp.shape} 推理均值={infer_ms:.1f}ms 检出={len(dets)}个物体")
    return 0


if __name__ == "__main__":
    sys.exit(main())
