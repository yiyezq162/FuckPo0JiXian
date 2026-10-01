#!/system/bin/sh
MODDIR=${0%/*}
echo "FuckPo0JiXian 只读诊断（用户主动触发）"
CLASSPATH="$MODDIR/helper.dex.jar" /system/bin/app_process /system/bin app.fuckpo0jixian.helper.Diagnostics
# The module description in the manager is kept current by the helper itself (state and counters).
