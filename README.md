# Custom Gallery App

Android app implementing the requested gallery behavior:

- Loads only photos from device storage using `MediaStore.Images`.
- Shows a date-sorted (newest first) grid.
- Allows tapping a photo for full-image viewing.
- Allows dynamic grid size changes (2–6 columns) with immediate updates.
- Supports multi-selection with visual selected state.

## Build

Use Android Studio (Hedgehog+ recommended) and open this folder as a Gradle project.

## Permissions

- Android 13+: `READ_MEDIA_IMAGES`
- Android 12 and below: `READ_EXTERNAL_STORAGE`
