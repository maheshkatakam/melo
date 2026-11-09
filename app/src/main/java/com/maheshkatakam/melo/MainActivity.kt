package com.maheshkatakam.melo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.maheshkatakam.melo.ui.LocalContainer
import com.maheshkatakam.melo.ui.MeloRoot
import com.maheshkatakam.melo.ui.theme.MeloTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val openPlayerRequests = MutableStateFlow(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = appContainer
        container.player.connect()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle()
            MeloTheme(settings) {
                CompositionLocalProvider(LocalContainer provides container) {
                    MeloRoot(openPlayerSignal = openPlayerRequests)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        appContainer.music.onPermissionChanged()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (intent.action == Intent.ACTION_VIEW && data != null) {
            appContainer.player.playExternal(data, null)
            openPlayerRequests.value = openPlayerRequests.value + 1
        } else if (intent.getBooleanExtra(EXTRA_OPEN_PLAYER, false)) {
            openPlayerRequests.value = openPlayerRequests.value + 1
        }
    }

    companion object {
        const val EXTRA_OPEN_PLAYER = "open_player"
    }
}
