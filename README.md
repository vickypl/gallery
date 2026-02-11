# Custom Gallery App

A Jetpack Compose Android app that implements a custom media gallery experience.

## Features

- Loads photos and videos from device storage using `MediaStore.Images` and `MediaStore.Video`.
- Displays media in a date-sorted grid (newest first).
- Opens a full-screen preview when a media item is tapped.
- Supports dynamic grid columns (2–6) with immediate UI updates.
- Supports multi-selection with a visual selected state.
- Handles Android 13+ and Android 12-and-below media permissions.

---

## Tech Stack

- **Kotlin**
- **Jetpack Compose (Material 3)**
- **Coil** for image loading
- **Android Gradle Plugin 8.5.2**
- **Kotlin plugin 1.9.24**
- **compileSdk / targetSdk 34**
- **minSdk 26**
- **Java 17**

---

## Prerequisites (Local Setup)

Install the following tools before building locally:

1. **Android Studio** (Hedgehog or newer recommended)
2. **Android SDK 34**
3. **Build-Tools for API 34**
4. **JDK 17**
5. **Android platform tools** (`adb`) for installing/running APK from terminal

> Recommended: Use Android Studio because this repository currently does not include a Gradle wrapper (`gradlew`). Android Studio will handle Gradle configuration for you.

---

## Step-by-Step: Run the Project Locally

### 1) Clone the repository

```bash
git clone <your-repo-url>
cd gallery
```

### 2) Open the project in Android Studio

- Launch Android Studio.
- Click **Open**.
- Select this project folder (`gallery`).
- Wait for Gradle sync to finish.

### 3) Configure SDK/JDK (if prompted)

In Android Studio:

- Go to **File > Settings > Android SDK** and ensure API 34 is installed.
- Go to **File > Settings > Build, Execution, Deployment > Build Tools > Gradle** and ensure JDK is set to **17**.

### 4) Connect a test device

Choose one:

- **Physical device**: enable Developer Options + USB debugging.
- **Emulator**: create/start an Android Virtual Device (AVD).

### 5) Run the app

- Select the `app` run configuration.
- Click **Run** (▶).

The app will launch and request storage permission:

- **Android 13+**: `READ_MEDIA_IMAGES` + `READ_MEDIA_VIDEO`
- **Android 12 and below**: `READ_EXTERNAL_STORAGE`

Grant permission to load gallery photos and videos.

---

## Team Workflow Requirement

From now onward, after code updates we keep the latest generated APK in `gallery/apk`.

Use:

```bash
./scripts/build_and_collect_apk.sh debug
```

(Or `release` instead of `debug`.) This script builds the app and copies the latest APK to:

```text
apk/gallery-debug-latest.apk
```

---

## Step-by-Step: Build APK for Testing

You can build a **debug APK** (fastest for QA/testing) or a **release APK** (for distribution).

---

### Option A: Build Debug APK (Recommended for testing)

#### A1) Build from Android Studio

1. Open project in Android Studio.
2. Use menu: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
3. Wait for success notification.
4. Click **locate** in the notification.

Expected output file:

```text
app/build/outputs/apk/debug/app-debug.apk
```

#### A2) Build from terminal (if you have Gradle available)

```bash
gradle :app:assembleDebug
```

If you later add a Gradle wrapper, use:

```bash
./gradlew :app:assembleDebug
```

---

### Option B: Build Release APK

> Use release builds only when you have signing configured.

#### B1) Generate a keystore (one-time)

```bash
keytool -genkeypair -v \
  -keystore release-key.jks \
  -alias gallery \
  -keyalg RSA -keysize 2048 -validity 10000
```

#### B2) Configure signing in `app/build.gradle.kts`

Add a `signingConfigs` block and connect it to `buildTypes.release`.

Example (replace with your own paths/password handling):

```kotlin
android {
    // ...existing config...

    signingConfigs {
        create("release") {
            storeFile = file("../release-key.jks")
            storePassword = "<store-password>"
            keyAlias = "gallery"
            keyPassword = "<key-password>"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}
```

#### B3) Build release APK

```bash
gradle :app:assembleRelease
```

(Or `./gradlew :app:assembleRelease` if wrapper exists.)

Expected output file:

```text
app/build/outputs/apk/release/app-release.apk
```

---

## Install APK on a Device (ADB)

After building:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If install succeeds, launch from app drawer.

---

## Quick Test Checklist (Manual QA)

After installation, verify:

1. App opens without crash.
2. Permission prompt appears and can be granted.
3. Photos are loaded from local media storage.
4. Grid sorts newest images first.
5. Tapping image opens full-view screen.
6. Grid column count can be changed (2 to 6).
7. Multi-selection toggles selected visual state correctly.
8. App behavior is reasonable after rotation/background-foreground.

---

## Project Structure

```text
app/
  src/main/
    java/com/example/customgallery/MainActivity.kt
    AndroidManifest.xml
    res/values/strings.xml
build.gradle.kts
settings.gradle.kts
gradle.properties
```

---

## Notes

- This project currently focuses on app logic/UX and testable APK generation.
- For CI/CD or reproducible command-line builds, adding the Gradle wrapper (`gradlew`) is recommended.
