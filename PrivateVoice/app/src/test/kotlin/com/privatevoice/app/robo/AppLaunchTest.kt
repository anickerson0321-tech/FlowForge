package com.privatevoice.app.robo

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.privatevoice.app.ui.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

fun View.allTexts(): List<String> = when (this) {
    is TextView -> listOf(text.toString())
    is ViewGroup -> (0 until childCount).flatMap { getChildAt(it).allTexts() }
    else -> emptyList()
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AppLaunchTest {
    @Test fun dashboardRenders() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val texts = activity.window.decorView.allTexts()
        println("Dashboard texts: " + texts.take(30))
        assertTrue(texts.contains("PrivateVoice"))
        assertTrue(texts.contains("Read latest message"))
    }
}
