# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
# ── Slim APK (-PslimApk) ────────────────────────────────────────────────────
# Only libraries are shrunk: the app's own classes are all kept (Room, Hilt, Gson, JNI and
# reflection reach them by name), and so is every library that loads classes by name.
-keep class com.amar.vault.** { *; }
-keepclasseswithmembernames class * { native <methods>; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*,SourceFile,LineNumberTable
# PDF reading, password-locked PDFs (JCE provider), Office files.
-keep class com.tom_roush.** { *; }
# Bouncy Castle's provider finds its ciphers by class name.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
# Office files: XMLBeans finds its schema types by name, POI its parts.
-keep class org.apache.xmlbeans.** { *; }
-keep class org.openxmlformats.schemas.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }
-keep class org.apache.poi.** { *; }
# Its zip reader makes the zip's extra fields by class name.
-keep class org.apache.commons.compress.** { *; }
# POI logs through Log4j's API, which makes its message factories by class name.
-keep class org.apache.logging.log4j.** { *; }
# Tesseract (scanned pages) is called from native code.
-keep class com.googlecode.tesseract.** { *; }
-keep class com.googlecode.leptonica.** { *; }
# Gson reads fields by name.
-keep class com.google.gson.** { *; }
-dontwarn **
