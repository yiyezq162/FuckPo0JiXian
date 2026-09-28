#!/system/bin/sh
# Executed by KernelSU module installer, not a standalone root installer.
[ "$KSU" = "true" ] || [ -n "$MAGISK_VER_CODE" ] || abort "需要 Magisk 或 KernelSU 模块安装器。"
ui_print "去他妈的鸡险 0.8.0-preview · 运行辅助模块"
ui_print "（如果您不知道这个是什么，那么就不用理会）"
ui_print "无系统挂载，不需要本模块专用 metamodule。"
ui_print "事件 helper，不保存 token、不访问 Po0；真机兼容与待机尚未验收。"
ui_print "KernelSU 用户空间版本: ${KSU_VER_CODE:-UNKNOWN}"
ui_print "KernelSU 内核版本: ${KSU_KERNEL_VER_CODE:-UNKNOWN}"
ui_print "32601-2 的分支与 action.sh 支持仍需真机确认。"
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/helper.dex.jar" 0 0 0600
set_perm "$MODPATH/certificate.sha256" 0 0 0600
