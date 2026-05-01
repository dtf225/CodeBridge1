package com.codesync

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.codesync.databinding.ActivityMainBinding
import com.codesync.service.WebSocketService
import com.codesync.util.TotpUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var isServiceBound = false
    private var totpUpdateJob: kotlinx.coroutines.Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestPermissions()
        setupUI()
        startServiceIfNeeded()
        startTotpUpdates()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.RECEIVE_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.READ_SMS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val denied = results.entries.filter { !it.value }
            if (denied.isNotEmpty()) {
                Toast.makeText(this, R.string.permission_sms_required, Toast.LENGTH_LONG).show()
            }
        }

    private fun setupUI() {
        binding.btnScanQR.setOnClickListener {
            startActivity(Intent(this, QRScannerActivity::class.java))
        }

        binding.btnAddTotp.setOnClickListener {
            showAddTotpDialog()
        }

        binding.btnDisconnect.setOnClickListener {
            val intent = Intent(this, WebSocketService::class.java)
            intent.action = WebSocketService.ACTION_DISCONNECT
            startService(intent)
            updateConnectionUI(false)
        }

        binding.switchAutoSync.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startServiceIfNeeded()
            } else {
                stopServiceIfRunning()
            }
        }
    }

    private fun showAddTotpDialog() {
        val secretInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.totp_secret_hint)
        }
        val labelInput = com.google.android.material.textfield.TextInputEditText(this).apply {
            hint = getString(R.string.totp_label_hint)
        }

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
            addView(labelInput.apply { layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 16 }})
            addView(secretInput.apply { layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ) })
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.add_totp)
            .setView(layout)
            .setPositiveButton(R.string.save) { _, _ ->
                val secret = secretInput.text?.toString()?.trim()?.replace(" ", "") ?: ""
                val label = labelInput.text?.toString()?.trim() ?: "TOTP"
                if (secret.isNotEmpty()) {
                    saveTotpSecret(label, secret)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveTotpSecret(label: String, secret: String) {
        val prefs = getSharedPreferences("totp_secrets", MODE_PRIVATE)
        val existing = prefs.getStringSet("entries", emptySet())?.toMutableSet() ?: mutableSetOf()
        existing.add("$label|$secret")
        prefs.edit().putStringSet("entries", existing).apply()
        Toast.makeText(this, "TOTP [$label] 已保存", Toast.LENGTH_SHORT).show()
        startTotpUpdates()
        syncTotpToDesktop(label, secret)
    }

    private fun syncTotpToDesktop(label: String, secret: String) {
        val intent = Intent(this, WebSocketService::class.java)
        intent.action = WebSocketService.ACTION_SEND_TOTP
        intent.putExtra("totp_label", label)
        intent.putExtra("totp_secret", secret)
        startService(intent)
    }

    private fun startServiceIfNeeded() {
        val intent = Intent(this, WebSocketService::class.java)
        intent.action = WebSocketService.ACTION_CONNECT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        binding.switchAutoSync.isChecked = true
    }

    private fun stopServiceIfRunning() {
        val intent = Intent(this, WebSocketService::class.java)
        intent.action = WebSocketService.ACTION_DISCONNECT
        startService(intent)
    }

    private fun startTotpUpdates() {
        totpUpdateJob?.cancel()
        totpUpdateJob = lifecycleScope.launch {
            while (true) {
                updateTotpDisplay()
                delay(1000)
            }
        }
    }

    private fun updateTotpDisplay() {
        val prefs = getSharedPreferences("totp_secrets", MODE_PRIVATE)
        val entries = prefs.getStringSet("entries", emptySet()) ?: emptySet()

        if (entries.isEmpty()) {
            binding.tvTotpCodes.text = "暂无 TOTP 验证码\n点击下方按钮添加"
            binding.tvTotpCodes.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            return
        }

        val sb = StringBuilder()
        val remaining = TotpUtil.getRemainingSeconds()

        entries.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                val label = parts[0]
                val secret = parts[1]
                val code = TotpUtil.generate(secret)
                sb.appendLine("$label: $code")
            }
        }
        sb.appendLine()
        sb.appendLine("⏱️ ${remaining}s 后刷新")

        binding.tvTotpCodes.text = sb.toString().trimEnd()
        binding.tvTotpCodes.setTextColor(ContextCompat.getColor(this, android.R.color.white))

        entries.forEach { entry ->
            val parts = entry.split("|")
            if (parts.size == 2) {
                syncTotpToDesktop(parts[0], parts[1])
            }
        }
    }

    fun updateConnectionUI(connected: Boolean) {
        runOnUiThread {
            binding.tvConnectionStatus.text = if (connected)
                getString(R.string.connected) else getString(R.string.disconnected)
            binding.tvConnectionStatus.setTextColor(
                ContextCompat.getColor(this,
                    if (connected) android.R.color.holo_green_light
                    else android.R.color.holo_red_light
                )
            )
            binding.switchAutoSync.isChecked = connected
        }
    }

    override fun onDestroy() {
        totpUpdateJob?.cancel()
        super.onDestroy()
    }
}
