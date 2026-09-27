#!/system/bin/sh
MODDIR=${0%/*}
echo "AllowMate 只读诊断（用户主动触发）"
CLASSPATH="$MODDIR/helper.dex.jar" /system/bin/app_process /system/bin app.allowmate.helper.Diagnostics
# KernelSU-only display hint. Historical even within this boot; never a heartbeat.
if [ "$KSU" = "true" ] && [ -x /data/adb/ksud ]; then
  export KSU_MODULE=allowmate_helper
  /data/adb/ksud module config set --temp override.description "最近人工检查：$(date '+%Y-%m-%d %H:%M:%S')。仅历史记录，当前运行状态请重新检查；APK 请求结果见应用。" ||
    echo "此管理器不支持临时描述，诊断结果仍可查看。"
fi
