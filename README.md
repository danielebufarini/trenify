# Trenify

Trenify is a Kotlin Multiplatform application for Italian train information and monitoring.
Android and iOS share non-rendering application/domain/data logic, Decompose application/lifecycle/navigation-model state, SQLDelight persistence, providers and repositories.

## Project layout

- `androidApp`: Android host and Jetpack Compose presentation, with Material 3 and the custom Trenify design system.
- `iosApp`: native SwiftUI presentation, Liquid Glass where supported, thin SKIE-backed UI adapters and Apple platform integration.
- `shared/app`: shared composition root and Decompose application/lifecycle/navigation-model state.
- `shared/core`: framework boundaries and shared infrastructure.
- `shared/data`: repository implementation boundary.
- `shared/provider`: concrete external-provider adapter boundary.
- `shared/feature`: non-rendering feature components, immutable state and actions.

```shell
./gradlew projects
./gradlew verifyAll
```

Build the hosts directly with:

```shell
./gradlew :androidApp:assembleDebug
./gradlew :shared:app:linkDebugFrameworkIosSimulatorArm64
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator -configuration Debug CODE_SIGNING_ALLOWED=NO build
```

## Monitoring behavior

Train monitors are persisted locally and refreshed while the app is in the foreground. Android `JobScheduler` and iOS `BGTaskScheduler` also request best-effort background refreshes, and local notifications are emitted only when the operating system grants both notification permission and execution time.

