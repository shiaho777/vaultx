# VaultX R8/ProGuard 规则

# Tink 使用反射注册原语
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# BouncyCastle 只直接用 crypto 包(Argon2 派生链:Argon2BytesGenerator/Argon2Parameters/Blake2b);
# 其余数万类整包 keep 会白白放大 release 体积,交给 R8 按可达性裁剪
-keep class org.bouncycastle.crypto.** { *; }
-dontwarn org.bouncycastle.**

# kotlinx.serialization —— 生成的 serializer() 必须可达
-keepclasseswithmembers class io.vaultx.app.core.vault.** { *; }
# Navigation3 路由的序列化类:状态保存/恢复依赖 serializer
-keep class io.vaultx.app.ui.nav.** { *; }
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
