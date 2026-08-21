# Fix Firebase Dependency Resolution Error

The project is currently failing to resolve transitive Android Firebase dependencies (e.g., `com.google.firebase:firebase-auth-ktx`). This occurs because these dependencies are missing a version specification, which is typically provided by the Firebase Bill of Materials (BoM) in Android projects.

## Proposed Changes

### [gradle/libs.versions.toml](file:///C:/Users/djran/AndroidStudioProjects/Crewsync/gradle/libs.versions.toml)

- Update `firebase-kotlin-sdk` from `2.1.0` to `2.5.0` to use the latest version of the GitLive SDK.
- Add `firebase-bom` dependency to the version catalog.

### [composeApp/build.gradle.kts](file:///C:/Users/djran/AndroidStudioProjects/Crewsync/composeApp/build.gradle.kts)

- Add the Firebase BoM to the `androidMain` dependencies using `platform()`. This will provide the necessary version information for all transitive `com.google.firebase` dependencies.

## Verification Plan

### Automated Tests
- Run Gradle sync to verify that the resolution error is resolved.
- Run `:composeApp:assembleDebug` to ensure the project builds successfully.

### Manual Verification
- Verify that Firebase features (Auth, Firestore, Storage) are still accessible in the code.
