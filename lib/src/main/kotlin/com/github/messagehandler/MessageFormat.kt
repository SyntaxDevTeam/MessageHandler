package com.github.messagehandler

/**
 * Supported input formats for a message entry.
 */
public enum class MessageFormat {
    /** A MiniMessage formatted string. */
    MINI_MESSAGE,

    /** The Minecraft legacy section format that uses the `§` character. */
    LEGACY_SECTION,

    /** The Minecraft legacy ampersand format that uses the `&` character (with RGB support). */
    LEGACY_AMPERSAND,

    /** Plain, unformatted text. */
    PLAIN;

    public companion object {
        /**
         * Parses the given value into a [MessageFormat]. The comparison is case insensitive and accepts
         * common aliases such as `section`, `ampersand`, `legacy`, `mini`, and `plain`.
         */
        public fun from(value: String?, default: MessageFormat): MessageFormat {
            if (value == null) return default
            return when (value.trim().lowercase()) {
                "mini", "minimessage", "mm" -> MINI_MESSAGE
                "section", "legacy_section", "section-sign", "§" -> LEGACY_SECTION
                "ampersand", "legacy_ampersand", "legacy", "&" -> LEGACY_AMPERSAND
                "plain", "text", "raw" -> PLAIN
                else -> default
            }
        }
    }
}
