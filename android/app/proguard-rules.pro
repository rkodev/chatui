# R8 keep rules for the ChatUI release build.
#
# Compose, Room, Hilt/Dagger, OkHttp/Okio, and kotlinx.serialization all ship
# consumer rules. The rules below cover the app-owned generated code and the
# reflective lookups that R8 cannot infer from bytecode. Keep this file narrow:
# add a rule only when a minified build proves it is required.

# Kotlin serialization: the runtime resolves a generated serializer through the
# model's Companion.serializer() accessor, which R8 cannot see. Keep the
# generated serializers and companion accessors for app-owned @Serializable
# models (core:model, core:network, feature:conversations export models).
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class dev.chatui.** {
    *** Companion;
}
-keepclasseswithmembers class dev.chatui.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class dev.chatui.**$$serializer {
    *;
}

# Room: the generated RoomDatabase subclass instantiates its generated
# implementation by name, so keep the app-owned generated database classes.
-keep class dev.chatui.**_Impl { *; }
-keep class dev.chatui.**_Impl$* { *; }

# Hilt/Dagger: generated factories, members injectors, and Hilt modules are
# looked up reflectively by the Hilt runtime. Keep the app-owned generated
# classes; the library's own consumer rules cover the framework side.
-keep class dev.chatui.**_Factory { *; }
-keep class dev.chatui.**_MembersInjector { *; }
-keep class dev.chatui.**_HiltModules* { *; }
