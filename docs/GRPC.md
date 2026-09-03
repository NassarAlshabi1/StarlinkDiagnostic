# طبقة gRPC — الاشتقاق والمحاذاة مع البروتوكول الحالي (API v42)

هذه الوثيقة تشرح كيف بُنيت طبقة الاتصال في V2.1 وما الفروق الموثقة عن
[sparky8512/starlink-grpc-tools](https://github.com/sparky8512/starlink-grpc-tools).

## المصادر

1. **السلوك والدلالات**: starlink-grpc-tools v1.2.5 (`starlink_grpc.py`) —
   نفس توقيعات الدوال، نفس مفاتيح قواميس البيانات، ونفس دلالات ring buffer
   (`index = counter % 9000`) في `history_stats`.
2. **تخطيط الحقول (wire format)**: الكود المولّد الحديث من
   [joshuasing/starlink_exporter](https://github.com/joshuasing/starlink_exporter)
   (API v42، firmware 2026.06.22) — استُخرجت أرقام الحقول وأنواعها من
   struct tags في `dish.pb.go` / `device.pb.go` / `common.pb.go` / `wifi.pb.go`
   وحُققت حقل-بحقل قبل كتابة `docs/spacex_api_device.proto`.

لماذا لا نستخدم gRPC reflection مثل الأصل؟ لأن التطبيق يعمل بلا أي خدمات
خارجية؛ الاعتماد على reflection يعني رحلة إضافية عند كل إقلاع بارد.
`spacex_api_device.proto` المدمج مُجمّع مسبقاً إلى `pb2` بنفس إصدار
`grpcio` المثبت (1.59.3) فيعطي نفس الأسلاك دون reflection.

## الفروق الجوهرية بين مخطط 2021/2022 القديم وتخطيط v42

| الموضع | القديم (ما كان في V2.0) | v42 (الآن) |
|--------|--------------------------|------------|
| History 1005-1007 | snr/scheduled/obstructed (repeated) | أُزيلت من الخدمة |
| History 1009 | `uptime` (varint) | `repeated DishOutage outages` (message) |
| History 1010 | `redirect_start_timestamp` (varint) | `power_in` (repeated float — واط/عينة) |
| Status 1006 | `state` (enum DishState) | أُزيل — الحالة تُشتق من `outage` |
| Status 1001 | `snr` | أُزيل |
| Status 1013 → 1015 | gps_stats | gps_stats (وفيه `inhibit_gps=4` حقيقي الآن) |
| Status 1014 → 1016 | eth_speed_mbps | eth_speed_mbps |
| Status 1015 → 1018 | is_snr_above_noise_floor | is_snr_above_noise_floor |
| Status 1016 → 1022 | is_snr_persistently_low | is_snr_persistently_low |
| Status 1017 | alerts_hardware (repeated Alert) | أُزيل — 1017 أصبح mobility_class |
| جديد | — | outage=1014، software_update_state=1021، disablement_code=1024، software_update_stats=1026، alignment_stats=1027، swupdate_reboot_ready=1030، reboot_reason=1032، upsu_stats=1043 (قدرة الطبق/الراوتر)، dl/ul_bandwidth_restricted_reason=1044/1045، battery_stats=1054 |

> **لماذا كان هذا مصفوف خطراً في V2.0؟** protobuf يعامل تعارض wire-type
> (varint مقابل length-delimited) كحقل مجهول — لا انهيار، لكن الحقول
> المعلنة لدينا كانت ترجع فارغة بصمت على الأجهزة الحديثة، و1017 القديم
> كان يمكن أن يُقرأ كأكواد عتاد وهمية. المحاذاة إلى v42 أعادت الدقة.

## أرقام الطلبات المستخدمة

| الطلب | الرقم | الغرض |
|-------|-------|-------|
| get_status | 1004 | حالة الطبق اللحظية |
| get_history | 1007 | مخزن العينات (9000 عينة × 1 ث) |
| reboot | 1001 | إعادة تشغيل |
| dish_stow | 2002 | طوي/فك (unstow=true) |
| dish_get_obstruction_map | 2008 | خريطة العرقلة القطبية (12×123 SNR) |
| start_speedtest | 1027 | اختبار سرعة من الطبق نفسه نحو الـ POP |
| get_speedtest_status | 1028 | حالة/نتائج اختبار السرعة |
| get_ping | 1009 | أهداف ping كما يقيسها الطبق |
| dish_power_save | 2013 | جدولة نوم/توفير الطاقة |
| dish_inhibit_gps | 2014 | توقف/تفعيل GPS |
| wifi_get_status | 3004 | حالة الراوتر (على 192.168.1.1:9000) |
| wifi_get_clients | 3002 | قائمة أجهزة الراوتر |

الاستجابة تأتي في نفس نداء `Device.Handle` عبر oneof الرد
(dish_get_status=2004، dish_get_history=2006، …).

## اشتقاق الحالة (state)

حقل `state` لم يعد موجوداً في v42، فالاشتقاق مطابق لما يفعله
starlink-grpc-tools الحالي مع تحسين واحد:

- `outage.duration_ns == 0` → انقطاع **جارٍ**: الحالة = سببه
  (استثناء `NO_SCHEDULE` يُترجم `SEARCHING` كما في الأصل).
- `outage.duration_ns > 0` → انقطاع **منتهٍ** لا يزال مُرفقاً بالحالة → `CONNECTED`.

## خرائط التعدادات (أهم القيم)

- **DishOutage.Cause**: BOOTING=1، STOWED=2، THERMAL_SHUTDOWN=3،
  NO_SCHEDULE=4، NO_SATS=5، OBSTRUCTED=6، NO_DOWNLINK=7، NO_PINGS=8،
  ACTUATOR_ACTIVITY=9، CABLE_TEST=10، SLEEPING=11، SKY_SEARCH=13، INHIBIT_RF=14.
- **UtDisablementCode** (1024): OKAY=1، NO_ACTIVE_ACCOUNT=2،
  TOO_FAR_FROM_SERVICE_ADDRESS=3، IN_OCEAN=4، BLOCKED_COUNTRY=6،
  DATA_OVERAGE_SANDBOX_POLICY=7، CELL_IS_DISABLED=8، ROAM_RESTRICTED=10،
  UNKNOWN_LOCATION=11، ACCOUNT_DISABLED=12، UNSUPPORTED_VERSION=13،
  MOVING_TOO_FAST_FOR_POLICY=14، UNDER_AVIATION_FLYOVER_LIMITS=15، BLOCKED_AREA=16.
- **SoftwareUpdateState** (1021): IDLE=1، FETCHING=2، PRE_CHECK=3، WRITING=4،
  POST_CHECK=5، REBOOT_REQUIRED=6، DISABLED=7، FAULTED=8.
- **RateLimitReason** (1044/1045): NO_LIMIT=1، POLICY_LIMIT=2،
  USER_CUSTOM_LIMIT=3، OVERAGE_LIMIT=5، LOW_SPEED_POLICY_LIMIT=6.

## خريطة العرقلة (2008)

الاستجابة: `num_rows=12` حلقة ارتفاع (الصف 0 = أدنى ارتفاع `min_elevation_deg`
≈ 25° وهو الحلقة الخارجية، آخر صف = السمت/المركز)، `num_cols=123` قطاع
سمت (360°/123)، و`snr` قائمة طولها 12×123. القيم ≤ 0 تعني «لا بيانات».

## فروق موثقة عن الأصل (starlink-grpc-tools)

1. pb2 مُجمّع مسبقاً بدل reflection (نفس الأسلاك، إقلاع أسرع، بلا yagrc).
2. الأسطح غير المستخدمة غير منقولة (location، sleep config عبر yagrc،
   سكربتات Influx/SQLite).
3. `history_power_stats()` و`outages_from_history()` إضافتان V2.1 فوق
   واجهة الأصل لبناء إحصاءات الطاقة وإسناد أسباب الانقطاع.
4. `optional` محلي على `DishGpsStats.inhibit_gps` وحقول ping في
   `WifiGetStatusResponse` — نفس الترميز على الأسلاك لكن يتيح HasField()
   للتمييز بين «مُعلن» و«غائب» (الجهاز الحقيقي بـ implicit presence لا
   يُرسل القيم الصفرية أصلاً).
