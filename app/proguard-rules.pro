# BrontoPlayer ProGuard / R8 rules.
# Media3, Room, DataStore and Coil ship their own consumer rules; these add app-specific keeps.

# Keep the pure-JVM M4B parser model classes (used via simple data flow, but keep names stable
# for crash readability).
-keep class com.aemake.brontoplayer.m4b.** { *; }

# Room generated implementations are referenced reflectively in some configurations.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**

# Kotlin coroutines / metadata
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlin.Metadata { *; }

# Keep enum valueOf/values used by settings persistence.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
