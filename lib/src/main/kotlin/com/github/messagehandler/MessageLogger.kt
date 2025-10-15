package com.github.messagehandler

/**
 * Simple logger abstraction used by [MessageHandler] to report successes and
 * failures without binding to a particular logging framework.
 */
public interface MessageLogger {
    /** Logs a non-critical informational message. */
    public fun success(message: String)

    /** Logs an error level message. */
    public fun err(message: String)
}

/**
 * Default console based implementation of [MessageLogger]. It prints messages
 * to the standard output streams using ANSI aware Adventure serializers so
 * colour codes from MiniMessage remain intact.
 */
public object ConsoleMessageLogger : MessageLogger {
    override fun success(message: String) {
        println(message)
    }

    override fun err(message: String) {
        System.err.println(message)
    }
}
