package com.example.data.repository

import com.example.data.db.OrderDao
import com.example.data.db.OrderEntity
import com.example.model.Order
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class OrderRepository(
    private val orderDao: OrderDao
) {
    suspend fun saveOrder(order: Order) {
        val entity = OrderEntity.fromDomain(order)
        orderDao.insertOrder(entity)
    }

    fun getOrdersForUser(userId: String): Flow<List<Order>> {
        return orderDao.getOrdersForUser(userId).map { list ->
            list.map { it.toDomain() }
        }
    }

    suspend fun getOrderById(orderId: String): Order? {
        return orderDao.getOrderById(orderId)?.toDomain()
    }

    fun getOrderFlow(orderId: String): Flow<Order?> {
        return orderDao.getOrderFlow(orderId).map { it?.toDomain() }
    }

    suspend fun updateOrderStatus(orderId: String, newStatus: com.example.model.OrderStatus) {
        orderDao.updateOrderStatus(orderId, newStatus.name)
    }

    suspend fun advanceOrderStatus(orderId: String): com.example.model.OrderStatus? {
        val current = orderDao.getOrderById(orderId)?.toDomain() ?: return null
        val nextStatus = when (current.status) {
            com.example.model.OrderStatus.CONFIRMED -> com.example.model.OrderStatus.PACKING
            com.example.model.OrderStatus.PACKING -> com.example.model.OrderStatus.OUT_FOR_DELIVERY
            com.example.model.OrderStatus.OUT_FOR_DELIVERY -> com.example.model.OrderStatus.DELIVERED
            else -> null
        }
        if (nextStatus != null) {
            orderDao.updateOrderStatus(orderId, nextStatus.name)
        }
        return nextStatus
    }

    suspend fun getActiveOrders(): List<Order> {
        return orderDao.getActiveOrders().map { it.toDomain() }
    }
}
