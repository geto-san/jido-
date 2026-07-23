package com.geto.jido

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Accessibility Service that monitors UI events to detect clipboard changes
 * in the background on Android 10+.
 */
class JidoAccessibilityService : AccessibilityService() {

    private var lastHandledClip: String? = null

    companion object {
        private const val TAG = "JidoAccessibility"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // We listen for events that might indicate a copy operation or a link being interacted with.
        // On many Android versions, copying text triggers TYPE_VIEW_CLICKED or TYPE_WINDOW_CONTENT_CHANGED.
        
        // When an event occurs, we check the clipboard. Because this service is "active", 
        // it often satisfies the system's requirement for a background app to have some 
        // level of user-driven context to read the clipboard.
        
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).text?.toString()
            if (!text.isNullOrBlank() && text != lastHandledClip) {
                val platform = SupportedLinks.detect(text)
                if (platform != null) {
                    lastHandledClip = text
                    Log.d(TAG, "Accessibility detected $platform link: $text")
                    startForegroundService(ClipboardService.manualLinkIntent(this, text))
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
        
        // Initialize with current clipboard to ignore it
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        lastHandledClip = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.text?.toString()
    }
}
