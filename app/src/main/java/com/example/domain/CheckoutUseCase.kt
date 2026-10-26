package com.example.domain

import com.example.data.InventoryRepository
import com.example.model.CartItem
import com.example.model.Order
import com.example.model.OrderStatus
import com.example.model.PromoCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

sealed interface CheckoutResult {
    data class Success(val order: Order) : CheckoutResult
    data class Error(val message: String, val canRetry: Boolean = true) : CheckoutResult
}

class CheckoutUseCase(
    private val inventoryRepository: InventoryRepository,
    private val paymentGateway: PaymentGateway
) {
    // Mutex guarantees atomic transactional boundary, eliminating Time-of-Check to Time-of-Use (TOCTOU) race conditions
    private val transactionMutex = Mutex()

    // Free delivery threshold: $35.00 (3500 cents)
    private val freeDeliveryThresholdCents = 3500L
    private val standardDeliveryFeeCents = 399L // $3.99

    // Active Promo Codes in system
    private val availablePromoCodes = mapOf(
        "FRESH20" to PromoCode("FRESH20", 20, 2000L, "20% off orders over $20.00"),
        "WELCOME5" to PromoCode("WELCOME5", 10, 1500L, "10% off for new shoppers over $15.00"),
        "URBAN15" to PromoCode("URBAN15", 15, 2500L, "15% off orders over $25.00")
    )

    fun validatePromoCode(code: String, subtotalCents: Long): Result<PromoCode> {
        val trimmed = code.trim().uppercase()
        val promo = availablePromoCodes[trimmed]
            ?: return Result.failure(IllegalArgumentException("Promo code '$code' is invalid."))

        if (subtotalCents < promo.minSpendInCents) {
            val neededCents = promo.minSpendInCents - subtotalCents
            val neededStr = String.format("$%.2f", neededCents / 100.0)
            return Result.failure(
                IllegalArgumentException("Add $neededStr more to apply ${promo.code}.")
            )
        }
        return Result.success(promo)
    }

    suspend fun execute(
        userId: String,
        cartItems: List<CartItem>,
        appliedPromoCode: String? = null,
        deliveryAddress: String = "742 Evergreen Terr, Apt 4B",
        deliverySlot: String = "Today, within 30 mins",
        customIdempotencyKey: String? = null
    ): CheckoutResult = transactionMutex.withLock {
        // 1. Basic validation
        if (cartItems.isEmpty()) {
            return CheckoutResult.Error("Your cart is empty. Add grocery items before checkout.")
        }

        // 2. Authoritative Price & Stock Verification (Security Defenses #1 & #4)
        // We re-query the true product from the inventory repository, rejecting client-tampered prices!
        val verifiedItems = mutableListOf<CartItem>()
        var calculatedSubtotalCents = 0L

        for (item in cartItems) {
            if (item.quantity <= 0) {
                return CheckoutResult.Error("Invalid quantity (${item.quantity}) for ${item.product.name}.")
            }

            val authoritativeProduct = inventoryRepository.getProduct(item.product.id)
                ?: return CheckoutResult.Error("Product ${item.product.name} is no longer available.")

            // Verify stock against authoritative repository
            if (authoritativeProduct.availableStock < item.quantity) {
                return CheckoutResult.Error(
                    "Insufficient stock for ${authoritativeProduct.name}. " +
                            "Only ${authoritativeProduct.availableStock} available."
                )
            }

            // Always use the server/repository's authoritative price
            val verifiedItem = CartItem(
                product = authoritativeProduct,
                quantity = item.quantity
            )
            verifiedItems.add(verifiedItem)
            calculatedSubtotalCents += verifiedItem.subtotalInCents
        }

        // 3. Discount calculation
        var discountCents = 0L
        if (!appliedPromoCode.isNullOrBlank()) {
            val promoResult = validatePromoCode(appliedPromoCode, calculatedSubtotalCents)
            if (promoResult.isSuccess) {
                val promo = promoResult.getOrThrow()
                discountCents = (calculatedSubtotalCents * promo.discountPercent) / 100
            }
        }

        // 4. Delivery fee calculation
        val deliveryFeeCents = if (calculatedSubtotalCents >= freeDeliveryThresholdCents) {
            0L
        } else {
            standardDeliveryFeeCents
        }

        val finalTotalCents = (calculatedSubtotalCents - discountCents + deliveryFeeCents).coerceAtLeast(0L)

        // 5. Secure Idempotency Key (UUIDv4 128-bit entropy)
        val idempotencyKey = customIdempotencyKey ?: UUID.randomUUID().toString()

        // 6. Process Payment with Idempotency Key
        val paymentResult = paymentGateway.processPayment(userId, finalTotalCents, idempotencyKey)
        if (paymentResult.isFailure) {
            val err = paymentResult.exceptionOrNull()?.message ?: "Payment transaction failed."
            return CheckoutResult.Error(err)
        }

        val paymentData = paymentResult.getOrThrow()

        // 7. Atomic Inventory Deduction (Rollback handling if deduction fails)
        val deductedItems = mutableListOf<CartItem>()
        for (item in verifiedItems) {
            val deducted = inventoryRepository.deductStock(item.product.id, item.quantity)
            if (!deducted) {
                // Compensating Transaction (Rollback previously deducted items)
                for (prev in deductedItems) {
                    inventoryRepository.replenishStock(prev.product.id, prev.quantity)
                }
                return CheckoutResult.Error("Checkout failed during stock allocation. Please retry.")
            }
            deductedItems.add(item)
        }

        // 8. Generate Confirmed Order with full UUID
        val order = Order(
            orderId = UUID.randomUUID().toString(),
            userId = userId,
            items = verifiedItems,
            subtotalInCents = calculatedSubtotalCents,
            discountInCents = discountCents,
            deliveryFeeInCents = deliveryFeeCents,
            totalAmountInCents = finalTotalCents,
            status = OrderStatus.CONFIRMED,
            idempotencyKey = idempotencyKey,
            paymentTxnId = paymentData.transactionId,
            timestamp = System.currentTimeMillis(),
            deliveryAddress = deliveryAddress,
            deliverySlot = deliverySlot
        )

        return CheckoutResult.Success(order)
    }
}
