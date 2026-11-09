# Capacitor and wolp-device-trust ship their own consumer rules (plugins, JNI class).
# Keep line numbers for crash reports without exposing source file names.
-keepattributes LineNumberTable
-renamesourcefileattribute SourceFile

# BridgeGuard (S1 §5) reads these Capacitor fields by name: keep them from being renamed or removed.
-keepclassmembers class com.getcapacitor.Bridge { private com.getcapacitor.MessageHandler msgHandler; }
-keepclassmembers class com.getcapacitor.MessageHandler { private androidx.webkit.JavaScriptReplyProxy javaScriptReplyProxy; }
