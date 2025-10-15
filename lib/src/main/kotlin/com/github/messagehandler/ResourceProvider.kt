package com.github.messagehandler

import java.io.File
import java.io.InputStream

/**
 * Abstraction over plugin resources used by [MessageHandler]. It mirrors the
 * operations exposed by the Bukkit API so that the handler can work in tests
 * and in production without depending on the platform classes directly.
 */
public interface ResourceProvider {
    /** Directory that stores language files. */
    public val dataFolder: File

    /** Reads a configuration value from the primary configuration file. */
    public fun getConfigValue(path: String, default: String): String

    /** Copies a bundled resource to the [dataFolder]. */
    public fun saveResource(path: String, replace: Boolean)

    /** Provides a stream to a bundled resource located on the classpath. */
    public fun getResourceStream(path: String): InputStream?
}
