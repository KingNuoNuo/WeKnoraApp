plugins {
    id("com.android.application") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    // Kotlin 2.0 起 Compose 编译器作为独立 Gradle 插件，不再需要手工对齐
    // compose-compiler 版本与 Kotlin 版本（这是老版本最容易踩的坑）。
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20" apply false
}
