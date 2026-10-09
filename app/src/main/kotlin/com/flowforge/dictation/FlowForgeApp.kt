package com.flowforge.dictation

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.flowforge.dictation.data.DictionaryRepository
import com.flowforge.dictation.data.FlowForgeDatabase
import com.flowforge.dictation.data.HistoryRepository
import com.flowforge.dictation.data.SecureBox
import com.flowforge.dictation.data.SettingsStore
import com.flowforge.dictation.util.Notifications
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class FlowForgeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        Notifications.createChannels(this)
        graph.io.execute { graph.history.purgeExpired() }
    }
}

/** Process-wide singletons. Created once in [FlowForgeApp.onCreate]. */
class AppGraph(val app: Application) {
    /** Single background thread for disk and network work (keeps ordering simple). */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "flowforge-io").apply { isDaemon = true } }
    val main = Handler(Looper.getMainLooper())

    val secureBox: SecureBox by lazy { SecureBox() }
    val settings: SettingsStore by lazy { SettingsStore(app, secureBox) }
    private val database: FlowForgeDatabase by lazy { FlowForgeDatabase(app) }
    val dictionary: DictionaryRepository by lazy { DictionaryRepository(database, secureBox) }
    val history: HistoryRepository by lazy { HistoryRepository(database, secureBox, settings) }

    /** Runs [work] on the IO thread and hands its result to [then] on the main thread. */
    fun <T> background(work: () -> T, then: (Result<T>) -> Unit) {
        io.execute {
            val r = runCatching(work)
            main.post { then(r) }
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as FlowForgeApp).graph
