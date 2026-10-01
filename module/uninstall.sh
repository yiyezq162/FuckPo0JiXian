#!/system/bin/sh
MODDIR=${0%/*}
# Inotify stops the helper; removal also fails closed at every event and IPC claim.
touch "$MODDIR/remove"
# The module's lifetime counters live outside the module folder (so updates keep them); removal takes them along.
rm -rf /data/adb/fuckpo0jixian
