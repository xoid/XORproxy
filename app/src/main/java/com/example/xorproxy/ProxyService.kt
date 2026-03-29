package com.example.xorproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors

class ProxyService : Service() {

    companion object {
        private const val TAG = "XorProxyService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "xor_proxy_channel"
        const val ACTION_STOP = "STOP_PROXY"
    }

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private var isRunning = false
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("proxy_config", MODE_PRIVATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) return START_STICKY

        // Сразу показываем уведомление (обязательно для foreground service)
        startForegroundNotification()

        isRunning = true

        val localPort = prefs.getString("local_port", "1080")!!.toIntOrNull() ?: 1080
        val remoteHost = prefs.getString("remote_host", "")!!
        val remotePort = prefs.getString("remote_port", "9999")!!.toIntOrNull() ?: 9999
        val passphrase = prefs.getString("passphrase", "")!!

        if (remoteHost.isEmpty() || passphrase.length < 8) {
            Log.e(TAG, "Неверные настройки")
            stopSelf()
            return START_NOT_STICKY
        }

        val key = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.toByteArray(Charsets.UTF_8))

        Thread {
            try {
                serverSocket = ServerSocket(localPort, 50, InetAddress.getByName("127.0.0.1"))
                Log.i(TAG, "✅ TCP Proxy запущен и слушает 127.0.0.1:$localPort")

                while (isRunning) {
                    val clientSocket = serverSocket!!.accept()
                    executor.execute {
                        handleClient(clientSocket, remoteHost, remotePort, key)
                    }
                }
            } catch (e: Exception) {
                if (isRunning) Log.e(TAG, "Ошибка ServerSocket", e)
            }
        }.start()

        return START_STICKY
    }

    private fun startForegroundNotification() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun handleClient(client: Socket, remoteHost: String, remotePort: Int, key: ByteArray) {
        var remote: Socket? = null
        try {
            remote = Socket(remoteHost, remotePort)
            Log.i(TAG, "Новое соединение → $remoteHost:$remotePort")

            // Telegram → Remote (шифруем)
            Thread {
                val buffer = ByteArray(16384)
                try {
                    val input = client.getInputStream()
                    val output = remote.getOutputStream()
                    while (isRunning) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        xorInPlace(buffer, bytesRead, key)
                        output.write(buffer, 0, bytesRead)
                        output.flush()
                    }
                } catch (_: Exception) {}
            }.start()

            // Remote → Telegram (дешифруем)
            Thread {
                val buffer = ByteArray(16384)
                try {
                    val input = remote.getInputStream()
                    val output = client.getOutputStream()
                    while (isRunning) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        xorInPlace(buffer, bytesRead, key)
                        output.write(buffer, 0, bytesRead)
                        output.flush()
                    }
                } catch (_: Exception) {}
            }.start()

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка соединения", e)
        } finally {
            try { client.close() } catch (_: Exception) {}
            try { remote?.close() } catch (_: Exception) {}
        }
    }

    private fun xorInPlace(buffer: ByteArray, length: Int, key: ByteArray) {
        for (i in 0 until length) {
            buffer[i] = (buffer[i].toInt() xor key[i % key.size].toInt()).toByte()
        }
    }

    private fun createNotification(): Notification {
        val channelId = CHANNEL_ID

        // Создаём канал для Android 8.0+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Шифрующий прокси",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Прокси шифрует трафик Telegram через XOR"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, ProxyService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val localPort = prefs.getString("local_port", "1080")
        val remoteHost = prefs.getString("remote_host", "—")

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Шифрующий прокси активен")
            .setContentText("127.0.0.1:$localPort → XOR → $remoteHost")
            .setSmallIcon(R.drawable.ic_proxy_active)
            .setLargeIcon(BitmapFactory.decodeResource(resources, R.drawable.ic_proxy_active))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Остановить",
                stopPendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        stopForeground(true)
        Log.i(TAG, "ProxyService остановлен")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
