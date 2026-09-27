#!/system/bin/sh
MODDIR=${0%/*}
# Inotify stops the helper; removal also fails closed at every event and IPC claim.
touch "$MODDIR/remove"
