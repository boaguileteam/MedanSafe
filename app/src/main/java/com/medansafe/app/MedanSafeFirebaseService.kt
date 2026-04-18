package com.medansafe.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MedanSafeFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val title = remoteMessage.notification?.title ?: remoteMessage.data["title"] ?: "Notifikasi MedanSafe"
        val body = remoteMessage.notification?.body ?: remoteMessage.data["body"] ?: ""
        val type = remoteMessage.data["type"] ?: "system"
        val incidentId = remoteMessage.data["incidentId"]

        // Simpan ke Firestore jika user login
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            saveNotificationToFirestore(uid, title, body, type, incidentId)
        }

        showNotification(title, body, type, incidentId)
    }

    private fun saveNotificationToFirestore(uid: String, title: String, body: String, type: String, incidentId: String?) {
        val db = FirebaseFirestore.getInstance()
        val notification = NotificationModel(
            id = "", // Firestore will generate
            type = type,
            judul = title,
            isi = body,
            timestamp = System.currentTimeMillis(),
            isRead = false,
            incidentId = incidentId
        )

        db.collection("users").document(uid).collection("notifications")
            .add(notification)
    }

    private fun showNotification(title: String, body: String, type: String, incidentId: String?) {
        // Karena NotificationsActivity sudah dihapus, arahkan ke HomeActivity
        // HomeActivity akan menangani navigasi ke FeedFragment (Notifikasi)
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra("OPEN_NOTIFICATIONS", true)
            putExtra("TYPE", type)
            putExtra("INCIDENT_ID", incidentId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val channelId = "medansafe_notifications"
        val notificationBuilder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_logo_medansafe)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MedanSafe Notifications",
                IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }

        notificationManager.notify(System.currentTimeMillis().toInt(), notificationBuilder.build())
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d("FCM", "Token baru: $token")
    }
}
