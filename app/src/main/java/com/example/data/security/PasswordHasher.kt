package com.example.data.security

import java.security.MessageDigest
import java.security.SecureRandom

object PasswordHasher {

    private const val SALT_LENGTH = 16

    /**
     * Generates a cryptographically secure random salt encoded in hex.
     */
    fun generateSalt(): String {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)
        return salt.joinToString("") { "%02x".format(it) }
    }

    /**
     * Hashes password with the provided salt using SHA-256 over multiple rounds.
     */
    fun hashPassword(password: String, saltHex: String, iterations: Int = 1000): String {
        val md = MessageDigest.getInstance("SHA-256")
        var currentBytes = (saltHex + password).toByteArray(Charsets.UTF_8)
        repeat(iterations) {
            currentBytes = md.digest(currentBytes)
        }
        return currentBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifies password against stored hash using constant-time comparison to prevent timing attacks.
     */
    fun verifyPassword(password: String, saltHex: String, storedHash: String): Boolean {
        val computedHash = hashPassword(password, saltHex)
        return MessageDigest.isEqual(
            computedHash.toByteArray(Charsets.UTF_8),
            storedHash.toByteArray(Charsets.UTF_8)
        )
    }
}
