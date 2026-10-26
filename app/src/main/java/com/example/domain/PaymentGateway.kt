package com.example.domain

import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

data class PaymentResponse(
    val transactionId: String,
    val isSuccess: Boolean,
    val isIdempotentDuplicate: Boolean = false,
    val message: String
)

interface PaymentGateway {
    suspend fun processPayment(
        userId: String,
        amountInCents: Long,
        idempotencyKey: String
    ): Result<PaymentResponse>
}

class SimulatedPaymentGateway : PaymentGateway {
    // Concurrent map storing processed idempotency keys to transaction IDs
    private val processedIdempotencyRecords = ConcurrentHashMap<String, String>()

    // Controllable flags for TDD testing and error simulation
    var simulatePaymentFailure: Boolean = false
    var simulatedDelayMs: Long = 400L

    override suspend fun processPayment(
        userId: String,
        amountInCents: Long,
        idempotencyKey: String
    ): Result<PaymentResponse> {
        if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }

        // 1. Idempotency Check: Protect against duplicate charges / double billing
        val existingTxn = processedIdempotencyRecords[idempotencyKey]
        if (existingTxn != null) {
            return Result.success(
                PaymentResponse(
                    transactionId = existingTxn,
                    isSuccess = true,
                    isIdempotentDuplicate = true,
                    message = "Idempotent payment recognized. No duplicate charge incurred."
                )
            )
        }

        // 2. Simulated decline if testing error path
        if (simulatePaymentFailure) {
            return Result.failure(
                PaymentException("Payment declined by issuer: Insufficient funds or card restricted.")
            )
        }

        if (amountInCents <= 0) {
            return Result.failure(
                PaymentException("Invalid payment amount: Amount must be greater than zero.")
            )
        }

        // 3. Process new payment
        val newTxnId = "TXN_STRIPE_${System.currentTimeMillis()}_${(1000..9999).random()}"
        processedIdempotencyRecords[idempotencyKey] = newTxnId

        return Result.success(
            PaymentResponse(
                transactionId = newTxnId,
                isSuccess = true,
                isIdempotentDuplicate = false,
                message = "Payment authorized and captured successfully."
            )
        )
    }

    fun resetIdempotencyCache() {
        processedIdempotencyRecords.clear()
    }
}

class PaymentException(message: String) : Exception(message)
