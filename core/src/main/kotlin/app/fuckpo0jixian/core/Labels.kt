package app.fuckpo0jixian.core

/** People-facing text for engine and UI codes, shared by the Android and desktop apps. */
fun statusText(code: String): String = when (code) {
    "UNTRUSTED_PATH" -> "没有可直连的网络，已跳过本次检查"
    "NETWORK_SETTLING" -> "网络刚切换，系统还没确认可用，20 秒内自动检查"
    "VPN_NO_BYPASS" -> "VPN 不允许直连：请在 VPN 的分应用代理中绕过本应用"
    "UNMANAGED" -> "未授权本机管理"
    "AUTHORIZED_LOCAL" -> "已授权，等待检查"
    "IDENTITY_REQUIRED" -> "未绑定 Wi-Fi，可手动更新"
    "LEGACY_UNINITIALIZED" -> "旧配置需要重新授权"
    "LEGACY_RECONCILE" -> "旧配置等待核对"
    "MIGRATED_CELLULAR_ONLY" -> "已迁移，仅在移动数据下更新"
    "SLOT_NOT_LOCAL" -> "未允许本机自动更新"
    "TEMPORARY_HOLD" -> "已锁定当前 IP"
    "SLOT_CURRENT" -> "已是最新"
    "SLOT_UPDATED" -> "已更新并核对"
    "EXTERNAL_CHANGE" -> "检测到外部修改，本机不接管"
    "PEER_UPDATED" -> "其他设备已更新"
    "SHARED_RECENT" -> "其他设备刚改过此槽，暂不自动改回；手动更新可覆盖"
    "IMPORT_INVALID" -> "剪贴板里不是有效的导出数据"
    "IMPORT_SAME_DEVICE" -> "这是本机导出的数据"
    "IMPORT_DEVICE_NAME" -> "导出数据缺少设备名称"
    "COVERED_OTHER_SLOT" -> "当前出口已在其他槽位，此槽未更新"
    "NO_TARGET", "MOBILE_MATCH" -> "没有需要更新的槽位"
    "UNKNOWN_WIFI" -> "未知 Wi-Fi，未更新"
    "WIFI_UNAVAILABLE" -> "无法读取 Wi-Fi，请在固定槽中一键开启 Wi-Fi 识别"
    "WIFI_SECURITY_CHANGED" -> "Wi-Fi 加密方式已变化，已停止自动更新"
    "IDENTITY_AMBIGUOUS" -> "网络匹配到多个槽位，请检查绑定"
    "PENDING_REVIEW" -> "上次写入待确认，下次检查会先核对"
    "PENDING_CONFIG_CHANGED" -> "配置已变化，请重新授权此槽"
    "RECOVERED_VERIFIED" -> "上次写入已确认生效"
    "RECOVERED_NOT_APPLIED" -> "上次写入未生效，未重发；后续检查按当前出口判断"
    "MANUAL_EXPIRED" -> "确认已过期，请重新操作"
    "AUTHORIZATION_REQUIRED" -> "请先打开「授权本机管理」"
    "CONFIG_QUERY_FIRST" -> "请先检查连接，再保存授权"
    "CONFIG_MOBILE_LIMIT" -> "每台手机只能有一个自动移动槽"
    "REBIND_REQUIRED" -> "Wi-Fi 名称已变化，请重新绑定"
    "NETWORK_BOUND_ELSEWHERE" -> "这个网络已绑定在其他槽位，请先在那里移除"
    "NETWORK_LIMIT" -> "一个槽位最多绑定 ${LayoutRules.MAX_NETWORKS} 个网络"
    "ACCOUNT_MISMATCH" -> "账户信息不一致，已停止操作"
    "DEMO_SCENARIO_0" -> "演示：住宅"
    "DEMO_SCENARIO_1" -> "演示：住宅重新拨号"
    "DEMO_SCENARIO_2" -> "演示：移动数据"
    "DEMO_SCENARIO_3" -> "演示：办公室"
    "DEMO_SCENARIO_4" -> "演示：新接入点待确认"
    "DEMO_SCENARIO_5" -> "演示：缺少权限"
    "DEMO_SCENARIO_6" -> "演示：槽位被占用"
    "NOT_CHECKED" -> "尚未检查"
    "DEMO_READY" -> "演示数据已就绪"
    "PRESENT_CURRENT_CHECK" -> "当前出口已在白名单"
    "SLOT_CONFLICT" -> "槽位占用与授权不符，请核对后重新授权"
    "SLOT_INVALID" -> "平台槽位与本机配置不符，未写入"
    "SLOT_VERIFY_FAILED" -> "写入后核对失败，已停止自动检查；请只读复核"
    "OBSERVED_MISSING" -> "当前出口不在白名单"
    "EGRESS_UNVERIFIED" -> "出口未验证，未写入"
    "CAPACITY_FULL" -> "白名单已满"
    "CONCURRENT_CHANGE" -> "平台或出口有变化，已停止"
    "RATE_LIMITED" -> "请求太频繁，请稍后再试"
    "LOCAL_UNCHANGED" -> "出口未变化，无需查询"
    "AUTH_PAUSED", "HTTP_401", "HTTP_403" -> "Token 无效，已暂停自动检查"
    "HTTP_429" -> "平台限流，等待期结束后才可检查"
    "NO_TOKEN" -> "未添加 Token"
    "TOKEN_INVALID" -> "Token 格式不正确"
    "TOKEN_SAVED" -> "Token 已保存，尚未检查"
    "SLOT_CONFIGURED" -> "配置已保存"
    "PAUSED" -> "已暂停"
    "BUSY" -> "正在检查"
    "CANCELLED_NETWORK_OR_SETTINGS", "NETWORK_CHANGED" -> "网络已变化，本次检查已取消"
    "INVALID_RESPONSE" -> "平台返回异常，未写入"
    "PLATFORM_DISABLED" -> "平台未启用白名单"
    "PROBE_RUNNING" -> "正在查询"
    "PROBE_OBSERVED" -> "已获取"
    "PROBE_INVALID_RESPONSE", "PROBE_FAILED" -> "查询失败"
    "PROBE_IPV6_ONLY" -> "仅返回 IPv6 地址"
    "STORAGE_RECOVERY_REQUIRED" -> "本地数据无法读取，原文件已保护；请勿清除应用数据"
    "NETWORK_OR_STORAGE_ERROR" -> "网络或存储异常，已停止自动检查；请只读复核，若仍失败请保留恢复材料"
    "PROTECTION_REVIEWED" -> "只读复核通过，仍保持暂停；请核对槽位后恢复检查"
    "TOKEN_CHANGE_PENDING" -> "账户切换未完成，已暂停并清除授权；请重新保存 Token"
    "NETWORK_TLS_OR_RESPONSE_ERROR" -> "网络异常，请稍后检查"
    "NETWORK_CONNECT_FAILED" -> "连接失败，请稍后检查"
    "NETWORK_DNS_FAILED" -> "域名解析失败，请稍后检查"
    else -> "检查未完成（${code.take(48)}）"
}

