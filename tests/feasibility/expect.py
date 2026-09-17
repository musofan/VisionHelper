"""生成 Kotlin 后处理单测的期望值（与 Kotlin 实现逐行等价的参考实现）。"""
import json


def iou(a, b):
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    iw, ih = ix2 - ix1, iy2 - iy1
    if iw <= 0 or ih <= 0:
        return 0.0
    inter = iw * ih
    ua = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return inter / ua if ua > 0 else 0.0


def clamp(v):
    return min(1.0, max(0.0, v))


def decode(anchors, scale, dx, dy, ow, oh, conf_t=0.35, iou_t=0.45):
    cand = []
    for cls, conf, cx, cy, w, h in anchors:
        if conf < conf_t:
            continue
        x1 = (cx - w / 2 - dx) / scale / ow
        y1 = (cy - h / 2 - dy) / scale / oh
        x2 = (cx + w / 2 - dx) / scale / ow
        y2 = (cy + h / 2 - dy) / scale / oh
        cand.append((cls, conf, clamp(x1), clamp(y1), clamp(x2), clamp(y2)))
    cand.sort(key=lambda c: -c[1])
    kept = []
    for c in cand:
        if all(not (k[0] == c[0] and iou(k[2:], c[2:]) > iou_t) for k in kept):
            kept.append(c)
    return kept


# 案例 A：640x640 原图（scale=1, dx=dy=0）
#  anchor0 person 0.90 / anchor1 person 0.80（与前者 IoU 高，应被抑制）
#  anchor2 bicycle 0.70 / anchor3 car 0.20（低于阈值，应丢弃）
A = [
    (0, 0.90, 100, 100, 50, 50),
    (0, 0.80, 105, 105, 50, 50),
    (1, 0.70, 300, 300, 100, 100),
    (2, 0.20, 500, 500, 60, 60),
]
ra = decode(A, 1.0, 0.0, 0.0, 640, 640)
print("A:", json.dumps(ra))

# 案例 B：1280x640 原图，letterbox scale=0.5 / dx=0 / dy=160（验证反算与 clamp）
B = [(0, 0.75, 320, 200, 100, 100), (3, 0.55, 600, 320, 80, 80)]
rb = decode(B, 0.5, 0.0, 160.0, 1280, 640)
print("B:", json.dumps(rb))

# IoU 交叉验证（案例 A 前两个框，在归一化空间）
a = (75 / 640, 75 / 640, 125 / 640, 125 / 640)
b = (80 / 640, 80 / 640, 130 / 640, 130 / 640)
print("iou_caseA_pairs:", round(iou(a, b), 6))
