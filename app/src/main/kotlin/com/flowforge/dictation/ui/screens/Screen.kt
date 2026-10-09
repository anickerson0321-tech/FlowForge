package com.flowforge.dictation.ui.screens

import android.view.View
import com.flowforge.dictation.AppGraph
import com.flowforge.dictation.graph
import com.flowforge.dictation.ui.MainActivity
import com.flowforge.dictation.ui.kit.Ui

/**
 * A screen is rebuilt from scratch on refresh (state lives in settings/repositories); on
 * fold/unfold or rotation its existing views are kept and only re-parented.
 */
abstract class Screen(protected val host: MainActivity) {
    abstract val title: String
    /** Rebuild when the activity resumes (for screens showing permission/setup status). */
    open val refreshOnResume: Boolean = false
    abstract fun build(ui: Ui): View
    open fun onPause() {}
    open fun onDestroy() {}
    protected val graph: AppGraph get() = host.graph
}
