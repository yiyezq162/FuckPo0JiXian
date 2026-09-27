package app.allowmate.core

/** Trigger selection never changes account authorization or persistent rate limits. */
object RuntimePolicy {
    const val PROTOCOL = 1
    fun enabled(s: State) = s.runtimeMode == RuntimeMode.MODULE && !s.paused && !s.demo && !s.authBlocked
    fun status(mode: RuntimeMode, paused: Boolean, reply: String?): String = when {
        mode == RuntimeMode.STANDARD -> "标准模式 · Android 调度"
        paused -> "已暂停 · 增强触发关闭"
        reply == null -> "模块失联 · 已降级；安装及启用状态未知"
        reply == "NOT_SEEN" -> "本次开机未检测到模块 · 已降级；安装及启用状态未知"
        reply == "DISABLED" -> "模块已禁用 · 标准调度兜底"
        reply == "LOCKED" -> "待首次解锁 · 凭据尚不可用"
        reply == "VERSION" -> "版本不匹配 · 标准调度兜底"
        reply == "BOOT_UNKNOWN" -> "开机归属无法核实 · 已降级为标准调度"
        reply == "READY" -> "模块已握手 · 只读验收中，标准调度兜底"
        reply == "ACTIVE" -> "增强已连接 · 本代 APK 只读链路已完成"
        reply == "DOZE" -> "系统深度休眠 · 增强已降级，等待系统调度"
        reply == "START_FAILED" -> "唤起未确认 · 已降级，请检查应用自启动权限"
        reply == "STOPPED" -> "增强触发已关闭 · 标准调度兜底"
        else -> "增强不可用 · 已降级为标准调度"
    }
}
