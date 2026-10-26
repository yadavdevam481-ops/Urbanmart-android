package com.example.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.model.CartItem
import com.example.model.Order
import com.example.model.OrderStatus
import com.example.model.Product
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "orders")
data class OrderEntity(
    @PrimaryKey
    val orderId: String,
    val userId: String,
    val subtotalInCents: Long,
    val discountInCents: Long,
    val deliveryFeeInCents: Long,
    val totalAmountInCents: Long,
    val status: String,
    val idempotencyKey: String,
    val paymentTxnId: String,
    val timestamp: Long,
    val deliveryAddress: String,
    val deliverySlot: String,
    val itemsSerialized: String
) {
    fun toDomain(): Order {
        val cartItems = deserializeCartItems(itemsSerialized)
        val orderStatus = try {
            OrderStatus.valueOf(status)
        } catch (_: Exception) {
            OrderStatus.CONFIRMED
        }
        return Order(
            orderId = orderId,
            userId = userId,
            items = cartItems,
            subtotalInCents = subtotalInCents,
            discountInCents = discountInCents,
            deliveryFeeInCents = deliveryFeeInCents,
            totalAmountInCents = totalAmountInCents,
            status = orderStatus,
            idempotencyKey = idempotencyKey,
            paymentTxnId = paymentTxnId,
            timestamp = timestamp,
            deliveryAddress = deliveryAddress,
            deliverySlot = deliverySlot
        )
    }

    companion object {
        fun fromDomain(order: Order): OrderEntity {
            return OrderEntity(
                orderId = order.orderId,
                userId = order.userId,
                subtotalInCents = order.subtotalInCents,
                discountInCents = order.discountInCents,
                deliveryFeeInCents = order.deliveryFeeInCents,
                totalAmountInCents = order.totalAmountInCents,
                status = order.status.name,
                idempotencyKey = order.idempotencyKey,
                paymentTxnId = order.paymentTxnId,
                timestamp = order.timestamp,
                deliveryAddress = order.deliveryAddress,
                deliverySlot = order.deliverySlot,
                itemsSerialized = serializeCartItems(order.items)
            )
        }

        fun serializeCartItems(items: List<CartItem>): String {
            return items.joinToString(";;") { item ->
                listOf(
                    item.product.id,
                    item.product.name.replace("::", " "),
                    item.product.category.replace("::", " "),
                    item.product.priceInCents.toString(),
                    item.quantity.toString(),
                    item.product.imageEmoji,
                    item.product.unit.replace("::", " ")
                ).joinToString("::")
            }
        }

        fun deserializeCartItems(serialized: String): List<CartItem> {
            if (serialized.isBlank()) return emptyList()
            return serialized.split(";;").mapNotNull { itemStr ->
                val parts = itemStr.split("::")
                if (parts.size >= 7) {
                    val id = parts[0]
                    val name = parts[1]
                    val category = parts[2]
                    val price = parts[3].toLongOrNull() ?: 0L
                    val qty = parts[4].toIntOrNull() ?: 1
                    val emoji = parts[5]
                    val unit = parts[6]
                    CartItem(
                        product = Product(
                            id = id,
                            name = name,
                            category = category,
                            priceInCents = price,
                            availableStock = 99,
                            unit = unit,
                            imageEmoji = emoji
                        ),
                        quantity = qty
                    )
                } else null
            }
        }
    }
}

@Dao
interface OrderDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity)

    @Query("SELECT * FROM orders WHERE userId = :userId ORDER BY timestamp DESC")
    fun getOrdersForUser(userId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE orderId = :orderId LIMIT 1")
    suspend fun getOrderById(orderId: String): OrderEntity?

    @Query("SELECT * FROM orders WHERE orderId = :orderId LIMIT 1")
    fun getOrderFlow(orderId: String): Flow<OrderEntity?>

    @Query("UPDATE orders SET status = :newStatus WHERE orderId = :orderId")
    suspend fun updateOrderStatus(orderId: String, newStatus: String)

    @Query("SELECT * FROM orders WHERE status NOT IN ('DELIVERED', 'CANCELLED')")
    suspend fun getActiveOrders(): List<OrderEntity>

    @Query("SELECT * FROM orders ORDER BY timestamp DESC")
    fun getAllOrders(): Flow<List<OrderEntity>>
}
