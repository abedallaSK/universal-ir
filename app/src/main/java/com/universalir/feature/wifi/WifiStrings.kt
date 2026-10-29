package com.universalir.feature.wifi

import com.universalir.data.wifi.WifiAcMode
import com.universalir.data.wifi.WifiDeviceKind
import com.universalir.data.wifi.WifiFan
import com.universalir.data.wifi.WifiError
import java.util.Locale

/** UI text for the Wi-Fi feature (English / Arabic / Hebrew), chosen from the current default locale. */
object WifiText {
    private fun t(en: String, ar: String, he: String) = when (Locale.getDefault().language) {
        "ar" -> ar
        "iw", "he" -> he
        else -> en
    }

    val title get() = t("Wi-Fi devices", "أجهزة Wi-Fi", "מכשירי Wi-Fi")
    val sameNetwork get() = t("Phone and device must be on the same Wi-Fi.", "يجب أن يكون الهاتف والجهاز على نفس شبكة Wi-Fi.", "הטלפון והמכשיר חייבים להיות באותה רשת Wi-Fi.")
    val notOnWifi get() = t("You're not on Wi-Fi. Connect to the same network as your TV or AC.", "لست متصلاً بشبكة Wi-Fi. اتصل بنفس شبكة التلفاز أو المكيّف.", "אינך מחובר ל-Wi-Fi. התחבר לאותה רשת של הטלוויזיה או המזגן.")
    val scanning get() = t("Scanning your network…", "جارٍ فحص الشبكة…", "סורק את הרשת…")
    val scanningHint get() = t("Looking for TVs and air conditioners", "نبحث عن أجهزة التلفاز والمكيّفات", "מחפש טלוויזיות ומזגנים")
    val scanAgain get() = t("Scan again", "فحص مجدداً", "סרוק שוב")
    val saved get() = t("Saved Wi-Fi devices", "أجهزة Wi-Fi المحفوظة", "מכשירי Wi-Fi שמורים")
    val found get() = t("Found on this network", "تم العثور عليها في هذه الشبكة", "נמצאו ברשת הזו")
    val noneTitle get() = t("No devices found", "لم يتم العثور على أجهزة", "לא נמצאו מכשירים")
    val noneTips get() = t(
        "Turn the device on and connect it to this Wi-Fi. Samsung: allow external devices in Settings › General. LG: enable \"LG Connect Apps\". Roku: allow control by mobile apps. ACs: finish setup in the maker's app first.",
        "شغّل الجهاز ووصّله بهذه الشبكة. سامسونج: اسمح بالأجهزة الخارجية من الإعدادات › عام. إل جي: فعّل \"LG Connect Apps\". روكو: اسمح بالتحكم من تطبيقات الجوال. المكيّفات: أكمل الإعداد في تطبيق الشركة أولاً.",
        "הדלק את המכשיר וחבר אותו לרשת הזו. סמסונג: אפשר מכשירים חיצוניים בהגדרות › כללי. LG: הפעל \"LG Connect Apps\". Roku: אפשר שליטה מאפליקציות. מזגנים: השלם קודם את ההגדרה באפליקציית היצרן.",
    )
    val viewOnly get() = t("Not controllable yet", "لا يمكن التحكم به بعد", "עדיין לא ניתן לשליטה")
    val unsupported get() = t("This device was found, but Wi-Fi control isn't supported for it yet.", "تم العثور على الجهاز، لكن التحكم عبر Wi-Fi غير مدعوم له بعد.", "המכשיר נמצא, אך שליטה ב-Wi-Fi עדיין לא נתמכת עבורו.")
    val rename get() = t("Rename", "إعادة تسمية", "שינוי שם")
    val name get() = t("Name", "الاسم", "שם")
    val remove get() = t("Remove", "إزالة", "הסרה")
    val removeConfirm get() = t("Remove this Wi-Fi device?", "إزالة جهاز Wi-Fi هذا؟", "להסיר את מכשיר ה-Wi-Fi הזה?")
    val more get() = t("More", "المزيد", "עוד")
    val connected get() = t("Connected", "متصل", "מחובר")
    val connecting get() = t("Connecting…", "جارٍ الاتصال…", "מתחבר…")
    val offline get() = t("Offline", "غير متصل", "לא מחובר")
    val retry get() = t("Retry", "إعادة المحاولة", "נסה שוב")
    val pairingTitle get() = t("Accept the prompt on your TV", "اقبل الطلب على شاشة التلفاز", "אשר את הבקשה בטלוויזיה")
    val pairingBody get() = t("Use the TV's own remote to allow this phone. You only need to do this once.", "استخدم ريموت التلفاز للسماح لهذا الهاتف. ستحتاج لذلك مرة واحدة فقط.", "השתמש בשלט של הטלוויזיה כדי לאשר את הטלפון. צריך לעשות זאת רק פעם אחת.")
    val deniedTitle get() = t("The TV refused the connection", "رفض التلفاز الاتصال", "הטלוויזיה דחתה את החיבור")
    val deniedBody get() = t("Allow this app in the TV's connected-device settings, then retry.", "اسمح لهذا التطبيق من إعدادات الأجهزة المتصلة في التلفاز ثم أعد المحاولة.", "אפשר את האפליקציה בהגדרות המכשירים המחוברים בטלוויזיה ונסה שוב.")
    val missingTitle get() = t("Device not found", "الجهاز غير موجود", "המכשיר לא נמצא")
    val missingBody get() = t("This Wi-Fi device is no longer saved.", "لم يعد جهاز Wi-Fi هذا محفوظاً.", "מכשיר ה-Wi-Fi הזה כבר לא שמור.")
    val readingStatus get() = t("Reading AC status…", "جارٍ قراءة حالة المكيّف…", "קורא את מצב המזגן…")
    val back get() = t("Back", "رجوع", "חזרה")

