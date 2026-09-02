"""Diagnostics engine — turns raw dish data into an evidence chain.

Implements the V2 assessment flow:

    Hardware Self-Test -> code -> component -> component checks
    (GPS Valid? -> Satellites? -> Inhibited?) -> Initialization -> RF/PHY
    -> Final Assessment

Design rules (from the V2 spec):
- Codes come from what the dish actually announces over gRPC
  (alerts_hardware Alert enum + DishAlerts booleans), never invented.
- The engine must clearly DIFFERENTIATE:
    * GPS hardware test failure   (code maps to GPS)
    * GPS inhibited               (gps_enabled == False)
    * GPS unavailable / no fix    (valid=false, sats=0, not inhibited)
  because these states are NOT equivalent.
- A hardware fault may only be CONCLUDED when the evidence supports it;
  otherwise the report says so and lists the next test.
"""

# Codes officially defined by the gRPC schema (repeated Alert enum,
# alerts_hardware field) — verified against the dish protoset.
CODE_TABLE = {
    0:  {"component": "NOMINAL",            "componentAr": "طبيعي",                "severity": "info"},
    1:  {"component": "THERMAL_SHUTDOWN",   "componentAr": "إيقاف حراري",          "severity": "hard"},
    2:  {"component": "THERMAL_THROTTLE",   "componentAr": "خفض حراري للأداء",     "severity": "warn"},
    3:  {"component": "POWER_SUPPLY_IDLE",  "componentAr": "مزود الطاقة خامل",     "severity": "warn"},
    4:  {"component": "MAST_NOT_VERTICAL",  "componentAr": "السارية غير رأسية",    "severity": "warn"},
    5:  {"component": "UNEXPECTED_LOCATION","componentAr": "موقع غير متوقع",       "severity": "warn"},
    6:  {"component": "OBSTRUCTED",         "componentAr": "عرقلة مجال الرؤية",    "severity": "warn"},
    7:  {"component": "SLOW_ETHERNET",      "componentAr": "إيثرنت بطيء",          "severity": "warn"},
    8:  {"component": "IS_HEATING",         "componentAr": "التسخين نشط",          "severity": "info"},
    9:  {"component": "UNSAFE_VOLTAGE",     "componentAr": "جهد غير آمن",          "severity": "hard"},
    10: {"component": "LAST_CHAIN_NON_IDLE","componentAr": "سلسلة RF غير خاملة",   "severity": "warn"},
    11: {"component": "BATTERY_USAGE",      "componentAr": "استهلاك بطارية",       "severity": "warn"},
    12: {"component": "IDLE_ATTY",          "componentAr": "ATTY خامل",            "severity": "info"},
    13: {"component": "MOBILITY_HANDOFF",   "componentAr": "تنازل حركي",           "severity": "info"},
    # Observed in the field on firmware 2026.x (user device): GPS self-test
    # failure is announced as code 14. Not present in the older protoset —
    # documented in docs/DIAGNOSTICS.md.
    14: {"component": "GPS",                "componentAr": "نظام تحديد المواقع",   "severity": "hard"},
}

# The 8 hardware components shown on the HARDWARE page.
HARDWARE_COMPONENTS = [
    {"key": "cpu_voltage",  "en": "CPU Voltage",  "ar": "جهد المعالج"},
    {"key": "dbf_aap",      "en": "DBF / AAP",    "ar": "DBF / AAP"},
    {"key": "eth_phy",      "en": "Ethernet PHY", "ar": "فيزيائي الإيثرنت"},
    {"key": "rf",           "en": "RF",           "ar": "الترددات الراديوية"},
    {"key": "gps",          "en": "GPS",          "ar": "GPS"},
    {"key": "imu",          "en": "IMU",          "ar": "وحدة القياس بالقصور"},
    {"key": "temperature",  "en": "Temperature",  "ar": "درجة الحرارة"},
    {"key": "power",        "en": "Power",        "ar": "الطاقة"},
]

GPS_OK = "ok"
GPS_NO_FIX = "no_fix"
GPS_INHIBITED = "inhibited"
GPS_HW_FAIL = "hardware_failure"


def _step(step_id, title_en, title_ar, status, evidence=None, note_ar=None):
    return {
        "id": step_id,
        "titleEn": title_en,
        "titleAr": title_ar,
        "status": status,  # pass | fail | warn | info | skip
        "evidence": evidence or {},
        "noteAr": note_ar,
    }


