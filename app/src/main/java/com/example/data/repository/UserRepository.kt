package com.example.data.repository

import com.example.data.db.UserDao
import com.example.data.db.UserEntity
import com.example.data.security.PasswordHasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class UserSession(
    val userId: String,
    val name: String,
    val email: String
)

class UserRepository(
    private val userDao: UserDao
) {
    private val _currentUser = MutableStateFlow<UserSession?>(null)
    val currentUser: StateFlow<UserSession?> = _currentUser.asStateFlow()

    private val emailRegex = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+$")

    suspend fun signUp(name: String, email: String, password: String): Result<UserSession> {
        val trimmedName = name.trim()
        val trimmedEmail = email.trim().lowercase()

        if (trimmedName.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter your name."))
        }
        if (!emailRegex.matches(trimmedEmail)) {
            return Result.failure(IllegalArgumentException("Please enter a valid email address."))
        }
        if (password.length < 6) {
            return Result.failure(IllegalArgumentException("Password must be at least 6 characters."))
        }

        // Check for existing account
        val existing = userDao.getUserByEmail(trimmedEmail)
        if (existing != null) {
            return Result.failure(IllegalArgumentException("An account with this email already exists."))
        }

        // Generate salt & secure hash
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hashPassword(password, salt)

        val newUserId = "usr_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val userEntity = UserEntity(
            userId = newUserId,
            name = trimmedName,
            email = trimmedEmail,
            passwordHash = hash,
            salt = salt
        )

        try {
            userDao.insertUser(userEntity)
            val session = UserSession(userId = newUserId, name = trimmedName, email = trimmedEmail)
            _currentUser.value = session
            return Result.success(session)
        } catch (e: Exception) {
            return Result.failure(Exception("Registration failed: ${e.message}"))
        }
    }

    suspend fun logIn(email: String, password: String): Result<UserSession> {
        val trimmedEmail = email.trim().lowercase()
        if (trimmedEmail.isBlank() || password.isBlank()) {
            return Result.failure(IllegalArgumentException("Please enter both email and password."))
        }

        val user = userDao.getUserByEmail(trimmedEmail)
            ?: return Result.failure(IllegalArgumentException("Invalid email or password."))

        val isValid = PasswordHasher.verifyPassword(password, user.salt, user.passwordHash)
        if (!isValid) {
            return Result.failure(IllegalArgumentException("Invalid email or password."))
        }

        val session = UserSession(userId = user.userId, name = user.name, email = user.email)
        _currentUser.value = session
        return Result.success(session)
    }

    fun logOut() {
        _currentUser.value = null
    }

    fun setDirectSession(session: UserSession?) {
        _currentUser.value = session
    }
}
