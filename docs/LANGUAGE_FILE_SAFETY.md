# Language file safety

MessageHandler treats existing user language files as **read-only configuration** during normal startup and reload.

## Loading policy

For every message lookup the effective value is resolved in this order:

1. the user's `lang/messages_<language>.yml`, when it exists and contains a compatible value,
2. the bundled `lang/messages_<language>.yml` resource shipped by the plugin,
3. the regular `Message not found!` fallback when neither source contains a usable value.

Missing keys and incompatible value types are therefore handled in memory. MessageHandler does **not** add those keys to the user's YAML and does not rewrite the file merely because a plugin update introduced new messages.

## Invalid YAML

Language files are parsed strictly.

When a user file contains invalid YAML during initial startup:

- the original file is left unchanged,
- a safety copy is written under `lang/backups/` with the suffix `.invalid.yml`,
- the bundled language resource is used in memory,
- the parser error is logged clearly.

When invalid YAML is encountered during `reloadMessages()` after a valid configuration was already loaded:

- the reload is aborted,
- the invalid file remains unchanged,
- the previously loaded valid configuration remains active,
- caches and the active prefix are not replaced by data from the failed reload.

## Missing files

A language resource is copied from the plugin JAR only when the target language file does not exist. Existing files are never overwritten by `saveResource`.

## Locale-specific files

The same safety rules apply to locale-specific files such as `messages_pl_pl.yml` and `messages_en_us.yml`. A malformed locale-specific file never replaces the global working configuration.

## Backups

Backups are append-only and timestamped, for example:

```text
lang/backups/messages_en_2026-10-07_06-15-42-123.invalid.yml
```

MessageHandler no longer removes a shared `lang_old_ver` directory.

## Legacy language versioning

The previous `# Ver. x.y.z` migration concept is intentionally **disabled at runtime**. The related helper methods remain in the source as a trace for a future redesign, but no startup, reload or synchronization path invokes them and a language file is never replaced because of its declared version.
