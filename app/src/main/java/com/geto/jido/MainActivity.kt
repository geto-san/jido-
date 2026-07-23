package com.geto.jido

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.provider.Settings
import android.text.TextUtils
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.geto.jido.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val adapter = DownloadsAdapter()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startClipboardService()
            } else {
                // Without this permission the foreground notification can't be shown on
                // Android 13+, so the service would be killed almost immediately.
                Toast.makeText(
                    this,
                    "Notification permission is required for the background listener to keep running. " +
                        "Enable it from Settings to use Jido.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupDownloadsList()
        setupManualInput()
        ensureNotificationPermissionThenStart()
        checkAccessibilityService()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val manualLink = intent?.getStringExtra(ClipboardService.EXTRA_MANUAL_LINK)
        if (!manualLink.isNullOrBlank()) {
            startClipboardServiceWithLink(manualLink)
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityPrompt()
    }

    private fun updateAccessibilityPrompt() {
        val isEnabled = isAccessibilityServiceEnabled()
        binding.accessibilityCard.visibility = if (isEnabled) View.GONE else View.VISIBLE
    }

    private fun setupManualInput() {
        binding.enableAccessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.downloadButton.setOnClickListener {
            val link = binding.linkInput.text.toString().trim()
            if (link.isNotBlank()) {
                startClipboardServiceWithLink(link)
                binding.linkInput.text?.clear()
                hideKeyboard()
            } else {
                Toast.makeText(this, "Please paste a link first", Toast.LENGTH_SHORT).show()
            }
        }

        binding.pasteButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
            if (!text.isNullOrBlank()) {
                binding.linkInput.setText(text)
            } else {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startClipboardServiceWithLink(link: String) {
        val serviceIntent = ClipboardService.manualLinkIntent(this, link)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun checkAccessibilityService() {
        updateAccessibilityPrompt()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = android.content.ComponentName(this, JidoAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
        val splitter = TextUtils.SimpleStringSplitter(':')
        splitter.setString(enabledServices)
        while (splitter.hasNext()) {
            val componentName = splitter.next()
            if (componentName.equals(expectedComponentName, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.linkInput.windowToken, 0)
    }

    private fun setupDownloadsList() {
        binding.downloadsRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.downloadsRecyclerView.adapter = adapter

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DownloadRepository.downloads.collect { items ->
                    adapter.submitList(items)
                    binding.emptyStateText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun ensureNotificationPermissionThenStart() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // No runtime notification permission needed before Android 13.
            startClipboardService()
            return
        }

        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED -> {
                startClipboardService()
            }

            ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.POST_NOTIFICATIONS) -> {
                Toast.makeText(
                    this,
                    "Jido needs notification access to run its background link listener.",
                    Toast.LENGTH_LONG
                ).show()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }

            else -> {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startClipboardService() {
        val serviceIntent = Intent(this, ClipboardService::class.java)
        startForegroundService(serviceIntent)
    }
}
