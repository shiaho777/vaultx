# VaultX R8/ProGuard 规则

# Tink 使用反射注册原语
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# BouncyCastle Argon2/provider 反射
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization —— 序列化模型走生成代码,保守起见保留
-keepclasseswithmembers class io.vaultx.app.core.vault.** { *; }
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}

# Biometric / Keystore 无特殊需求

# Compose/Coil/Media3 自带 consumer rules,无需额外 keep
-dontwarn kotlinx.coroutines.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
