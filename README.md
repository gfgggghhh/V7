# Veinfinder – Android

تحويل لبرنامج Veinfinder v1.0 (Windows / .NET + Emgu.CV) إلى تطبيق أندرويد (Kotlin + OpenCV).

## بناء الـ APK على GitHub (من غير ما تفتح Android Studio)
1. ارفع **محتويات** المجلد (مش ملف الـ zip) على مستودع GitHub، بحيث يكون `.github/workflows/main.yml` في جذر المستودع.
2. اعمل Push على فرع `main` أو `master` (أو شغّل Workflow يدويًا من تبويب **Actions ▸ Build Android APK ▸ Run workflow**).
3. بعد ما الـ Workflow يخلص (حوالي 5–10 دقائق): افتح التشغيل ونزّل **VeinFinder-debug-apk** من قسم *Artifacts*، وفك الضغط، ثبّت `VeinFinder-debug.apk` على الموبايل.
4. لو عملت tag بالشكل `v1.0` الـ APK هيتضاف تلقائيًا في صفحة *Releases*.

## التشغيل من Android Studio (اختياري)
1. افتح المجلد في Android Studio (Koala أو أحدث) واعمل Sync (محتاج JDK 17 ورام 8GB يفضّل).
2. وصّل الموبايل (USB debugging) واضغط Run.
3. كاميرا USB: وصّلها بكابل OTG، اختارها من القائمة (USB: ...)، واقبل صلاحية الـ USB.

## الإعدادات (نفس الـ .config الأصلي)
- k = 0.9 (من 0 إلى 1) و b = 10 (من 0 إلى 10): من زر ⚙ Settings.
- ثوابت الخوارزمية (sigma1=58.4، sigma2=29.6، sigma3=2.0) في VeinProcessor.kt.
- الصور بتتحفظ في Pictures/Veinfinder.
