#!/system/bin/sh
MODDIR=${0%/*}
# Magisk late_start and KernelSU service stage. Never block post-fs-data.
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
  sleep $((attempt * 15))
done
# Exhausted bounded recovery: UI reports loss; never an infinite restart storm.
