#!/usr/bin/env bash
# VisionHelper —— 一键构建并推送到头显（Quest 3 / Pico / 任意 android 设备）
#
# 用法：
#   ./tools/push_to_quest.sh                 # 自动检测设备并安装（USB 直连）
#   ./tools/push_to_quest.sh 192.168.1.23    # 无线调试：先 adb connect 该 IP 再安装
#
# 前置条件（Quest 3 侧）：
#   1. 手机 App「Meta Horizon」里为头显打开开发者模式
#   2. 设置 → 系统 → 开发者 → 打开「USB 调试」（首次连接头显内会弹出授权弹窗，需点上允许）
#   3. 若用无线：设置 → 系统 → 开发者 → 打开「无线调试」，记下 IP（端口 5555）

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="$ROOT/android"
APK="$ANDROID_DIR/app/build/outputs/apk/debug/app-debug.apk"
PKG="com.visionhelper"
mkdir -p /tmp/vh-research

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export PATH="$JAVA_HOME/bin:$PATH"

TARGET_IP="${1:-}"

echo "==> 1/4 构建 debug APK"
(cd "$ANDROID_DIR" && ./gradlew assembleDebug --no-daemon --console=plain) | tail -3

if [[ ! -f "$APK" ]]; then
  echo "构建产物缺失: $APK" >&2
  exit 1
fi
echo "    产物: $APK ($(du -h "$APK" | cut -f1))"

if [[ -n "$TARGET_IP" ]]; then
  echo "==> 2/4 无线 adb 连接 $TARGET_IP:5555"
  adb connect "$TARGET_IP:5555" || true
else
  echo "==> 2/4 使用 USB 直连（未指定 IP）"
fi

echo "==> 3/4 检测设备"
# 只统计状态为 device 的条目，避免 offline / emulator 干扰
CONNECTED="$(adb devices | awk 'NR>1 && $2=="device" {print $1}')"
if [[ -z "$CONNECTED" ]]; then
  cat >&2 <<'HELP'

未检测到可用设备（adb devices 里没有状态为 device 的条目）。

请在头显上确认：
  1. 设置 → 系统 → 开发者 → 已打开「开发者模式」
  2. 设置 → 系统 → 开发者 → 已打开「USB 调试」
  3. 已用数据线连接 Mac，且头显内弹窗点了「允许 USB 调试」
  4. 若用无线：设置 → 系统 → 开发者 → 无线调试，然后用
     ./tools/push_to_quest.sh <头显IP>

当前 adb 输出：
HELP
  adb devices -l >&2
  exit 1
fi
echo "    找到设备: $(echo "$CONNECTED" | tr '\n' ' ')"

echo "==> 4/4 安装并启动"
adb install -r -d "$APK"
adb shell am start -n "$PKG/.MainActivity"

cat <<'TIP'

完成。头显里应该在「应用库 → 未知来源」看到 VisionHelper。

使用要点：
  · 首个界面就是「识别一帧」的结果（内置演示图，不依赖任何相机权限）
  · 「设备诊断」页可确认该设备暴露了几个相机、能否抓帧/录音 —— 这是把输入源换成真机的第一步
  · 点「设置」可填入任意 OpenAI 兼容的 API Key / Base URL / 模型名，即可真对话；不填则用本地兜底应答
  · 语音：点「🎤 说话」调用系统识别；回复遵循「先显示文字，再语音播报」，可随时「停止播报」

查日志：
  adb logcat -s VisionHelper:V AndroidRuntime:E
TIP
