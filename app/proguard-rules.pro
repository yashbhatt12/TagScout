# ============================================================
# TagScout ProGuard / R8 Rules
# ============================================================
# These rules tell R8 what NOT to obfuscate or strip.
# Everything else gets renamed, shrunk, and optimized.

# ============================================================
# DEBUGGING — keep line numbers for readable crash stack traces
# ============================================================
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ============================================================
# ROOM DATABASE
# ============================================================
# Room uses reflection to instantiate DAOs and entities at runtime.
# R8 must not rename or strip them.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep all entity and DAO classes in our data package
-keep class com.snainfotech.tagscout.data.entities.** { *; }
-keep class com.snainfotech.tagscout.data.dao.** { *; }
-keep class com.snainfotech.tagscout.data.AppDatabase { *; }

# ============================================================
# FIREBASE AUTH + FIRESTORE
# ============================================================
# Firebase uses reflection for serialization/deserialization.
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Firestore document classes (our UserProfile and any future models)
-keep class com.snainfotech.tagscout.data.auth.UserProfile { *; }

# Firebase Auth internal classes
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ============================================================
# BLUEBIRD RFID SDK (vendor JAR)
# ============================================================
# The Bluebird SDK is a pre-compiled JAR — we can't control its
# internals, so keep everything in it untouched.
-keep class co.kr.bluebird.** { *; }
-dontwarn co.kr.bluebird.**

# ============================================================
# FASTEXCEL (reader + writer) + XML DEPENDENCIES
# ============================================================
# Fastexcel-reader uses Aalto XML which uses reflection for
# StAX factory loading.
-keep class org.dhatim.fastexcel.** { *; }
-keep class org.dhatim.fastexcel.reader.** { *; }
-keep class com.fasterxml.aalto.** { *; }
-keep class org.codehaus.stax2.** { *; }
-keep class javax.xml.stream.** { *; }
-dontwarn javax.xml.stream.**
-dontwarn org.codehaus.stax2.**
-dontwarn com.fasterxml.aalto.**

# ============================================================
# KOTLIN COROUTINES
# ============================================================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ============================================================
# KOTLIN SERIALIZATION / REFLECTION
# ============================================================
-keep class kotlin.Metadata { *; }
-keepattributes RuntimeVisibleAnnotations
-dontwarn kotlin.**

# ============================================================
# JETPACK COMPOSE
# ============================================================
# Compose uses runtime reflection for remember/state.
# The default R8 rules from the Compose library handle most cases,
# but keep the @Composable annotation accessible.
-keep @androidx.compose.runtime.Composable class * { *; }
-dontwarn androidx.compose.**

# ============================================================
# NAVIGATION COMPOSE
# ============================================================
# Navigation uses string route names that must not be obfuscated
# in the nav graph. Our Routes object uses const val strings which
# are inlined at compile time, so they survive obfuscation. But
# keep the Routes object just in case.
-keep class com.snainfotech.tagscout.NavGraphKt { *; }
-keep class com.snainfotech.tagscout.Routes { *; }

# ============================================================
# LIFECYCLE / VIEWMODEL
# ============================================================
-keep class * extends androidx.lifecycle.ViewModel { *; }
-keep class * extends androidx.lifecycle.ViewModelProvider.Factory { *; }

# ============================================================
# ANDROID GENERAL
# ============================================================
# Keep the Application class (it's referenced by name in AndroidManifest)
-keep class com.snainfotech.tagscout.TagScoutApplication { *; }
-keep class com.snainfotech.tagscout.MainActivity { *; }

# Keep enum classes (used by various SDKs and state machines)
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep Parcelable implementations
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep Serializable classes
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# ============================================================
# SUPPRESS WARNINGS for known-safe missing classes
# ============================================================
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**