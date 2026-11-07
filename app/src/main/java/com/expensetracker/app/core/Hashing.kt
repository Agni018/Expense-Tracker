package com.expensetracker.app.core

import java.security.MessageDigest

object Hashing {
    fun sha256(vararg parts: Any?): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { part ->
            digest.update((part?.toString() ?: "\u0000").toByteArray(Charsets.UTF_8))
            digest.update(0x1F)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
