package com.flowforge.dictation.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.flowforge.dictation.overlay.DictationService

/**
 * Invisible trampoline used by the Quick Settings tile. A microphone foreground service may only
 * start while the app is visible, and Android 14+ re-checks that when the service calls
 * startForeground(). So this transparent activity stays alive briefly (it draws nothing) until the
 * service has had time to enter the foreground, then finishes. If setup is incomplete it opens the
 * main app on the permissions screen instead.
 */
class BubbleLauncherActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val problem = DictationService.show(this)
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SCREEN, "permissions"))
            finish()
            return
        }
        window.decorView.postDelayed({ if (!isFinishing) finish() }, FINISH_DELAY_MS)
    }

    private companion object {
        const val FINISH_DELAY_MS = 800L
    }
}
