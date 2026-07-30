# Build & Setup Instructions

## Project Setup in Android Studio
1. Open Android Studio (Hedgehog or newer recommended).
2. Create a new project with **Empty Activity (Jetpack Compose)**.
3. Package Name: `com.rhythmplayer.app`
4. Language: **Kotlin**
5. Minimum SDK: `API 24 (Android 7.0)`

## Dependencies (`app/build.gradle.kts`)
Add Media3 ExoPlayer for seamless audio looping:
```kotlin
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.2.1")
    implementation("androidx.media3:media3-ui:1.2.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")
}
```

## Adding Audio Files
Place your `.mp3` files in `app/src/main/assets/rhythms/...` as described in `README.md`.
