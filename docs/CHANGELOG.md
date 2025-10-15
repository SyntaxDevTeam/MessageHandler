# Dziennik zmian

## 2024-05-16
- Dodano plik `AGENTS.md` z opisem projektu, instrukcjami dla kontrybutorów oraz listą zadań do wykonania.

## 2025-10-15
- Przepisano bibliotekę na uproszczony handler wiadomości oparty o Caffeine, MiniMessage i SnakeYAML.
- Dodano interfejsy `ResourceProvider` oraz `PluginMetaProvider` wraz z loggerem konsolowym.
- Zaktualizowano dokumentację i testy jednostkowe do nowego API.
- Usunięto plugin foojay-resolver z konfiguracji Gradle, aby przywrócić możliwość budowania projektu.

## 2025-10-16
- Skonfigurowano kompilację przy użyciu lokalnego JDK 21 z docelową zgodnością Java 17, aby przywrócić budowanie projektu po usunięciu pluginu foojay.
