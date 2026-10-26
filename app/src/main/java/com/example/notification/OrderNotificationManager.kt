package com.example.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.MainActivity
import com.example.R
import com.example.model.OrderStatus

object OrderNotificationManager {

    const val CHANNEL_ID = "urbanmart_order_channel"
    const val CHANNEL_NAME = "Urbanmart Order Updates"
    const val CHANNEL_DESC = "Real-time alerts when your grocery order changes fulfillment status"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, importance).apply {
                description = CHANNEL_DESC
                enableVibration(true)
                setShowBadge(true)
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showOrderStatusNotification(
        context: Context,
        orderId: String,
        status: OrderStatus,
        customMessage: String? = null
    ) {
        createNotificationChannel(context)

        // Check POST_NOTIFICATIONS permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
        }

        val title = when (status) {
            OrderStatus.CONFIRMED -> "Order #${orderId.take(8).uppercase()} Confirmed! ✅"
            OrderStatus.PACKING -> "Order #${orderId.take(8).uppercase()} is being Packed 📦"
            OrderStatus.OUT_FOR_DELIVERY -> "Order #${orderId.take(8).uppercase()} Out for Delivery! 🛵"
            OrderStatus.DELIVERED -> "Order #${orderId.take(8).uppercase()} Delivered! 🎉"
            OrderStatus.CANCELLED -> "Order #${orderId.take(8).uppercase()} Cancelled"
            else -> "Order #${orderId.take(8).uppercase()} Update"
        }

        val body = customMessage ?: when (status) {
            OrderStatus.CONFIRMED -> "We received your grocery order and sent it to the store."
            OrderStatus.PACKING -> "Store associate is hand-picking and packing your fresh groceries."
            OrderStatus.OUT_FOR_DELIVERY -> "Your courier is on the road! Estimated arrival in 10-15 minutes."
            OrderStatus.DELIVERED -> "Your groceries were safely delivered to your doorstep. Enjoy!"
            OrderStatus.CANCELLED -> "Your order has been cancelled and refunded."
            else -> "Fulfillment status is now ${status.displayName}."
        }

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("OPEN_ORDER_ID", orderId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            orderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        val notificationId = (orderId.hashCode() and 0x7FFFFFFF)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
}
