package com.example.model

import java.util.UUID

data class Product(
    val id: String,
    val name: String,
    val category: String,
    val priceInCents: Long, // Stored in cents to guarantee zero floating-point precision error
    val availableStock: Int,
    val unit: String = "1 item",
    val description: String = "",
    val badge: String? = null,
    val rating: Double = 4.8,
    val imageEmoji: String = "🛒"
) {
    val formattedPrice: String
        get() = formatCents(priceInCents)
}

data class CartItem(
    val product: Product,
    val quantity: Int
) {
    val subtotalInCents: Long
        get() = product.priceInCents * quantity

    val formattedSubtotal: String
        get() = formatCents(subtotalInCents)
}

enum class OrderStatus(val displayName: String) {
    DRAFT("Draft"),
    PAYMENT_PENDING("Processing Payment"),
    CONFIRMED("Confirmed"),
    PACKING("Packing Groceries"),
    OUT_FOR_DELIVERY("Out for Delivery"),
    DELIVERED("Delivered"),
    CANCELLED("Cancelled")
}

data class Order(
    val orderId: String = UUID.randomUUID().toString(),
    val userId: String,
    val items: List<CartItem>,
    val subtotalInCents: Long = 0L,
    val discountInCents: Long = 0L,
    val deliveryFeeInCents: Long = 0L,
    val totalAmountInCents: Long = 0L,
    val status: OrderStatus = OrderStatus.CONFIRMED,
    val idempotencyKey: String = UUID.randomUUID().toString(),
    val paymentTxnId: String = "TXN_${UUID.randomUUID().toString().take(12)}",
    val timestamp: Long = System.currentTimeMillis(),
    val deliveryAddress: String = "742 Evergreen Terr, Apt 4B",
    val deliverySlot: String = "Today, within 30 mins"
) {
    val formattedTotal: String
        get() = formatCents(totalAmountInCents)

    val formattedSubtotal: String
        get() = formatCents(subtotalInCents)

    val formattedDiscount: String
        get() = formatCents(discountInCents)

    val formattedDeliveryFee: String
        get() = if (deliveryFeeInCents == 0L) "FREE" else formatCents(deliveryFeeInCents)

    val itemCount: Int
        get() = items.sumOf { it.quantity }
}

data class PromoCode(
    val code: String,
    val discountPercent: Int,
    val minSpendInCents: Long,
    val description: String
)

fun formatCents(cents: Long): String {
    val dollars = cents / 100
    val remainder = cents % 100
    return String.format("$%d.%02d", dollars, remainder)
}
