#!/system/bin/sh
MODDIR=${0%/*}
echo "FuckPo0JiXian 只读诊断（用户主动触发）"
CLASSPATH="$MODDIR/helper.dex.jar" /system/bin/app_process /system/bin app.fuckpo0jixian.helper.Diagnostics
# KernelSU-only display hint. Historical even within this boot; never a heartbeat.
if [ "$KSU" = "true" ] && [ -x /data/adb/ksud ]; then
  export KSU_MODULE=fuckpo0jixian_helper
  /data/adb/ksud module config set --temp override.description "辅助后台唤起。最近人工检查：$(date '+%Y-%m-%d %H:%M:%S')，不代表当前在线。
（如果您不知道这个是什么，那么就不用理会）" ||
    echo "此管理器不支持临时描述，诊断结果仍可查看。"
fi