def code_label(code):
    entry = CODE_TABLE.get(code)
    if entry:
        return entry["component"], entry["componentAr"]
    return "CODE_%d" % code, "رمز غير معروف (%d)" % code


def assess_gps(status):
    """Three-way GPS verdict from the actual announced fields.

    status keys used: gps_ready, gps_sats, gps_enabled, gps_inhibit_raw,
    alert_hw_codes.
    """
    ready = status.get("gps_ready")
    sats = status.get("gps_sats")
    enabled = status.get("gps_enabled")  # None => unknown inhibit state
    inhibit_raw = status.get("gps_inhibit_raw")
    hw_codes = [c for c in status.get("alert_hw_codes", []) if c != 0]
    gps_hw_code = None
    for c in hw_codes:
        comp, _ = code_label(c)
        if comp == "GPS":
            gps_hw_code = c
            break

    inhibited = None if enabled is None else (not enabled)

    if gps_hw_code is not None:
        verdict = GPS_HW_FAIL
    elif inhibited is True:
        verdict = GPS_INHIBITED
    elif ready is False and (sats or 0) == 0:
        verdict = GPS_NO_FIX
    elif ready is True:
        verdict = GPS_OK
    else:
        verdict = "unknown"

    return {
        "verdict": verdict,
        "valid": ready,
        "sats": sats,
        "inhibited": inhibited,
        "inhibitEvidence": inhibit_raw,
        "hwCode": gps_hw_code,
        # A GPS hardware fault may only be concluded when the dish itself
        # announced a GPS code AND GPS was not inhibited at the same time.
        "canConcludeHwFault": gps_hw_code is not None and inhibited is not True,
    }


def _hardware_page(status, gps):
    """Map the 8 hardware components to gRPC-announced evidence."""
    hw_codes = [c for c in status.get("alert_hw_codes", []) if c != 0]
    alerts = status.get("_alerts_bool", {})

    def has_code(comp_en):
        return [c for c in hw_codes if code_label(c)[0] == comp_en]

    def comp(key, st, code=None, note=None):
        return {"key": key, "status": st, "code": code, "noteAr": note}

    out = []

    # CPU Voltage — unsafe voltage is announced as code 9
    v = has_code("UNSAFE_VOLTAGE")
    out.append(comp("cpu_voltage", "fail" if v else "ok", v[0] if v else None,
                    "جهد غير آمن مُعلَن من الطبق" if v else "لا تنبيه جهد معلن"))

    # DBF / AAP — no dedicated surface in the gRPC schema we pin
    out.append(comp("dbf_aap", "na", None,
                    "لا يوجد كود gRPC مباشر لهذه الوحدة في المخطط المثبت"))

    # Ethernet PHY — slow ethernet alert + eth_speed_mbps
    slow = has_code("SLOW_ETHERNET") or alerts.get("alert_slow_ethernet_speeds")
    eth = status.get("eth_speed_mbps")
    if slow:
        out.append(comp("eth_phy", "warn", 7,
                        "تنبيه إيثرنت بطيء مُعلَن" + (
                            " — السرعة %s Mbps" % eth if eth else "")))
    else:
        out.append(comp("eth_phy", "ok" if eth is None or eth >= 100 else "warn",
                        None, "سرعة الارتباط: %s" % ("%d Mbps" % eth if eth else "غير معروفة")))

    # RF — SNR flags + last-chain code + drop rate
    rf_bad = status.get("is_snr_persistently_low") or has_code("LAST_CHAIN_NON_IDLE")
    rf_warn = (status.get("is_snr_above_noise_floor") is False) or rf_bad
    out.append(comp("rf", "fail" if rf_bad else ("warn" if rf_warn else "ok"),
                    (rf_bad[0] if isinstance(rf_bad, list) and rf_bad else None),
                    "SNR تحت حد الضجيج" if rf_warn and not rf_bad else (
                        "كود سلسلة RF مُعلَن" if rf_bad else "SNR سليم")))

    # GPS — from the GPS verdict
    if gps["verdict"] == GPS_HW_FAIL:
        out.append(comp("gps", "fail", gps["hwCode"], "فشل اختبار عتاد GPS"))
    elif gps["verdict"] == GPS_INHIBITED:
        out.append(comp("gps", "warn", None, "GPS موقوف عمداً (inhibited)"))
    elif gps["verdict"] == GPS_NO_FIX:
        out.append(comp("gps", "warn", None, "لا أقمار — لا إصلاح للموقع"))
    elif gps["verdict"] == GPS_OK:
        out.append(comp("gps", "ok", None, "GPS يعمل"))
    else:
        out.append(comp("gps", "na", None, "لا بيانات GPS في هذه الحالة"))

    # IMU — no dedicated surface
    out.append(comp("imu", "na", None,
                    "لا يوجد كود gRPC مباشر لهذه الوحدة في المخطط المثبت"))

    # Temperature — thermal codes / alerts
    t_shutdown = has_code("THERMAL_SHUTDOWN") or alerts.get("alert_thermal_shutdown")
    t_throttle = has_code("THERMAL_THROTTLE") or alerts.get("alert_thermal_throttle")
    heating = has_code("IS_HEATING") or alerts.get("alert_is_heating") or status.get("alert_hw_codes") and 8 in status.get("alert_hw_codes", [])
    if t_shutdown:
        out.append(comp("temperature", "fail", 1, "إيقاف حراري مُعلَن"))
    elif t_throttle:
        out.append(comp("temperature", "warn", 2, "خفض حراري للأداء"))
    elif heating:
        out.append(comp("temperature", "info", 8, "التسخين نشط (وضع الشتاء)"))
    else:
        out.append(comp("temperature", "ok", None, "لا تنبيهات حرارية"))

    # Power — power-related codes
    p_codes = has_code("POWER_SUPPLY_IDLE") + has_code("UNSAFE_VOLTAGE") + has_code("BATTERY_USAGE")
    if any(code_label(c)[1] == "جهد غير آمن" for c in p_codes):
        out.append(comp("power", "fail", 9, "جهد غير آمن"))
    elif p_codes:
        out.append(comp("power", "warn", p_codes[0], "تنبيه طاقة مُعلَن"))
    else:
        out.append(comp("power", "ok", None, "لا تنبيهات طاقة"))

    return out


