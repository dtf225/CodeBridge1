package com.codesync.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.codesync.MainActivity
import com.codesync.R
import com.codesync.util.CryptoUtil
import com.codesync.util.TotpUtil
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

class WebSocketService : Service() {

    companion object {
        const val TAG = "WebSocketService"
        const val ACTION_CONNECT = "com.codesync.CONNECT"
        const val ACTION_DISCONNECT = "com.codesync.DISCONNECT"
        const val ACTION_SEND_SMS = "com.codesync.SEND_SMS"
        const val ACTION_SEND_TOTP = "com.codesync.SEND_TOTP"
        const val EXTRA_CODE = "code"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_TOTP_LABEL = "totp_label"
        const val EXTRA_TOTP_SECRET = "totp_secret"
        const val NOTIFICATION_ID = 1001
        const val CHANNEL_ID = "code_sync_service"

        var isConnected = false
        var isRunning = false
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var wsUrl: String? = null
    private var pairingKey: String? = null
    private var sessionKey: String? = null
    private var reconnectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_CONNECT -> handleConnect(intent)
            ACTION_DISCONNECT -> handleDisconnect()
            ACTION_SEND_SMS -> handleSendSms(intent)
            ACTION_SEND_TOTP -> handleSendTotp(intent)
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleConnect(intent: Intent) {
        val host = intent.getStringExtra("ws_host")
        val port = intent.getIntExtra("ws_port", 19527)
        val pk = intent.getStringExtra("pairing_key")
        if (host != null && pk != null) {
            wsUrl = "ws://$host:$port"
            pairingKey = pk
            connectWebSocket()
        }
    }

    private fun handleDisconnect() {
        reconnectJob?.cancel()
        webSocket?.close(1000, "用户断开")
        webSocket = null
        isConnected = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleSendSms(intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "短信"
        sendVerifyCode(code, source, "sms")
    }

    private fun handleSendTotp(intent: Intent) {
        val label = intent.getStringExtra(EXTRA_TOTP_LABEL) ?: return
        val secret = intent.getStringExtra(EXTRA_TOTP_SECRET) ?: return
        val code = TotpUtil.generate(secret)
        sendVerifyCode(code, label, "totp", label)
    }

    private fun connectWebSocket() {
        val url = wsUrl ?: return

        okHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder().url(url).build()

        webSocket = okHttpClient?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket 已连接")
                isConnected = true
                updateNotification("已连接")
                broadcastConnectionState(true)

                val authMsg = """{"type":"auth","pairingKey":"${pairingKey ?: ""}"}"""
                webSocket.send(authMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = org.json.JSONObject(text)
                    when (msg.optString("type")) {
                        "auth_ok" -> {
                            sessionKey = msg.optString("sessionKey")
                            Log.d(TAG, "认证成功")
                            updateNotification("已连接并认证")
                        }
                        "auth_fail" -> {
                            Log.e(TAG, "认证失败")
                            webSocket.close(1000, "认证失败")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "消息解析错误", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket 连接失败", t)
                isConnected = false
                updateNotification("连接断开，尝试重连...")
                broadcastConnectionState(false)
                scheduleReconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket 已关闭")
                isConnected = false
                broadcastConnectionState(false)
            }
        })
    }

    private fun sendVerifyCode(code: String, source: String, type: String, label: String? = null) {
        if (!isConnected || webSocket == null) return

        try {
            val json = org.json.JSONObject().apply {
                put("code", code)
                put("source", source)
                put("type", type)
                put("timestamp", System.currentTimeMillis())
                if (label != null) put("label", label)
            }.toString()

            val encrypted = if (sessionKey != null) {
                CryptoUtil.encrypt(json, sessionKey!!)
            } else {
                json
            }

            val message = org.json.JSONObject().apply {
                put("type", "verify_code")
                put("payload", encrypted)
            }.toString()

            webSocket?.send(message)
            Log.d(TAG, "验证码已发送: $code")
        } catch (e: Exception) {
            Log.e(TAG, "发送验证码失败", e)
        }
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        reconnectJob = serviceScope.launch {
            delay(5000)
            if (!isConnected) {
                Log.d(TAG, "尝试重新连接...")
                connectWebSocket()
            }
        }
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(status: String = "服务运行中"): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, WebSocketService::class.java).apply {
            action = ACTION_DISCONNECT
        }
        val disconnectPendingIntent = PendingIntent.getService(
            this, 1, disconnectIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("验证码同步")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "断开", disconnectPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "验证码同步服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持同步服务在后台运行"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun broadcastConnectionState(connected: Boolean) {
        val intent = Intent("com.codesync.CONNECTION_STATE").apply {
            putExtra("connected", connected)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        reconnectJob?.cancel()
        serviceScope.cancel()
        webSocket?.close(1000, "服务停止")
        okHttpClient?.dispatcher?.executorService?.shutdown()
        isRunning = false
        isConnected = false
        super.onDestroy()
    }
}