/**
 * What a slot page says about the latest change made elsewhere ([ManagedSlot.changedAt]): what happened, and what this
 * device does about it now, with the end of the co-managed quiet period when one is running. Null when nothing changed.
 */
fun changeNotice(slot: ManagedSlot, now: Long, time: (Long) -> String, policy: Policy = Policy()): Pair<String, String>? {
    if (slot.changedAt <= 0) return null
    val at = time(slot.changedAt)
    return when {
        slot.writer == Writer.LOCAL && slot.shared && slot.authorized -> {
            val until = slot.changedAt + policy.sharedQuietMs
            val left = until - now
            "$at 其他设备改了此槽" to if (left > 0)
                "为免两台设备来回改，本机 ${time(until)} 前不自动改它（还剩 ${(left + 59_999) / 60_000} 分钟）。手动更新可立即覆盖。"
            else "等待已结束，本机按自己的出口判断，需要时自动更新。"
        }
        slot.writer == Writer.LOCAL -> "$at 此槽被别处改了" to "本机写的值被替换，已停止自动更新此槽。核对后重新授权即可恢复。"
        slot.writer == Writer.OTHER_DEVICE -> "$at ${slot.owner.ifBlank { "其他设备" }}更新了此槽" to "由它负责，本机只记录变化。"
        else -> "$at 此槽在平台上被改动" to "不是本机写的，本机不会动它。"
    }
}
