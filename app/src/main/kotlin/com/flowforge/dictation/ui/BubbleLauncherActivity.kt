package com.flowforge.dictation.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.flowforge.dictation.overlay.DictationService

/**
 * Invisible trampoline used by the Quick Settings tile. Being a visible activity for a moment is
 * what allows the microphone foreground service to start; it finishes immediately.
 * If setup is incomplete it opens the main app on the permissions screen instead.
 */
class BubbleLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val problem = DictationService.show(this)
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "permissions"))
        }
        finish()
    }
}
