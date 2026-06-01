# BouncyCastle und sshj nutzen Reflection und Service-Provider-Lookup.
-keep class org.bouncycastle.** { *; }
-keep class net.schmizz.sshj.** { *; }
-keep class com.hierynomus.sshj.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.sshj.**
-dontwarn com.hierynomus.sshj.**

# slf4j-nop wird zur Laufzeit von sshj resolved.
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# kotlinx.serialization: ServerProfile lebt in core-data und wird als JSON
# in DataStore persistiert. Der Serializer-Companion darf nicht gestripped werden.
-keepclassmembers @kotlinx.serialization.Serializable class com.github.reygnn.core.data.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class com.github.reygnn.core.data.**$$serializer { *; }
