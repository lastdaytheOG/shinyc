plugins {
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.0" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false

    // THIS MUST BE 2.54! If it says 2.51.1, it will crash.
    id("com.google.dagger.hilt.android")  version "2.57"  apply false
}