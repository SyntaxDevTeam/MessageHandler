package pl.syntaxdevteam.message

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.chat.ChatType
import net.kyori.adventure.chat.SignedMessage

/**
 * Utilities for working with signed chat messages on Paper/Folia servers.
 */
object SignedMessageSupport {
    /**
     * Sends a signed message using the provided bound chat type.
     */
    @JvmStatic
    fun sendSignedMessage(audience: Audience, signedMessage: SignedMessage, chatType: ChatType.Bound) {
        audience.sendMessage(signedMessage, chatType)
    }

    /**
     * Requests deletion of a signed message for the given audience.
     */
    @JvmStatic
    fun deleteMessage(audience: Audience, signedMessage: SignedMessage) {
        audience.deleteMessage(signedMessage)
    }

    /**
     * Requests deletion of a signed message by signature for the given audience.
     */
    @JvmStatic
    fun deleteMessage(audience: Audience, signature: SignedMessage.Signature) {
        audience.deleteMessage(signature)
    }
}
