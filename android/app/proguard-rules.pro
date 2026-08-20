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

-keep class me.kavishdevar.librepods.utils.KotlinModule { *; }

# Python calls these Java methods by their source names through Chaquopy. R8 cannot see those
# call sites, so release builds must not rename or remove the bridge surface.
-keepclassmembers class dev.wander.android.opentagviewer.anisette.LocalAnisette {
    public *;
}
-keepclassmembers class me.kavishdevar.librepods.features.findmy.FindMyNetworkAccessoryRequest {
    public java.lang.String getBeaconId();
    public java.lang.String getAccessoryJson();
}
