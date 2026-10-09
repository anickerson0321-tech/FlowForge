package com.flowforge.dictation.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.flowforge.dictation.BuildConfig
import com.flowforge.dictation.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = MainScope()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this)
        setContentView(tv)
        scope.launch { tv.text = getString(R.string.app_name) + " " + BuildConfig.VERSION_NAME }
    }
}
