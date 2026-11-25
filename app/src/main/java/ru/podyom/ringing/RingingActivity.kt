package ru.podyom.ringing

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.core.os.BundleCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.podyom.app
import ru.podyom.core.RingingPriority
import ru.podyom.data.TechnicalEvent
import ru.podyom.ui.PodyomTheme
import ru.podyom.wakecheck.CheckRoute

class RingingActivity : ComponentActivity() {
    private val vm by viewModels<RingingViewModel>()
    private var request by mutableStateOf(Intent())
    private var wakeRoute: CheckRoute? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        val restored = savedInstanceState?.let { BundleCompat.getParcelable(it, "ringingRequest", Intent::class.java) }
        receive(restored ?: intent, opened = savedInstanceState == null)
        wakeRoute?.let { vm.recordRoute(it, TechnicalEvent.WAKE_ACTIVITY_CREATED) }
        setContent {
            val loaded by vm.runs.collectAsStateWithLifecycle()
            val route = CheckRoute.from(request)
            val id = request.getStringExtra("runId").orEmpty()
            val active = if (request.getBooleanExtra("followRinging", false))
                RingingPriority.next(loaded.orEmpty())?.id ?: id else id
            PodyomTheme {
                key(route?.key, active) {
                    RingingScreen(active, vm, onClose = { finish() }, route = route,
                        openRing = { nextId -> receive(Intent().putExtra("runId", nextId), opened = false) })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        receive(intent, opened = true)
        if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            wakeRoute?.let { vm.recordRoute(it, TechnicalEvent.WAKE_ACTIVITY_RESUMED) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable("ringingRequest", request)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        wakeRoute?.let { vm.recordRoute(it, TechnicalEvent.WAKE_ACTIVITY_RESUMED) }
    }

    private fun receive(intent: Intent, opened: Boolean) {
        setIntent(intent)
        request = intent
        wakeRoute = CheckRoute.from(intent)
        wakeRoute?.takeIf { opened && it.source == "notification" }
            ?.let { app.journal.report(TechnicalEvent.NOTIFICATION_OPENED, it.key.runId, it.version, it.source) }
    }
}
