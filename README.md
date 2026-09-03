# Starlink Diagnostic Pro — V2.2

تطبيق أندرويد أصلي (Kotlin + Chaquopy + Python) لتشخيص طبق Starlink مباشرة عبر gRPC
من `192.168.100.1:9200` — بلا خادم وسيط، بلا إنترنت، بلا سحابة.

العميل gRPC يتبنى سلوك [starlink-grpc-tools](https://github.com/sparky8512/starlink-grpc-tools)
(نفس نداءات `Handle` ونفس دلالات ring buffer)، مع **مخطط حقول محاذى للبروتوكول
الحالي (API v42، firmware 2026.x)** مُتحقق من الكود المولّد الحديث. انظر
`docs/GRPC.md` للتفاصيل وخريطة الفروق بين المخطط القديم والجديد.

## الهدف النهائي (اختبار القبول)

```
APK → Wi-Fi → 192.168.100.1:9200 → gRPC → Dish
     → GetStatus / GetHistory / Diagnostics → UI
```

لا تُعتبر V2 مكتملة إلا إذا وصل التطبيق فعلياً إلى الطبق وأظهر التشخيص الفعلي.

## المزايا

| # | الميزة | الشاشة |
|---|--------|--------|
| 1 | لوحة رئيسية حقيقية (DISH / SELF-TEST / حالة حية / NETWORK + أزرار) | dashboard |
| 2 | محرك تشخيص بسلسلة دليل (Self-Test → Code → Component → GPS chain → RF/PHY → إسناد الانقطاع → المحاذاة → الطاقة → Final) | diagnostics |
| 3 | مراقبة مباشرة بفواصل 1/5/10/30/60 ثانية | live |
| 4 | قاعدة بيانات محلية `StarlinkDiagnostic.db` + رسوم (Download/Upload/Latency/Loss) | history |
| 5 | خريطة عتاد 8 مكونات مرتبطة بأكواد gRPC المعلنة | hardware |
| 6 | صفحة GPS/GNSS تفرّق بين **unavailable / inhibited / hardware failure** | gps |
| 7 | عارض Raw (Status/History/Alerts/Obstruction/Diagnostics) مع Copy/Export JSON | raw |
| 8 | تشخيص شبكة قفزة-بقفزة + أهداف ICMP موسعة (الطبق/الراوتر/محللات عامة) | network |
| 9 | أوامر الطبق (Restart/Stow/Unstow + تفعيل GPS + جدولة النوم) مع تأكيد | control |
| 10 | تقرير تشخيص PDF احترافي (Starlink_Diagnostic_Report.pdf) | من شاشة التشخيص |

### إضافات V2.2 — الاحترافية والدقة والشمول

| الميزة | التفصيل |
|--------|---------|
| إحصاءات دقيقة p50/p95/p99 + Jitter | نافذة 300 عينة (~5 دقائق) تُحسب من buffer الطبق مباشرة بلا نداء إضافي؛ الكمون على عينات متصلة فقط كي لا يسمم الانقطاع المئينيات |
| مؤشر جودة تدفق البيانات (Freshness) | رصد تجمّد `end_counter` ≥ 15 ثانية — إنذار مبكر أن الطبق متصل لكنه توقف عن بث العينات (قبل انقطاع الاتصال الكامل) |
| اتجاهات طويلة المدى | توفر الخدمة %، عدد الانقطاعات، كمون p50/p95، واتجاه الكمون/التنزيل (تحسن/مستقر/تراجع) عبر نوافذ 6 ساعات / 24 ساعة / 7 أيام من القاعدة المحلية |
| صحة اتصال ذكية | شريط حالة مباشر في اللوحة + عداد المحاولات الفاشلة + تراجع تصاعدي في الاستقصاء (×2/×4/×8 بحد أقصى 120 ثانية) بدل قصف رابط ميت |
| تصدير CSV | تصدير كامل السجل (حتى 7 أيام) إلى ملف CSV ومشاركته عبر قائمة النظام — من قاعدة البيانات المحلية مباشرة |
| معالج أول تشغيل | 3 خطوات: الاتصال بشبكة الطبق → جاهزية الطبق → أول تحديث حالة (مع إمكانية إعادته من الإعدادات) |
| شاشة «حول» | خريطة البروتوكول وسجل الإصدارات والمرجع المجتمعي داخل التطبيق |
| تقرير PDF موسع | قسم «Network Quality (window)» بالأوزان الجديدة + قسم «V42 Evidence» (الانقطاع/الإيقاف/التحديث/التقييد/المحاذاة/الطاقة) |

### إضافات V2.1 (من بحث GitHub عن أدوات المجتمع)

| الميزة | الأساس |
|--------|--------|
| خريطة عرقلة قطبية 12×123 (RPC 2008) | نمط Dishylink وأدوات الخريطة المجتمعية |
| إسناد أسباب الانقطاع من سجل الطبق نفسه (History.outages=1009) | نمط stardashy |
| الطاقة: سحب الطبق/الراوتر لحظياً (upsu_stats=1043) + kWh من power_in=1010 | نمط Dishylink |
| عدادات المحاذاة: الفعلي مقابل desired boresight (alignment_stats=1027) | نمط Dishylink |
| شارات تقييد النطاق (1044/1045) وحالة التحديث (1021) ورمز الإيقاف (1024) | مخطط v42 |
| تشخيص الراوتر gRPC (wifi_get_status=3004 + قائمة الأجهزة) | نمط starlink-cli |
| اختبار سرعة من الطبق نفسه نحو الـ POP (start_speedtest=1027) — بلا إنترنت على الهاتف | best-effort بديل لاختبارات السحابة |
| أهداف ping كما يقيسها الطبق (get_ping=1009) | مخطط v42 |
| الكمون تحت الحمل مقابل بلا حمل (load buckets) | مدمج من history_stats |

## بنية المشروع

```
StarlinkDiagnostic/
├── android/
│   ├── app/src/main/
│   │   ├── kotlin/com/starlink/diagnostic/
│   │   │   ├── bridge/          # PythonBridge (نقطة استدعاء واحدة)
│   │   │   ├── grpc/            # (محجوز لطبقة الاتصال من جهة Kotlin)
│   │   │   ├── diagnostics/     # Models + NetworkProber
│   │   │   ├── history/         # (السجل يُدار من Python sqlite3)
│   │   │   ├── export/          # ReportGenerator (PDF) + JsonExporter
│   │   │   └── ui/              # Compose: 11 شاشة + ثيم فضائي + RTL
│   │   ├── assets/sample/       # عينة جهازك الحقيقية (GPS Code 14)
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── python/                      # مصدر Chaquopy (srcDir("../../python"))
│   ├── starlink_grpc/           # طبقة الاتصال — مشتقة من v1.2.5
│   │   └── core.py              # ChannelContext, get_status, get_history,
│   │                            # history_stats, reboot, set_stow_state …
│   ├── bridge.py                # نقطة الاستدعاء الوحيدة من Kotlin (rpc)
│   ├── diagnostics.py           # محرك التشخيص + جدول الأكواد
│   ├── history.py               # StarlinkDiagnostic.db (sqlite3)
│   ├── demo_sim.py              # وضع العرض + عينة GPS14
│   └── requirements.txt
├── docs/
│   ├── GRPC.md                     # اشتقاق الطبقة + خريطة v42 + الفروق
│   └── spacex_api_device.proto     # المخطط المحاذى لـ v42 (مُتحقق من الكود المولّد)
└── README.md
```

> ملفات `spacex_api_device_pb2*.py` مولّدة مسبقاً بـ grpcio-tools 1.59.3
> (مطابقة لإصدار grpcio المثبت 1.59.3) وتوضع في `python/`.

## البناء

### المتطلبات
- Android Studio (Hedgehog أو أحدث) + JDK 17
- `python3` (3.8–3.12) على PATH — يحتاجه Chaquopy وقت البناء (`buildPython`)
- اتصال إنترنت للمزامنة الأولى (Google Maven + Chaquopy pip repo)

### خطوات
1. افتح Android Studio → **Open** → مجلد `StarlinkDiagnostic/android`
2. انتظر مزامنة Gradle (أول مرة تنزّل grpcio للأنرويد من مستودع Chaquopy)
3. `Run ▶` على الجهاز/المحاكي — أو **Build → Build APK(s)**

### بناء سحابي (بلا تثبيت محلي)
ارفع المستودع إلى GitHub وأضف workflow:

```yaml
name: build-apk
on: [push, workflow_dispatch]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - uses: actions/setup-python@v5
        with: { python-version: '3.11' }
      - uses: android-actions/setup-android@v3
      - run: cd android && chmod +x gradlew && ./gradlew assembleDebug
      - uses: actions/upload-artifact@v4
        with: { name: starlink-diagnostic-apk, path: android/app/build/outputs/apk/debug/app-debug.apk }
```

## اختبار القبول على شبكتك (مهم)

1. وصّل الهاتف بشبكة **راوتر ستارلينك** (Wi-Fi).
2. افتح التطبيق → ستظهر حالة الاتصال تلقائياً من `get_status`.
3. افتح **NETWORK** → «فحص المسار الآن»:
   - `Dish TCP 9200` فشل؟ → المشكلة في المسار (الراوتر/العزل بين العملاء)،
     راجع ملاحظة README الأصلي: الراوتر غير الخاص بستارلينك قد يتطلب routing
     إضافياً للوصول إلى 192.168.100.1.
   - المنفذ مفتوح و`gRPC` فشل؟ → firmware مختلف أو خدمة غير متوقعة —
     أرسل صفحة **RAW → Status** (Copy JSON) للتحليل.
4. **تشخيص كامل** → سلسلة الدليل + التقييم النهائي + تقرير PDF.
5. **مراقبة مباشرة** (اختر 1 ثانية) ثم **السجل** لرؤية الرسوم من القاعدة المحلية.

### بلا طبق؟
- الإعدادات → «وضع العرض التجريبي» لمحاكاة واقعية.
- الإعدادات → «تحميل عينة الجهاز الحقيقية (GPS Code 14)» لإعادة إنتاج حالة
  جهازك: `rev3_proto2` / `2026.08.20.mr85023.1` / Self-Test FAILED / GPS
  valid=false, sats=0, inhibited=true — ويبني عليها محرك التشخيص نفس
  الاستنتاج الموثق في `docs/DIAGNOSTICS.md`.

## الخصوصية
كل شيء محلي: gRPC إلى الطبق على شبكتك المحلية، قاعدة SQLite في ملفات التطبيق
الخاصة، وتقرير PDF يُولَّد على الجهاز. لا إنترنت ولا تحليلات ولا سحابة.

## الترخيص والإسناد
- مشتق من starlink-grpc-tools (MIT) — (c) sparky8512 والمساهمون.
- مخطط البروتوكول مُحقق ضد protoset الطبق (انظر رأس `docs/spacex_api_device.proto`).
