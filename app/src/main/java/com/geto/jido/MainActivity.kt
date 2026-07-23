package com.geto.jido

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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
        setupManualLinkRow()
        ensureNotificationPermissionThenStart()
    }

    /**
     * "Paste link" row: an explicit alternative path to the automatic
     * clipboard listener. Useful for testing a link right away, or on
     * devices/keyboards where OnPrimaryClipChangedListener doesn't fire
     * reliably. The EditText itself also accepts a normal long-press ▸ Paste
     * from the keyboard, so the dedicated paste button is just a shortcut.
     */
    private fun setupManualLinkRow() {
        binding.pasteButton.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipText = clipboard.primaryClip
                ?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)
                ?.text
                ?.toString()

            if (clipText.isNullOrBlank()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            } else {
                binding.linkInput.setText(clipText)
                binding.linkInput.setSelection(clipText.length)
            }
        }

        binding.downloadButton.setOnClickListener {
            val link = binding.linkInput.text?.toString()?.trim().orEmpty()
            submitManualLink(link)
        }
    }

    private fun submitManualLink(link: String) {
        if (link.isBlank()) {
            Toast.makeText(this, "Paste a link first", Toast.LENGTH_SHORT).show()
            return
        }
        if (SupportedLinks.detect(link) == null) {
            Toast.makeText(
                this,
                "That doesn't look like a Pinterest, Instagram, or TikTok link",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // Make sure the foreground service is up (it may not be, if notification
        // permission was denied) before handing it the link to process.
        startClipboardService()

        val serviceIntent = ClipboardService.manualLinkIntent(this, link)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        binding.linkInput.setText("")
        Toast.makeText(this, "Fetching media…", Toast.LENGTH_SHORT).show()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }
}
