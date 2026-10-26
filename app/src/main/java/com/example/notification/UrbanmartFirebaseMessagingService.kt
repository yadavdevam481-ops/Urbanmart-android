package com.example.notification

import android.util.Log
import com.example.data.db.AppDatabase
import com.example.model.OrderStatus
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UrbanmartFirebaseMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("UrbanmartFCM", "New FCM Registration Token: $token")
        // Token can be sent to backend server for target push messaging
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("UrbanmartFCM", "Received FCM Message from: ${remoteMessage.from}")

        val data = remoteMessage.data
        val orderId = data["orderId"] ?: "ORD_FCM"
        val statusString = data["status"] ?: "OUT_FOR_DELIVERY"
        val customTitle = remoteMessage.notification?.title ?: data["title"]
        val customBody = remoteMessage.notification?.body ?: data["body"]

        val orderStatus = try {
            OrderStatus.valueOf(statusString)
        } catch (_: Exception) {
            OrderStatus.OUT_FOR_DELIVERY
        }

        // Show push notification to user
        OrderNotificationManager.showOrderStatusNotification(
            context = applicationContext,
            orderId = orderId,
            status = orderStatus,
            customMessage = customBody
        )

        // Update local database order status if known orderId
        if (data.containsKey("orderId")) {
            serviceScope.launch {
                try {
                    val db = AppDatabase.getDatabase(applicationContext)
                    db.orderDao().updateOrderStatus(orderId, orderStatus.name)
                } catch (e: Exception) {
                    Log.e("UrbanmartFCM", "Error updating order from FCM payload", e)
                }
            }
        }
    }
}
