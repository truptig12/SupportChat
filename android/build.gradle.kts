// Build plugins. UI = Jetpack Compose (Material3); networking stays on plain
// HttpURLConnection + org.json (no Retrofit/OkHttp/Moshi).
plugins {
    id("com.android.application") version "8.13.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}
