# Walkthrough - Fixing AGP 9.0 Compatibility

I have fixed the compatibility issue between Android Gradle Plugin 9.0 and the Kotlin Multiplatform plugin.

## Changes

### Build Configuration

#### [gradle.properties](file:///C:/Users/djran/AndroidStudioProjects/Crewsync/gradle.properties)

I added the following properties to disable the new "built-in Kotlin" support and the new DSL, which currently conflict with KMP modules in AGP 9.0:

```properties
android.builtInKotlin=false
android.newDsl=false
```

## Verification Results

### Automated Tests
- **Gradle Sync**: Executed a full project sync which finished successfully.

> [!NOTE]
> This is a known compatibility issue in AGP 9.0. Disabling these features allows the project to build while still using the standard `org.jetbrains.kotlin.multiplatform` plugin for your shared code and Android application modules.
