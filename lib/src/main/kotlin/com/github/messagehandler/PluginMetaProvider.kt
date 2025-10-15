package com.github.messagehandler

/** Provides minimal metadata about the owning plugin used for prefixes. */
public interface PluginMetaProvider {
    /** Name of the plugin or module using the message handler. */
    public val name: String
}
