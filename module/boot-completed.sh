#!/system/bin/sh
# KernelSU hook only. Magisk uses service.sh; the Java file lock rejects duplicates.
MODDIR=${0%/*}
[ "$KSU" = "true" ] && sh "$MODDIR/service.sh" &
