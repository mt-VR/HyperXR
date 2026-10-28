# Called from native code (JNI_OnLoad / FindClass) and from the game's JVM by name.
-keep class org.lwjgl.glfw.CallbackBridge { *; }
-keep class pojlib.** { *; }
-keep class com.oracle.dalvik.VMLauncher { *; }
-keep class com.hypermagic.minecraft.MinecraftActivity { *; }
