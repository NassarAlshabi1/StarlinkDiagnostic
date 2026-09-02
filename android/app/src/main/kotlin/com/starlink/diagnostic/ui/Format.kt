package com.starlink.diagnostic.ui

/** Small Arabic display helpers shared across screens. */

fun formatUptimeAr(seconds: Long?): String {
    if (seconds == null || seconds <= 0L) return "—"
    var s = seconds
    val d = s / 86400; s %= 86400
    val h = s / 3600; s %= 3600
    val m = s / 60; s %= 60
    val sec = s
    return if (d > 0) "%dd %02d:%02d:%02d".format(d, h, m, sec)
    else "%02d:%02d:%02d".format(h, m, sec)
}

/** Human-readable GPS state (clearly differentiates the 3 non-OK cases). */
fun formatGpsAr(label: String): String = when (label) {
    "ok" -> "يعمل (valid)"
    "no_fix" -> "لا إصلاح — غير متاح حالياً (unavailable)"
    "inhibited" -> "موقوف عمداً (inhibited)"
    "hardware_failure" -> "فشل اختبار العتاد (hardware failure)"
    else -> "غير معروف"
}

fun formatTsAr(ts: Long?): String {
    if (ts == null || ts <= 0L) return "—"
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
    return fmt.format(java.util.Date(ts * 1000L))
}

fun formatTsMsAr(ts: Long?): String {
    if (ts == null || ts <= 0L) return "—"
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
    return fmt.format(java.util.Date(ts))
}
