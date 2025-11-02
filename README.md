# MessageHandler
## Opis
Autorska biblioteka do kompleksowej obsługi wiadomości i plików językowych dla pluginów.
## Jak dodać?
Dodaj do build.gradle.kts:
```kotlin
repositories {
    mavenCentral()
    maven("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/") //SyntaxDevTeam
    maven("https://nexus.syntaxdevteam.pl/repository/maven-releases/") //SyntaxDevTeam
}

dependencies {
    implementation("pl.syntaxdevteam:messageHandler:1.0.3")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
}
```
W klasię głownej dodaj:
```kotlin
import pl.syntaxdevteam.message.SyntaxMessages

class TwojPLuginX : JavaPlugin() {
    lateinit var messageHandler: MessageHandler

  override fun onEnable() {
        SyntaxMessages.initialize(this)
        messageHandler = SyntaxMessages.messages
  }
}
```
### Opisy podstawowych metod do obsługi wiadomości

 * `reloadMessages()` – ponownie wczytuje konfigurację językową i czyści wszystkie cache wiadomości, by kolejne odczyty korzystały z aktualnych danych. **Dodaje się najczęściej do komendy reload.**
 * `getPrefix()` – zwraca aktualny prefiks dodawany do wiadomości użytkownika.
 * `stringMessageToComponent(category, key, placeholders)` – podstawowa metoda która buduje komponent MiniMessage z prefiksem na podstawie wpisu YAML i podstawionych placeholderów.
    Przykład użycia:
   
    ```kotlin
    val wiadomosc = messageHandler.stringMessageToComponent("errors", "no-permission", map.of("player", playerName))
    ```
 * `stringMessageToString(category, key, placeholders)` – zwraca sformatowany tekst MiniMessage jako String z prefiksem, zachowując kolory i placeholdery stosowana tam gdzie wymagany jest czysty String zamiast komponentu.
 * `stringMessageToStringNoPrefix(category, key, placeholders)` – tak jak powyższa metoda ale ta zwraca treść wiadomości jako „czysty” String **bez prefiksu**, ale po przetworzeniu MiniMessage.
 * `stringMessageToComponentNoPrefix(category, key, placeholders)` – generuje komponent przeznaczony nie tylko do logów ale napisany z myślą o nich, konwertując zapis legacy/section na MiniMessage i pomijając prefiks.
   
    Przykłąd użycia:
    ```kotlin
    val logWiadomosc = messageHandler.stringMessageToComponentNoPrefix("logs", "user-joined", map.of("user", userName))
    logger.info(logWiadomosc)
    ```
 * `getSmartMessage(category, key, placeholders)` – inteligentnie obsługuje zarówno pojedynczy wpis tekstowy, jak i listę, zwracając listę komponentów gotowych do wyświetlenia.
    Przykład zastosowania w pliku YAML:
   
      Wersja 1 – pojedynczy wpis tekstowy:
      ```YAML
        broadcast: "<dark_gray>Gracz <gray><player></gray> został wyrzucony z powodu <gray><reason></gray></dark_gray>"
     ```
      Wersja 2 – lista wpisów tekstowych:
     ```YAML
      broadcast:
        - "<dark_gray>*************** Twoja Nazwa Serwera *************** </dark_gray>"
        - ""
        - "<red>   Gracz <white><player></white> został wyrzucony</red>"
        - "   Powód: <white><reason></white>"
        - ""
        - "<dark_gray>*************************************************** </dark_gray>"
        ```
### Metody do formatowania tekstu
 * `formatLegacyText(message)` – konwertuje tekst w formacie &-color na komponent MiniMessage. Czyli formatowanie wszystkich formatów Minecraft typu `&a`, `&l`, `&n` itp.
 * `formatHexAndLegacyText(message)` – obsługuje zarówno kody `&#rrggbb`, jak i `§` podczas konwersji do komponentu.
 * `miniMessageFormat(message)` – parsuje surowy ciąg MiniMessage do komponentu Adventure.
 * `getANSIText(component)` – serializuje komponent do kolorowego ANSI (np. na konsolę).
 * `getPlainText(component)` – sprowadza komponent do czystego tekstu pozbawionego  formatowania.
 * `formatMixedTextToMiniMessage(message, resolver)` – przyjmuje tekst mieszany (MiniMessage + legacy + § + sekwencje \uXXXX) i zwraca poprawnie zdeserializowany komponent, opcjonalnie z resolverem placeholderów. Najczęściej używane do przetwarzania tekstu wprowadzonych przez użytkowników, bo kompleksowo żąda wszystkie możliwe formaty.
---