    val power get() = t("Power", "الطاقة", "הפעלה")
    val up get() = t("Up", "أعلى", "למעלה")
    val down get() = t("Down", "أسفل", "למטה")
    val left get() = t("Left", "يسار", "שמאלה")
    val right get() = t("Right", "يمين", "ימינה")
    val ok get() = t("OK", "موافق", "אישור")
    val home get() = t("Home", "الرئيسية", "בית")
    val menu get() = t("Menu", "القائمة", "תפריט")
    val input get() = t("Input", "المصدر", "מקור")
    val volume get() = t("Volume", "الصوت", "עוצמה")
    val volUp get() = t("Volume up", "رفع الصوت", "הגבר")
    val volDown get() = t("Volume down", "خفض الصوت", "הנמך")
    val mute get() = t("Mute", "كتم", "השתק")
    val channel get() = t("Channel", "القناة", "ערוץ")
    val chUp get() = t("Channel up", "القناة التالية", "ערוץ הבא")
    val chDown get() = t("Channel down", "القناة السابقة", "ערוץ קודם")
    val play get() = t("Play", "تشغيل", "נגן")
    val pause get() = t("Pause", "إيقاف مؤقت", "השהה")
    val rewind get() = t("Rewind", "ترجيع", "הרץ אחורה")
    val forward get() = t("Fast forward", "تقديم", "הרץ קדימה")

