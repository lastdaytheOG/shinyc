# Only in a slim build made for device tests (-PslimApk -PtestPhoneBuild). The test APK leaves
# out the libraries it shares with the app and borrows them from it, so they must not be shrunk
# away. What reading depends on (PDF, Office, Tesseract, ML Kit) is still shrunk as in the real APK.
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-keep class androidx.** { *; }
-keep class junit.** { *; }
-keep class org.junit.** { *; }
-keep class org.hamcrest.** { *; }
-keep class dagger.** { *; }
-keep class com.google.mlkit.vision.common.** { *; }
-keep class com.google.mlkit.vision.text.** { *; }
