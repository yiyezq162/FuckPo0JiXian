#!/system/bin/sh
MODDIR=${0%/*}
# Magisk late_start and KernelSU service stage. Never block post-fs-data.
# The manager's module list shows module.prop's description; the helper keeps it current once it runs.
describe() { sed -i "s#^description=.*#description=$1#" "$MODDIR/module.prop" 2>/dev/null; }
describe "⏳ 正在启动辅助进程…"
attempt=0
while [ "$attempt" -lt 4 ]; do
  [ -d "$MODDIR" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
  CLASSPATH="$MODDIR/helper.dex.jar" /system/bin/app_process /system/bin app.fuckpo0jixian.helper.Main
  result=$?
  # A rejected duplicate instance exits normally; do not restart it repeatedly.
  [ "$result" -eq 0 ] && exit 0
  # Disable/uninstall must also stop the supervisor before its next backoff.
  [ -d "$MODDIR" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ] || exit 0
  attempt=$((attempt + 1))
  describe "⚠️ 辅助进程异常退出，正在第 $attempt 次重试…"
  sleep $((attempt * 15))
done
# Exhausted bounded recovery: say so where people look, never an infinite restart storm.
describe "❌ 辅助进程未能启动（已重试 4 次）。请重启手机；仍不行请在应用「记录」中导出调试信息"
