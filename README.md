# MessageHandler

A lightweight Kotlin library that simplifies working with YAML-based localisation files in Minecraft plugins. It focuses on
predictable synchronous loading, Caffeine powered caching, and Adventure `Component` formatting utilities so that retrieving
messages inside a plugin is as simple as `messageHandler.getMessage("category", "key", mapOf("player" to "Alex"))`.

## Features

- **Synchronous reloads backed by caching** – language files are read on demand and cached with Caffeine for fast access.
- **Multiple formats out of the box** – MiniMessage, Minecraft legacy section (`§`) and ampersand (`&`) formats as well as plain
  text are handled transparently.
- **MiniMessage placeholder support** – simple map based placeholders are converted into `TagResolver`s and work alongside
  custom resolvers.
- **Rich conversion helpers** – retrieve translations as Adventure components, MiniMessage strings, legacy strings, or plain text.
- **Minimal dependencies** – provide a tiny `ResourceProvider` and `PluginMetaProvider` wrapper around your platform and you are
  ready to go.

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
    object : ResourceProvider {
        override val dataFolder = plugin.dataFolder

        override fun getConfigValue(path: String, default: String): String =
            plugin.config.getString(path, default)

        override fun saveResource(path: String, replace: Boolean) =
            plugin.saveResource(path, replace)

        override fun getResourceStream(path: String) =
            plugin.getResource(path)
    },
    object : PluginMetaProvider {
        override val name: String = plugin.name
    }
)
```

The handler automatically copies the language file from your JAR into the plugin directory and keeps responses cached for ten
minutes. Call `handler.reloadMessages()` to invalidate caches after editing the YAML file.

### YAML structure

Each entry can be a raw string or a list of strings. MiniMessage, legacy ampersand, and legacy section formats are recognised
automatically:

```yaml
prefix: "<gray>[Serwer]</gray>"
general:
  welcome: "<green>Witaj <player>!</green>"
log:
  notice: "&aInformacja §b<player>"
smart:
  multi:
    - "&ePierwsza linia"
    - "§fDruga linia"
```

### Usage examples

```kotlin
val placeholders = mapOf("player" to player.name)

// Adventure component
player.sendMessage(handler.getMessage("general", "welcome", placeholders))

// Legacy string for other APIs
audience.sendMessage(handler.getLogMessage("log", "notice", placeholders))

// Plain text without prefix
plugin.logger.info(handler.getCleanMessage("general", "welcome", placeholders))
```

## Running the tests

```bash
./gradlew --no-daemon test
```

This executes the JUnit 5 test-suite located in `lib/src/test/kotlin`.
