# Wytyczne dla projektu MessageHandler

## Opis projektu
Chcemy przygotować bibliotekę Kotlin, którą można dołączać jako zależność do pluginów Minecraft.
Biblioteka ma wykonywać następujące zadania:
1. Zarządzać plikami językowymi (np. `lang/messages_pl.yml`).
2. Wykorzystywać mechanizm cache (np. Caffeine), aby przeładowanie pluginu pozwalało na ponowne załadowanie plików wiadomości do pamięci.
3. Zapewniać nieblokujące działanie względem głównego wątku serwera.
4. Obsługiwać formatowanie Minecraft Legacy (§), ampersand RGB oraz MiniMessage.
5. Udostępniać zestaw metod przyjmujących i zwracających różne typy (`String`, `Component`, tekst płaski) z obsługą prefixu i placeholderów poprzez MiniMessage `TagResolver`. Metody muszą identyfikować wpis w YAML po kluczu i wartości.
6. Umożliwiać minimalną integrację po stronie pluginu (instancja i proste wywołania, np. `messageHandler.getMessage("kategoria", "klucz", mapOf("player" to player))`).

## Instrukcje dla kontrybutorów
- Każda wprowadzana zmiana musi być odnotowana w pliku `docs/CHANGELOG.md` w formie krótkiego wpisu zawierającego datę i opis zmian. Jeśli plik nie istnieje, należy go utworzyć.
- Przed wdrożeniem nowych funkcji sprawdzaj, czy spełniają one wymagania opisane w sekcji "Opis projektu".
- Zachowuj spójny styl dokumentacji i kodu; do formatowania tekstu używaj Markdown.

## Lista rzeczy do zrobienia
- Implementacja warstwy cache dla wiadomości (z preferencją dla Caffeine).
- Przygotowanie adapterów formatujących dla Legacy, ampersand RGB oraz MiniMessage.
- Opracowanie API metod zwracających różne typy (Component, String, Plain, Prefix) z obsługą placeholderów.
- Dokumentacja sposobu użycia biblioteki w pluginie docelowym.