    val acOn get() = t("On", "يعمل", "פועל")
    val acOff get() = t("Off", "متوقف", "כבוי")
    val tempUp get() = t("Warmer", "أدفأ", "חם יותר")
    val tempDown get() = t("Cooler", "أبرد", "קר יותר")
    val mode get() = t("Mode", "الوضع", "מצב")
    val fan get() = t("Fan", "المروحة", "מאוורר")
    val swing get() = t("Swing", "تأرجح", "נדנוד")
    fun modeName(m: Int) = when (m) {
        0 -> t("Auto", "تلقائي", "אוטו")
        1 -> t("Cool", "تبريد", "קירור")
        2 -> t("Dry", "تجفيف", "ייבוש")
        3 -> t("Fan", "مروحة", "מאוורר")
        else -> t("Heat", "تدفئة", "חימום")
    }
    fun fanName(f: Int) = when (f) {
        0 -> t("Auto", "تلقائي", "אוטו")
        1, 2 -> t("Low", "منخفض", "נמוך")
        3, 4 -> t("Med", "متوسط", "בינוני")
        else -> t("High", "عالٍ", "גבוה")
    }

    fun kind(k: WifiDeviceKind) = when (k) {
        WifiDeviceKind.TV_SAMSUNG -> t("Samsung TV", "تلفاز سامسونج", "טלוויזיית סמסונג")
        WifiDeviceKind.TV_LG -> t("LG TV", "تلفاز إل جي", "טלוויזיית LG")
        WifiDeviceKind.TV_ROKU -> "Roku"
        WifiDeviceKind.TV_ANDROID -> t("Android TV", "تلفاز أندرويد", "Android TV")
        WifiDeviceKind.CAST -> "Chromecast / Cast"
        WifiDeviceKind.AC_GREE, WifiDeviceKind.AC -> t("Air conditioner", "مكيّف", "מזגן")
        WifiDeviceKind.OTHER -> t("Device", "جهاز", "מכשיר")
    }

    fun error(e: WifiError, detail: String?): String = when (e) {
        WifiError.TIMEOUT -> t("The device didn't answer in time.", "لم يستجب الجهاز في الوقت المحدد.", "המכשיר לא ענה בזמן.")
        WifiError.UNREACHABLE -> t("Can't reach the device. Is it on and on this Wi-Fi?", "تعذّر الوصول إلى الجهاز. هل هو يعمل وعلى هذه الشبكة؟", "לא ניתן להגיע למכשיר. האם הוא דולק ובאותה רשת?")
        WifiError.DENIED -> t("The device refused the request.", "رفض الجهاز الطلب.", "המכשיר דחה את הבקשה.")
        WifiError.UNSUPPORTED -> t("Not supported by this device.", "غير مدعوم في هذا الجهاز.", "לא נתמך במכשיר הזה.")
        WifiError.PROTOCOL -> t("Unexpected reply from the device", "رد غير متوقع من الجهاز", "תשובה לא צפויה מהמכשיר") + (detail?.let { " ($it)" } ?: "")
        WifiError.AUTH -> t("Sign-in was refused. Log in again.", "تم رفض تسجيل الدخول. سجّل الدخول مجدداً.", "הכניסה נדחתה. התחבר שוב.")
        WifiError.NOT_PAIRED -> t("Pair this TV with the app first.", "اقرن هذا التلفاز بالتطبيق أولاً.", "יש לצמד קודם את הטלוויזיה לאפליקציה.")
    }

    // ---------------------------------------------------------------- cloud / errors
    val cloudUnreachable get() = t("Can't reach the service. Check your internet connection.", "تعذّر الوصول إلى الخدمة. تحقق من اتصال الإنترنت.", "אין גישה לשירות. בדוק את החיבור לאינטרנט.")
    val adapterUnsupported get() = t("This adapter model needs the maker's app; local control isn't available.", "طراز المحوّل هذا يتطلب تطبيق الشركة؛ التحكم المحلي غير متاح.", "דגם המתאם הזה דורש את אפליקציית היצרן; שליטה מקומית לא זמינה.")

