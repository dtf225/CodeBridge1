package com.codesync

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.codesync.service.WebSocketService
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import org.json.JSONObject

class QRScannerActivity : AppCompatActivity() {

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            handleScanResult(result.contents)
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("扫描桌面端二维码")
            setBeepEnabled(true)
            setOrientationLocked(true)
        }

        scanLauncher.launch(options)
    }

    private fun handleScanResult(content: String) {
        try {
            val json = JSONObject(content)
            val host = json.optString("host", "")
            val port = json.optInt("port", 19527)
            val pairingKey = json.optString("pk", "")

            if (host.isEmpty() || pairingKey.isEmpty()) {
                Toast.makeText(this, "无效的二维码", Toast.LENGTH_SHORT).show()
                return
            }

            val serviceIntent = Intent(this, WebSocketService::class.java).apply {
                action = WebSocketService.ACTION_CONNECT
                putExtra("ws_host", host)
                putExtra("ws_port", port)
                putExtra("pairing_key", pairingKey)
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            Toast.makeText(this, "正在连接 ${host}:${port}...", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Toast.makeText(this, "二维码解析失败: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
