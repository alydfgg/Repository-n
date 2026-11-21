# طريقة البناء الناجحة (نفس مشروع package.zip)

- Gradle wrapper: 6.1.1  (gradle/wrapper/gradle-wrapper.properties)
- Android Gradle Plugin: 3.5.3
- Kotlin plugin: 1.6.21
- compileSdk / targetSdk: 29 ، minSdk: 23
- المستودعات: مرايا aliyun (gradle-plugin / public / google) في build.gradle
- android.useAndroidX=false (gradle.properties)
- أي API من أندرويد 11+ يُستدعى بالـ reflection أو بنص ثابت لأن compileSdk = 29
- الاسم الظاهر: app/src/main/res/values/strings.xml (app_name)
- الاسم الداخلي: applicationId في app/build.gradle + package في AndroidManifest.xml + مجلد com/exfiles/app

## ميزات التطبيق
- تبويبات متعددة (＋ لتبويب جديد، ضغط مطوّل على التبويب لإغلاقه)، وفتح المحدد من "المزيد" للتنقل بين الصور/الفيديو/النصوص
- عرض شبكة/قائمة من القائمة ⋮
- أيقونات ومعلومات ملفات APK (الاسم، الإصدار، مثبّت/غير مثبّت) وبلاطة "نسخ التطبيقات" (EXBackup)
- الأيقونة: res/drawable/ic_exfiles_fg.xml + mipmap-*/ic_exfiles* (أسماء فريدة كي لا تتعارض مع أيقونة المشروع القديم)
- تثبيت APK: عبر ApkProvider (content://) + صلاحية "تثبيت التطبيقات من هذا المصدر"؛ حزم XAPK/APKS/APKM عبر PackageInstaller (مع نسخ OBB)
