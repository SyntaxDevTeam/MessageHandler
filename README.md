# MessageHandler

A lightweight Kotlin library that simplifies working with YAML-based localisation files in Minecraft plugins. It focuses on
non-blocking reloads, caching, and Adventure `Component` formatting utilities so that retrieving messages inside a plugin is as
simple as `messageHandler.getMessage("category", "key", mapOf("player" to playerComponent))`.

## Features

- **Asynchronous loading with caching** – language files are loaded on a dedicated executor and stored in a Caffeine cache so a
  reload never blocks the main server thread.
- **Multiple formats out of the box** – MiniMessage, Minecraft legacy section (`§`) and ampersand (`&`) formats as well as plain
  text are handled transparently.
- **MiniMessage placeholder support** – map based placeholders are converted into `TagResolver`s and can be mixed with custom
  resolvers.
- **Rich conversion helpers** – retrieve translations as Adventure components, MiniMessage strings, legacy strings, or plain text.
- **Configurable prefixes and fallbacks** – pick any path for a prefix entry and optionally fall back to another locale.
- **Small surface area** – instantiate once and reuse; reloads return a `CompletableFuture<Unit>`.

## Getting started

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":lib"))
}
```

Until the library is published, include the `lib` module as a composite build or copy it into your multi-project Gradle build.

### Creating a handler

```kotlin
val handler = MessageHandler(
    MessageHandlerConfig(
        baseDirectory = plugin.dataFolder.toPath().resolve("lang"),
        defaultLocale = Locale("pl"),
        fallbackLocale = Locale.ENGLISH
    )
)
```

Call `handler.preload()` once during start-up (optionally await the returned future) and reuse the handler whenever you need to
send messages.

### YAML structure

Each entry can either be a raw string or an object that explicitly sets the input format:

```yaml
prefix:
  value: "<gray>[Serwer]</gray> "
general:
  welcome:
    format: MINI_MESSAGE
    value: "<green>Witaj <player>!</green>"
  notify:
    format: LEGACY_SECTION
    value: "§eWitaj §l<player>"
  info:
    format: LEGACY_AMPERSAND
    value: "&bInformacja dla <player>"
```

### Usage examples

```kotlin
val placeholders = mapOf("player" to player.displayName())

// Adventure component
player.sendMessage(handler.getMessage("general", "welcome", placeholders))

// Legacy string for other APIs
audience.sendMessage(handler.getLegacySection("general", "notify", placeholders))

// Plain text
logger.info(handler.getPlain("general", "info", placeholders))

// Prefix only
player.sendMessage(handler.prefix().append(Component.text("Hello!")))
```

### Reloading

To reload the Polish messages asynchronously:

```kotlin
handler.reload(Locale("pl")).thenRun {
    plugin.logger.info("Messages refreshed!")
}
```

## Running the tests

```bash
./gradlew --no-daemon test
```

This executes the JUnit 5 test-suite located in `lib/src/test/kotlin`.