    // ---------------------------------------------------------------- Android TV pairing
    val pairTvTitle get() = t("Pair with your TV", "اقتران مع التلفاز", "צימוד עם הטלוויזיה")
    val pairTvBody get() = t("Enter the 6-character code shown on the TV.", "أدخل الرمز المكوّن من 6 خانات الظاهر على التلفاز.", "הזן את הקוד בן 6 התווים שמוצג בטלוויזיה.")
    val pairTvStarting get() = t("Asking the TV for a code…", "جارٍ طلب رمز من التلفاز…", "מבקש קוד מהטלוויזיה…")
    val pairTvVerifying get() = t("Checking the code…", "جارٍ التحقق من الرمز…", "בודק את הקוד…")
    val pairCode get() = t("Code", "الرمز", "קוד")
    val pair get() = t("Pair", "اقتران", "צמד")
    val startOver get() = t("Start over", "البدء من جديد", "התחל מחדש")
    val wrongCode get() = t("Wrong code. Check the TV and try again.", "رمز خاطئ. تحقق من التلفاز وحاول مجدداً.", "קוד שגוי. בדוק בטלוויזיה ונסה שוב.")
    val wrongCodeRestart get() = t("The TV rejected the code. Start over to get a new one.", "رفض التلفاز الرمز. ابدأ من جديد للحصول على رمز آخر.", "הטלוויזיה דחתה את הקוד. התחל מחדש לקבלת קוד חדש.")
    val tvNoAnswer get() = t("The TV isn't responding. Make sure it's on and on this Wi-Fi.", "التلفاز لا يستجيب. تأكد أنه يعمل وعلى هذه الشبكة.", "הטלוויזיה לא מגיבה. ודא שהיא דולקת ובאותה רשת.")
    val pairFailed get() = t("Pairing failed", "فشل الاقتران", "הצימוד נכשל")

    // ---------------------------------------------------------------- extra TV keys
    val settings get() = t("Settings", "الإعدادات", "הגדרות")
    val info get() = t("Info", "معلومات", "מידע")
    val subtitles get() = t("Subtitles", "الترجمة", "כתוביות")
    val guide get() = t("Guide", "الدليل", "מדריך")
    val playPause get() = t("Play/Pause", "تشغيل/إيقاف", "נגן/השהה")
    val next get() = t("Next", "التالي", "הבא")
    val previous get() = t("Previous", "السابق", "הקודם")
    val numbers get() = t("Numbers", "الأرقام", "מספרים")
    val apps get() = t("Apps", "التطبيقات", "אפליקציות")
    val red get() = t("Red", "أحمر", "אדום")
    val green get() = t("Green", "أخضر", "ירוק")
    val yellow get() = t("Yellow", "أصفر", "צהוב")
    val blue get() = t("Blue", "أزرق", "כחול")
    val tvOn get() = t("TV on", "التلفاز يعمل", "הטלוויזיה דולקת")
    val tvStandby get() = t("TV in standby", "التلفاز في وضع الاستعداد", "הטלוויזיה במצב המתנה")
    val muted get() = t("Muted", "مكتوم", "מושתק")
    fun volumeLevel(level: Int, max: Int?) = t("Volume", "الصوت", "עוצמה") + " " + (if (max != null && max > 0) "$level/$max" else "$level")

    // ---------------------------------------------------------------- AC
    fun modeName(m: WifiAcMode) = modeName(when (m) { WifiAcMode.AUTO -> 0; WifiAcMode.COOL -> 1; WifiAcMode.DRY -> 2; WifiAcMode.FAN -> 3; WifiAcMode.HEAT -> 4 })
    fun fanName(f: WifiFan) = fanName(when (f) { WifiFan.AUTO -> 0; WifiFan.LOW -> 1; WifiFan.MEDIUM -> 3; WifiFan.HIGH -> 5 })
    val room get() = t("Room", "الغرفة", "בחדר")
    val noSetPoint get() = "--"

