#!/bin/bash
# tvlockctl — install, diagnose and (when it has to) disarm TV Lock from a computer.
#
# Safety model:
#   * `status` is read-only and is the default. It never writes anything.
#   * Every subcommand that changes the TV requires --yes, and --dry-run prints without executing.
#   * There is no factory reset here, and there never will be. Restoring the TV is a manual,
#     deliberate act described in docs/RECOVERY.md.
#
# Usage:
#   export TV=TV_IP:5555        # adb serial; omit when exactly one device is attached
#   ./tools/tvlockctl.sh status
#   ./tools/tvlockctl.sh arm --yes
#   ./tools/tvlockctl.sh disable --yes      # TV becomes unprotected
#   ./tools/tvlockctl.sh uninstall --yes

set -u

PKG="com.toy.tvlock"
LISTENER="$PKG/com.toy.tvlock.RevivalListener"
ADB="adb"
if [ -n "${TV:-}" ]; then
  ADB="adb -s $TV"
fi

DRY=0
YES=0
CMD="status"

for arg in "$@"; do
  case "$arg" in
    status|arm|disable|uninstall|dump) CMD="$arg" ;;
    --yes) YES=1 ;;
    --dry-run) DRY=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数: $arg"; exit 2 ;;
  esac
done

sh() {
  if [ "$DRY" = "1" ]; then
    echo "  [dry-run] $*"
  else
    eval "$@"
  fi
}

require_yes() {
  if [ "$YES" != "1" ]; then
    echo
    echo "该操作会修改电视状态。确认后加 --yes 重跑。"
    exit 2
  fi
}

preflight() {
  if ! command -v adb >/dev/null 2>&1; then
    echo "找不到 adb。"
    exit 1
  fi
  local state
  state="$($ADB get-state 2>&1)"
  if [ "$state" != "device" ]; then
    echo "电视未就绪（adb get-state: ${state}）。"
    echo "先 adb connect <电视IP>:5555，或检查 USB 连接。"
    exit 1
  fi
}

status() {
  echo "=== 设备 ==="
  $ADB shell getprop ro.product.model
  $ADB shell getprop ro.build.display.id
  $ADB shell getprop ro.build.version.sdk

  echo
  echo "=== 安装状态 ==="
  $ADB shell pm path "$PKG" 2>&1 | head -1
  $ADB shell dumpsys package "$PKG" 2>/dev/null | grep -E "versionName|versionCode" | head -2

  echo
  echo "=== 进程 ==="
  local pid
  pid="$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r')"
  echo "pid: ${pid:-<未运行>}"

  echo
  echo "=== 授权 ==="
  echo -n "悬浮窗: "
  $ADB shell appops get "$PKG" SYSTEM_ALERT_WINDOW 2>&1 | tr -d '\r' | head -1
  echo -n "通知监听: "
  $ADB shell settings get secure enabled_notification_listeners 2>/dev/null \
    | tr -d '\r' | tr ':' '\n' | grep -c "$PKG" | sed 's/^/命中 /'

  echo
  echo "=== 锁定状态（来自应用自身的诊断输出）==="
  $ADB shell dumpsys activity service "$LISTENER" 2>&1 | sed -n '1,40p' | tr -d '\r'

  echo
  echo "=== 最近强停记录（若有）==="
  $ADB shell logcat -d -t 400 2>/dev/null \
    | grep -E "Force stopping $PKG|killAllThirdApp.*$PKG" | tail -5
  echo "（空表示最近 400 行日志里没有）"
}

arm() {
  require_yes
  echo "将执行：授予悬浮窗、授权通知监听。"
  sh "$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW allow"
  sh "$ADB shell cmd notification allow_listener $LISTENER"
  if [ "$DRY" = "1" ]; then return; fi

  echo
  echo "=== 复核 ==="
  $ADB shell appops get "$PKG" SYSTEM_ALERT_WINDOW | tr -d '\r'
  $ADB shell settings get secure enabled_notification_listeners | tr -d '\r' | tr ':' '\n' | grep "$PKG"
  echo "通知监听未出现在上面时，授权失败，请勿认为已生效。"
}

disable() {
  require_yes
  echo "!!! 执行后电视将处于「无保护」状态，任何人都可以直接使用。"
  echo "将执行：撤销悬浮窗、取消通知监听授权、结束进程。"
  sh "$ADB shell appops set $PKG SYSTEM_ALERT_WINDOW ignore"
  sh "$ADB shell cmd notification disallow_listener $LISTENER"
  sh "$ADB shell am force-stop $PKG"
  if [ "$DRY" = "1" ]; then return; fi
  echo
  echo "已解除。确认电视可正常操作后，再决定是修还是卸载。"
}

uninstall() {
  require_yes
  echo "将执行：先解除保护，再卸载 ${PKG}（应用数据一并删除，包括已设置的 PIN）。"
  # TV (if set) is exported in the environment, so the child inherits the same device.
  "$0" disable --yes
  sh "$ADB uninstall $PKG"
}

case "$CMD" in
  arm|disable|uninstall) require_yes ;;
esac

# A dry run prints the commands without touching the TV, so it does not need a device.
if [ "$DRY" != "1" ]; then
  preflight
fi

case "$CMD" in
  status) status ;;
  dump) $ADB shell dumpsys activity service "$LISTENER" | tr -d '\r' ;;
  arm) arm ;;
  disable) disable ;;
  uninstall) uninstall ;;
esac
