# Zigpoll Android SDK

Present [Zigpoll](https://www.zigpoll.com) surveys inside your Android app.

> Ready-to-run sample: https://github.com/zigpoll/zigpoll-android-example

## Requirements

- Android 7.0+ (API 24)
- Kotlin or Java

## Installation

Via JitPack (until the SDK is published to Maven Central). In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven(url = "https://jitpack.io")
    }
}
```

And in your module's dependencies:

```kotlin
implementation("com.github.zigpoll:zigpoll-android:master-SNAPSHOT")
```

## Setup

1. In the Zigpoll dashboard, create a survey and set its delivery type to **API**.
2. Configure the SDK once, early in your app lifecycle:

```kotlin
import com.zigpoll.Zigpoll

Zigpoll.configure(context, "YOUR_ACCOUNT_ID")
```

Your account id is under **Installation** in the dashboard.

## Identify the user (optional, recommended)

```kotlin
Zigpoll.identify("user_123", mapOf(
    "email" to "jane@example.com",
    "name" to "Jane"
))
```

Identified users keep their survey state across sessions and devices — a
completed survey stays completed on re-trigger. Without `identify`, an
anonymous device-persistent id is used.

Call `Zigpoll.logout()` when the user signs out.

## Show a survey

```kotlin
Zigpoll.trigger("SURVEY_ID", activity)
```

The survey appears in a native bottom sheet. Responses are recorded slide by
slide, so partial responses are captured even if the user dismisses mid-survey.

## Metadata

```kotlin
Zigpoll.setMetadata(mapOf("plan" to "pro", "cohort" to "2026-Q3"))
```

The SDK automatically includes permission-free device context — model, device
type, OS version/API, screen, locale, timezone, dark mode, battery-saver
state, and your app id/version/build — as `source=mobile-sdk` metadata.
Location is derived server-side from the request IP; no location permission
is used.

## Callbacks

```kotlin
Zigpoll.onLoad     = { }
Zigpoll.onComplete = { responses -> }
Zigpoll.onClose    = { responses -> }
Zigpoll.onError    = { error -> }
```

## Development builds

```kotlin
Zigpoll.configure(context, "YOUR_ACCOUNT_ID", preview = true)
```

With `preview = true`, surveys open normally but responses are not billed or
counted in analytics.

## Notes

- Call all SDK methods on the main thread.
- Surveys with **URL-dependent** response settings are not supported on
  mobile; use the default (once per participant) setting.

## Docs

Full documentation: https://docs.zigpoll.com/installation/android-sdk
