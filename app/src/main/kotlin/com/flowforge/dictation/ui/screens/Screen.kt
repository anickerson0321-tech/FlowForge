package com.flowforge.dictation.ui.screens

import android.view.View
import com.flowforge.dictation.AppGraph
import com.flowforge.dictation.graph
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Ui

/** A screen is rebuilt from scratch on every refresh; state lives in settings/repositories. */
abstract class Screen(protected val host: MainActivity) {
    abstract val title: String
    abstract fun build(ui: Ui): View
    open fun onPause() {}
    open fun onDestroy() {}
    protected val graph: AppGraph get() = host.graph
}