def run(status, obstruction, alerts_bool, stats, net=None):
    """Run the full assessment.

    Args:
        status: status_data()[0] dict plus V2 extensions (alert_hw_codes,
            gps_inhibit_raw, eth_speed_mbps, is_snr_persistently_low).
        obstruction: status_data()[1] dict.
        alerts_bool: status_data()[2] dict (alert_* booleans).
        stats: history_stats() 7-dict tuple (general, ping_drop, runs,
            latency, loaded, usage).
        net: optional dict from the Kotlin network prober:
            {phoneIp, gateway, dishPingOk, tcp9200Ok, grpcOk, popLatencyMs}.

    Returns the assessment dict (JSON-able).
    """
    status = dict(status)
    status["_alerts_bool"] = alerts_bool or {}

    steps = []
    hw_codes = [c for c in status.get("alert_hw_codes", []) if c != 0]
    hard_codes = [c for c in hw_codes if CODE_TABLE.get(c, {}).get("severity") == "hard"]
    warn_codes = [c for c in hw_codes if CODE_TABLE.get(c, {}).get("severity") == "warn"]

    gps = assess_gps(status)

    # ── 1) Hardware Self-Test ────────────────────────────────────────────
    if hard_codes:
        self_status = "FAILED"
    elif warn_codes or any(v for k, v in (alerts_bool or {}).items() if isinstance(v, bool)):
        self_status = "WARN"
    elif not status.get("alert_hw_codes"):
        self_status = "UNKNOWN"
    else:
        self_status = "PASSED"

    primary_code = hard_codes[0] if hard_codes else (warn_codes[0] if warn_codes else None)
    comp_en, comp_ar = code_label(primary_code) if primary_code is not None else (None, None)

    steps.append(_step(
        "self_test", "Hardware Self-Test", "الاختبار الذاتي للعتاد",
        "fail" if self_status == "FAILED" else ("warn" if self_status == "WARN" else ("pass" if self_status == "PASSED" else "info")),
        {
            "codes": hw_codes,
            "primaryCode": primary_code,
            "component": comp_en,
            "alertsBitfield": status.get("alerts"),
            "alertFlags": {k: v for k, v in (alerts_bool or {}).items() if v},
        },
        (
            "الطبق يعلن كوداً صلباً: %d (%s)" % (primary_code, comp_ar)
            if self_status == "FAILED"
            else "تنبيهات تشغيلية غير صارمة" if self_status == "WARN"
            else "لا أكواد عتاد معلنة"
        ),
    ))

    # ── 2) Component-specific follow-up (the GPS chain) ──────────────────
    if primary_code is not None and comp_en == "GPS":
        g = gps
        steps.append(_step(
            "gps_valid", "GPS Valid?", "هل GPS صالح؟",
            "fail" if g["valid"] is False else "pass",
            {"gpsReady": g["valid"], "gpsSats": g["sats"]},
            "الطبق يعلن صلاحية GPS = %s" % g["valid"],
        ))
        steps.append(_step(
            "gps_sats", "GPS Satellites?", "عدد الأقمار الملتقطة",
            "fail" if (g["sats"] or 0) == 0 else "pass",
            {"sats": g["sats"]},
            "الأقمار المعلنة = %s" % g["sats"],
        ))
        inhibit_ev = dict(g["inhibitEvidence"] or {})
        inhibited = g["inhibited"]
        steps.append(_step(
            "gps_inhibited", "GPS Inhibited?", "هل GPS موقوف عمداً؟",
            "info" if inhibited is None else ("warn" if inhibited else "pass"),
            {
                "inhibited": inhibited,
                "gpsEnabled": None if inhibited is None else (not inhibited),
                "evidence": inhibit_ev,
            },
            (
                "التوقف موثّق (inhibit) — هذا يفسر غياب الأقمار دون افتراض عطل"
                if inhibited is True
                else "GPS مفعل ولا يوجد inhibit معلن" if inhibited is False
                else "حالة الـ inhibit غير معروفة في هذه الحالة"
            ),
        ))

    # ── 3) Initialization / boot state ───────────────────────────────────
    st = status.get("state") or "UNKNOWN"
    uptime = status.get("uptime") or 0
    if st in ("BOOTING", "MOVING_TO_POSITION"):
        init_status, init_note = "warn", "الجهاز ما يزال في مرحلة الإقلاع/التحريك"
    elif st in ("SEARCHING",):
        init_status, init_note = "warn", "البحث عن شبكة — لا اتصال بالأقمار بعد"
    elif st == "CONNECTED":
        init_status, init_note = "pass", "الجهاز متصل"
    elif st == "THERMAL_SHUTDOWN":
        init_status, init_note = "fail", "إيقاف حراري"
    elif st == "STOWED":
        init_status, init_note = "info", "الطبق في وضع التخزين (stowed)"
    else:
        init_status, init_note = "info", "حالة غير معروفة (%s)" % st
    steps.append(_step(
        "init", "Initialization", "الإقلاع والتشغيل", init_status,
        {"state": st, "uptimeS": uptime,
         "uptimeHuman": _fmt_uptime(uptime)},
        init_note + (" — إقلاع حديث (%d ث)" % uptime if uptime and uptime < 600 else ""),
    ))

    # ── 4) RF / PHY ──────────────────────────────────────────────────────
    drop = status.get("pop_ping_drop_rate")
    rf_ev = {
        "isSnrAboveNoiseFloor": status.get("is_snr_above_noise_floor"),
        "isSnrPersistentlyLow": status.get("is_snr_persistently_low"),
        "popPingDropRate": drop,
        "currentlyObstructed": status.get("currently_obstructed"),
        "fractionObstructed": status.get("fraction_obstructed"),
        "popPingLatencyMs": status.get("pop_ping_latency_ms"),
    }
    if status.get("is_snr_persistently_low"):
        rf_status, rf_note = "fail", "SNR منخفض بشكل مستمر — مشكلة RF محتملة"
    elif status.get("is_snr_above_noise_floor") is False:
        rf_status, rf_note = "warn", "SNR تحت حد الضجيج"
    elif (drop or 0) > 0.03:
        rf_status, rf_note = "warn", "نسبة فقد ping مرتفعة (%.1f%%)" % (drop * 100)
    else:
        rf_status, rf_note = "pass", "قناة RF سليمة"
    steps.append(_step("rf_phy", "RF / PHY", "الترددات والقناة الفيزيائية",
                       rf_status, rf_ev, rf_note))

    # ── 5) Network path (when the prober ran) ────────────────────────────
    if net:
        grpc_ok = net.get("grpcOk")
        tcp_ok = net.get("tcp9200Ok")
        if grpc_ok is True:
            n_status, n_note = "pass", "الاتصال gRPC بالطبق يعمل"
        elif grpc_ok is False and tcp_ok is True:
            n_status, n_note = "fail", "منفذ 9200 مفتوح لكن gRPC فشل — خدمة غير متوقعة أو firmware مختلف"
        elif tcp_ok is False:
            n_status, n_note = "fail", "لا يمكن الوصول إلى الطبق على 192.168.100.1:9200 — المسار شبكي مبتور"
        else:
            n_status, n_note = "info", "نتيجة فحص الشبكة غير حاسمة"
        steps.append(_step(
            "net_path", "Network Path", "مسار الشبكة", n_status,
            {
                "phoneIp": net.get("phoneIp"),
                "gateway": net.get("gateway"),
                "dishTcp9200": tcp_ok,
                "grpc": grpc_ok,
                "popLatencyMs": net.get("popLatencyMs"),
            },
            n_note,
        ))

    # ── 6) History corroboration ─────────────────────────────────────────
    gen, ping_drop, runs, latency = stats[0], stats[1], stats[2], stats[3]
    if gen.get("samples", 0) > 0:
        total = ping_drop.get("total_ping_drop") or 0.0
        full = ping_drop.get("count_full_ping_drop") or 0
        h_status = "fail" if full > 0 else ("warn" if total > 0.02 else "pass")
        steps.append(_step(
            "history", "History Stats", "إحصاءات النافذة الأخيرة", h_status,
            {
                "samples": gen.get("samples"),
                "totalPingDrop": round(total, 4),
                "countFullPingDrop": full,
                "meanAllPingLatency": latency.get("mean_all_ping_latency"),
                "meanFullPingLatency": latency.get("mean_full_ping_latency"),
                "downloadUsageMB": round((stats[5].get("download_usage") or 0) / 1e6, 1),
                "uploadUsageMB": round((stats[5].get("upload_usage") or 0) / 1e6, 1),
            },
            "فقد إجمالي %.2f%% عبر %d عينة، انقطاعات كاملة: %d"
            % (total * 100, gen.get("samples", 0), full),
        ))

    # ── Final assessment ─────────────────────────────────────────────────
    # "failed" means a real fault: hardware self-test failure or a failed
    # functional step (boot/rf/network). A completed outage inside the
    # history window (history step fail) is network weather, not a fault —
    # it shapes the verdict text but not the failed flag.
    core_fail_ids = ("self_test", "init", "rf_phy", "net_path")
    failed_steps = [s for s in steps if s["status"] == "fail" and s["id"] in core_fail_ids]
    warn_steps = [s for s in steps if s["status"] == "warn"]

    if self_status == "FAILED":
        if comp_en == "GPS" and gps["verdict"] == GPS_INHIBITED:
            verdict_ar = (
                "فشل الاختبار الذاتي بكود GPS (%d)، لكن GPS موقوف عمداً حالياً — "
                "لذلك لا يمكن إثبات تلف عتاد GPS قبل إعادة تفعيله وإعادة الاختبار."
                % (gps["hwCode"] or primary_code)
            )
            next_tests = [
                "أعد تفعيل GPS (من تطبيق Starlink الرسمي: إلغاء inhibit) ثم أعد التشخيص الكامل",
                "بعد التفعيل: راقب gps_sats — إن بقيت 0 مع تفعيل GPS واستمرار الكود 14 فاحتمال عطل عتاد يصبح مرجحاً جداً",
                "أعد تشغيل الطبق (Restart) وأعد التشخيص قبل أي قرار استبدال",
            ]
        elif comp_en == "GPS":
            verdict_ar = (
                "فشل اختبار عتاد GPS (كود %d): Valid=%s، الأقمار=%s، inhibit=%s. "
                "الدليل يشير إلى مشكلة GPS، لكن يُنصح بإعادة التشغيل وإعادة الاختبار قبل الاستبدال."
                % (gps["hwCode"] or primary_code, gps["valid"], gps["sats"], gps["inhibited"])
            )
            next_tests = [
                "أعد تشغيل الطبق (Restart) ثم أعد التشخيص الكامل",
                "تأكد أن الطبق يعمل بأحدث firmware ثم أعد الاختبار",
                "إن تكرر الكود بعد إعادة التشغيل: تواصل مع الدعم مع تقرير التشخيص PDF",
            ]
        else:
            verdict_ar = "فشل الاختبار الذاتي: كود %d (%s)" % (primary_code, comp_ar)
            next_tests = ["أعد التشغيل وأعد التشخيص الكامل", "أرسل تقرير PDF إلى الدعم"]
    elif failed_steps:
        verdict_ar = "لم يفشل الاختبار الذاتي، لكن هناك فشلاً وظيفياً: %s" % (
            "؛ ".join(s["titleAr"] for s in failed_steps)
        )
        next_tests = ["أعد التشخيص بعد معالجة الفشل الوظيفي المذكور"]
    elif warn_steps:
        verdict_ar = "الحالة تعمل مع تحفظات: %s" % (
            "؛ ".join(s["titleAr"] for s in warn_steps)
        )
        next_tests = ["راقب المؤشرات عبر شاشة المراقبة المباشرة"]
    else:
        verdict_ar = "لا توجد مؤشرات فشل في البيانات المعلنة من الطبق"
        next_tests = []

    return {
        "ts": None,  # filled by bridge
        "selfTest": {
            "status": self_status,
            "code": primary_code,
            "component": comp_en,
            "componentAr": comp_ar,
            "codes": hw_codes,
        },
        "gps": gps,
        "hardware": _hardware_page(status, gps),
        "steps": steps,
        "final": {
            "verdictAr": verdict_ar,
            "nextTests": next_tests,
            "canConcludeHwFault": (
                gps["canConcludeHwFault"]
                if primary_code is not None and comp_en == "GPS"
                else bool(hard_codes)
            ),
            "failed": bool(hard_codes or failed_steps),
        },
    }


