# Room v10 schema reconstruction

Accepted by independent WSL review as a pinned-source reconstruction baseline.
Source commit:
`4c618f0baf6c94488264e72e1a35bdf005daebcf`. The [generated schema](com.agrios.app.core.database.AppDatabase/10.json)
is unedited, including its missing final LF.

SHA-256: `EB8C48552F9C665B6B191697E2A6D547BC4C179246CDD3DE1A3864FA0228ACC6`.

The isolated checkout extracted the commit's `app/src/main`,
`app/build.gradle.kts`, `gradle/`, `gradlew.bat`,
`settings.gradle.kts`, `build.gradle.kts`, and `gradle.properties` using
`git archive`. The sole export configuration change in that checkout
was adding the following argument to its app Gradle script:

```kotlin
ksp {
    arg("room.schemaLocation", layout.projectDirectory.dir("v10-room-export").asFile.absolutePath)
}
```

Ran the cached Gradle 8.11.1 distribution against the isolated checkout,
with `:app:kspDebugKotlin --offline`, both project and system properties
setting `org.gradle.java.installations.auto-download=false`, and
`tools/ownership-host-task-guard.gradle` as an init script. Tooling:
Room 2.6.1, AGP 8.7.3, Kotlin 2.0.0, KSP 2.0.0-1.0.24, installed Android
SDK 35, JVM target 17, and Android Studio JBR 21.0.9
(21.0.9+-14787801-b1163.94).

The guarded KSP task and its resource/configuration prerequisites
succeeded. The export declares v10 with 12 entity tables and matching
Room setup identity metadata. No APK, test, application database,
migration, or device execution occurred. This is a pinned-source
reconstruction, not deployed schema identity or reproducible-build
attestation. `PRODUCTION_RECOVERY_LIVE_NOT_APPROVED` remains closed.

The review independently matched the SHA-256 and checked 12 tables,
136 columns, and 20 indexes against the pinned source. It did not rerun
the export, construct a synthetic fixture, or execute a migration.
