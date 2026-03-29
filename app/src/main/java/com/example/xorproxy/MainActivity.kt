package com.example.xorproxy

import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.xorproxy.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var isProxyRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("proxy_config", MODE_PRIVATE)

        val etLocalPort = findViewById<EditText>(R.id.etLocalPort)
        val etRemoteHost = findViewById<EditText>(R.id.etRemoteHost)
        val etRemotePort = findViewById<EditText>(R.id.etRemotePort)
        val etPassphrase = findViewById<EditText>(R.id.etPassphrase)
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)
        val tvStatus = findViewById<TextView>(R.id.tvStatus)
        val tvTelegramLink = findViewById<TextView>(R.id.tvTelegramLink)

        // Загрузка сохранённых настроек
        etLocalPort.setText(prefs.getString("local_port", "1080"))
        etRemoteHost.setText(prefs.getString("remote_host", ""))
        etRemotePort.setText(prefs.getString("remote_port", "9999"))
        etPassphrase.setText(prefs.getString("passphrase", ""))

        // Запуск
        btnStart.setOnClickListener {
            val localPort = etLocalPort.text.toString().toIntOrNull() ?: 1080
            val remoteHost = etRemoteHost.text.toString().trim()
            val remotePort = etRemotePort.text.toString().toIntOrNull() ?: 9999
            val passphrase = etPassphrase.text.toString()

            if (remoteHost.isEmpty() || passphrase.length < 8) {
                Toast.makeText(this, "Укажи удалённый сервер и фразу минимум 8 символов", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }

            saveConfig(localPort, remoteHost, remotePort, passphrase)

            val intent = Intent(this, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            isProxyRunning = true
            updateUI(btnStart, btnStop, tvStatus, true)
        }

        // Остановка
        btnStop.setOnClickListener {
            stopService(Intent(this, ProxyService::class.java))
            isProxyRunning = false
            updateUI(btnStart, btnStop, tvStatus, false)
        }

        // Кликабельная ссылка для Telegram
        tvTelegramLink.setOnClickListener {
            val localPort = etLocalPort.text.toString().toIntOrNull() ?: 1080
            val link = "tg://socks?server=127.0.0.1&port=$localPort"
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
            } catch (e: Exception) {
                Toast.makeText(this, "Не удалось открыть Telegram. Установи приложение.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun saveConfig(localPort: Int, remoteHost: String, remotePort: Int, passphrase: String) {
        prefs.edit()
            .putString("local_port", localPort.toString())
            .putString("remote_host", remoteHost)
            .putString("remote_port", remotePort.toString())
            .putString("passphrase", passphrase)
            .apply()
    }

    private fun updateUI(btnStart: Button, btnStop: Button, tvStatus: TextView, running: Boolean) {
        if (running) {
            btnStart.isEnabled = false
            btnStop.isEnabled = true
            tvStatus.text = "Прокси запущен на 127.0.0.1:${prefs.getString("local_port", "1080")}"
            tvStatus.setTextColor(Color.GREEN)
        } else {
            btnStart.isEnabled = true
            btnStop.isEnabled = false
            tvStatus.text = "Прокси остановлен"
            tvStatus.setTextColor(Color.RED)
        }
    }
}
