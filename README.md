# MessageHandler
## Opis
Autorska biblioteka do kompleksowej obsługi wiadomości i plików językowych dla pluginów oparta na rozwiązaniach `net.kyori:adventure`.

## Warianty
- **MessageHandler-Paper** – wersja dla serwerów Paper/Folia i kompatybilnych forków.
- **MessageHandler-Spigot** – odpowiednik dla serwerów Bukkit/Spigot.

## Jak dodać?
Dodaj do build.gradle.kts odpowiednią wersję:  
### Paper/Spigot
* Release: ![Latest Release](https://img.shields.io/maven-metadata/v?metadataUrl=https://nexus.syntaxdevteam.pl/repository/maven-releases/pl/syntaxdevteam/messageHandler-paper/maven-metadata.xml)

* Snapshot: ![Latest Snapshot](https://img.shields.io/maven-metadata/v?metadataUrl=https://nexus.syntaxdevteam.pl/repository/maven-snapshots/pl/syntaxdevteam/messageHandler-paper/maven-metadata.xml)

```kotlin
repositories {
    mavenCentral()
    maven("https://nexus.syntaxdevteam.pl/repository/maven-snapshots/") //SyntaxDevTeam
    maven("https://nexus.syntaxdevteam.pl/repository/maven-releases/") //SyntaxDevTeam
}

dependencies {
    // Paper/Folia
    implementation("pl.syntaxdevteam:messageHandler-paper:1.0.2-SNAPSHOT")
    // lub Spigot/Bukkit
    // implementation("pl.syntaxdevteam:messageHandler-spigot:1.0.2-SNAPSHOT")
    implementation("com.github.ben-manes.caffeine:caffeine:3.2.3")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.25.0")
    compileOnly("net.kyori:adventure-text-minimessage:4.25.0")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.25.0")
    compileOnly("net.kyori:adventure-text-serializer-ansi:4.25.0")
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
   <img width="819" height="87" alt="image" src="https://github.com/user-attachments/assets/7af2c065-49ce-4333-8a6b-45aa2639fc42" />

    Przykład użycia:
   
    ```kotlin
    val wiadomosc = messageHandler.stringMessageToComponent("errors", "no-permission", map.of("player", playerName))
    ```
 * `stringMessageToString(category, key, placeholders)` – zwraca sformatowany tekst MiniMessage jako String z prefiksem, zachowując kolory i placeholdery stosowana tam gdzie wymagany jest czysty String zamiast komponentu.
   <img width="988" height="61" alt="image" src="https://github.com/user-attachments/assets/0fb676da-cff7-4c49-878c-d6e8cfa2c44a" />

 * `stringMessageToStringNoPrefix(category, key, placeholders)` – tak jak powyższa metoda ale ta zwraca treść wiadomości jako „czysty” String **bez prefiksu**, ale po przetworzeniu MiniMessage.
   <img width="713" height="28" alt="image" src="https://github.com/user-attachments/assets/8225a929-37d5-49af-862f-135d3ab03700" />

 * `stringMessageToComponentNoPrefix(category, key, placeholders)` – generuje komponent przeznaczony nie tylko do logów ale napisany z myślą o nich, konwertując zapis legacy/section na MiniMessage i pomijając prefiks.
   <img width="449" height="27" alt="image" src="https://github.com/user-attachments/assets/554ea0c8-044d-4f6b-861a-5efa0e7e7bfc" />

   
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
     <img width="930" height="29" alt="image" src="https://github.com/user-attachments/assets/e2f25a77-3a80-46b1-8701-448866d6b273" />

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

     <img width="620" height="160" alt="image" src="https://github.com/user-attachments/assets/6ab18817-9a00-487d-ae62-6422fb968738" />

### Metody do formatowania tekstu
 * `formatLegacyText(message)` – konwertuje tekst w formacie &-color na komponent MiniMessage. Czyli formatowanie wszystkich formatów Minecraft typu `&a`, `&l`, `&n` itp.
 * `formatHexAndLegacyText(message)` – obsługuje zarówno kody `&#rrggbb`, jak i `§` podczas konwersji do komponentu.
 * `miniMessageFormat(message)` – parsuje surowy ciąg MiniMessage do komponentu Adventure.
 * `getANSIText(component)` – serializuje komponent do kolorowego ANSI (np. na konsolę).
 * `getPlainText(component)` – sprowadza komponent do czystego tekstu pozbawionego  formatowania.
 * `formatMixedTextToMiniMessage(message, resolver)` – przyjmuje tekst mieszany (MiniMessage + legacy + § + sekwencje \uXXXX) i zwraca poprawnie zdeserializowany komponent, opcjonalnie z resolverem placeholderów. Najczęściej używane do przetwarzania tekstu wprowadzonych przez użytkowników, bo kompleksowo żąda wszystkie możliwe formaty.
 * `formatMixedTextToLegacy`(message, resolver)` – przyjmuje tekst mieszany (MiniMessage + legacy + § + sekwencje \uXXXX) i zwraca poprawnie zdeserializowany komponent, opcjonalnie z resolverem placeholderów. Najczęściej używane do przetwarzania tekstu wprowadzonych przez użytkowników, bo kompleksowo żąda wszystkie możliwe formaty.
---