def network_verdict(net):
    """Standalone verdict for the Network Diagnostics page."""
    hops = []
    phone_ip = net.get("phoneIp")
    hops.append({
        "hop": "phone",
        "labelAr": "الهاتف",
        "ok": bool(phone_ip),
        "detail": phone_ip or "لا يوجد عنوان على واجهة Wi-Fi",
    })
    gw = net.get("gateway")
    hops.append({
        "hop": "gateway",
        "labelAr": "الراوتر (بوابة الهاتف)",
        "ok": bool(gw),
        "detail": gw or "غير معروف",
    })
    tcp_ok = net.get("tcp9200Ok")
    hops.append({
        "hop": "dish_tcp",
        "labelAr": "الطبق TCP 192.168.100.1:9200",
        "ok": bool(tcp_ok),
        "detail": "منفذ مفتوح" if tcp_ok else "لا وصول — مسار الشبكة مبتور أو الراوتر لا يوجّه للطبق",
    })
    grpc_ok = net.get("grpcOk")
    hops.append({
        "hop": "dish_grpc",
        "labelAr": "خدمة gRPC (Handle)",
        "ok": bool(grpc_ok),
        "detail": "استجابة gRPC سليمة" if grpc_ok else "فشل استدعاء gRPC",
    })
    pop = net.get("popLatencyMs")
    if pop is not None:
        hops.append({
            "hop": "pop",
            "labelAr": "زمن الوصول إلى POP (من الطبق)",
            "ok": True,
            "detail": "%.1f ms" % pop,
        })

    if grpc_ok:
        verdict = "المسار الهاتف ← الراوتر ← الطبق ← gRPC يعمل بالكامل"
    elif tcp_ok:
        verdict = "الطبق مفتوح على 9200 لكن gRPC يفشل — تحقق من firmware أو أعد المحاولة"
    elif gw:
        verdict = "لا وصول إلى الطبق عبر الراوتر — قد يتطلب راوتر غير ستارلينك إعداد توجيه إضافي إلى 192.168.100.1"
    else:
        verdict = "تحقق من اتصال الهاتف بشبكة راوتر ستارلينك أولاً"

    return {"hops": hops, "verdictAr": verdict}


def _fmt_uptime(seconds):
    try:
        s = int(seconds)
    except (TypeError, ValueError):
        return "—"
    if s <= 0:
        return "00:00:00"
    h, rem = divmod(s, 3600)
    m, sec = divmod(rem, 60)
    if h >= 24:
        d, h = divmod(h, 24)
        return "%dd %02d:%02d:%02d" % (d, h, m, sec)
    return "%02d:%02d:%02d" % (h, m, sec)