    // ---------------------------------------------------------------- add / manage
    val add get() = t("Add", "إضافة", "הוספה")
    val addByIp get() = t("Add by IP address", "إضافة عبر عنوان IP", "הוספה לפי כתובת IP")
    val addByIpHint get() = t("When a device isn't found automatically", "عندما لا يتم العثور على الجهاز تلقائياً", "כשהמכשיר לא נמצא אוטומטית")
    val addByAccount get() = t("Add Wi-Fi AC by account", "إضافة مكيّف Wi-Fi عبر الحساب", "הוספת מזגן Wi-Fi לפי חשבון")
    val addByAccountHint get() = t("Electra Smart, Sensibo", "Electra Smart، Sensibo", "Electra Smart, Sensibo")
    val deviceType get() = t("Device type", "نوع الجهاز", "סוג המכשיר")
    val ipAddress get() = t("IP address", "عنوان IP", "כתובת IP")
    val ipExample get() = "192.168.1.20"
    val invalidIp get() = t("Enter a local address like 192.168.1.20", "أدخل عنواناً محلياً مثل 192.168.1.20", "הזן כתובת מקומית כמו 192.168.1.20")
    val checking get() = t("Checking…", "جارٍ التحقق…", "בודק…")
    val save get() = t("Save", "حفظ", "שמירה")
    val cancel get() = t("Cancel", "إلغاء", "ביטול")
    val open get() = t("Open", "فتح", "פתיחה")
    val notFoundAtIp get() = t("No answer from that address. Check the type and IP.", "لا رد من هذا العنوان. تحقق من النوع والعنوان.", "אין תשובה מהכתובת. בדוק את הסוג וה-IP.")
    val chooseBrand get() = t("Choose the brand", "اختر العلامة التجارية", "בחר את המותג")
    val greeNote get() = t("Tadiran and other Gree-based ACs are found automatically on your Wi-Fi.", "مكيّفات تديران وغيرها المبنية على Gree تظهر تلقائياً على شبكتك.", "מזגני תדיראן ומזגנים אחרים מבוססי Gree נמצאים אוטומטית ברשת.")
    val phoneNumber get() = t("Phone number", "رقم الهاتف", "מספר טלפון")
    val sendCode get() = t("Send code", "إرسال الرمز", "שלח קוד")
    val smsCode get() = t("Code from SMS", "الرمز من الرسالة", "קוד מה-SMS")
    val verify get() = t("Verify", "تحقق", "אמת")
    val electraPhoneHint get() = t("The number registered in the Electra Smart app.", "الرقم المسجل في تطبيق Electra Smart.", "המספר הרשום באפליקציית Electra Smart.")
    val electraCodeSent get() = t("We sent a code by SMS.", "أرسلنا رمزاً برسالة SMS.", "שלחנו קוד ב-SMS.")
    val electraPhoneRejected get() = t("This number isn't registered with Electra Smart.", "هذا الرقم غير مسجل في Electra Smart.", "המספר הזה לא רשום ב-Electra Smart.")
    val smsWrongCode get() = t("Wrong code. Check the SMS and try again.", "رمز خاطئ. تحقق من الرسالة وحاول مجدداً.", "קוד שגוי. בדוק את ה-SMS ונסה שוב.")
    val apiKey get() = t("API key", "مفتاح API", "מפתח API")
    val sensiboKeyHint get() = t("Create a key at home.sensibo.com/me/api", "أنشئ مفتاحاً في home.sensibo.com/me/api", "צור מפתח ב-home.sensibo.com/me/api")
    val keyRejected get() = t("The key was rejected.", "تم رفض المفتاح.", "המפתח נדחה.")
    val connect get() = t("Connect", "اتصال", "התחבר")
    val noAcsInAccount get() = t("No air conditioners in this account.", "لا توجد مكيّفات في هذا الحساب.", "אין מזגנים בחשבון הזה.")
    fun addedCount(n: Int) = t("Added $n air conditioner(s)", "تمت إضافة $n مكيّف", "נוספו $n מזגנים")
    val actions get() = t("Device options", "خيارات الجهاز", "אפשרויות מכשיר")
    fun accountLine(brand: String, account: String?) = if (account.isNullOrBlank()) brand else "$brand · $account"
}
