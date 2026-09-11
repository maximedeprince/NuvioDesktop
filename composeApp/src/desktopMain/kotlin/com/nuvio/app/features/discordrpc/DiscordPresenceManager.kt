package com.nuvio.app.features.discordrpc

import co.touchlab.kermit.Logger
import com.nuvio.app.AppScreenTab
import com.nuvio.app.core.ui.AppPresenceState
import com.nuvio.app.core.ui.PresenceSnapshot
import com.nuvio.app.features.settings.DiscordRichPresenceRepository
import com.nuvio.app.features.settings.ThemeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_nav_settings
import nuvio.composeapp.generated.resources.discord_presence_browsing_app
import nuvio.composeapp.generated.resources.discord_presence_browsing_section
import nuvio.composeapp.generated.resources.discord_presence_paused
import nuvio.composeapp.generated.resources.discord_presence_paused_episode
import nuvio.composeapp.generated.resources.discord_presence_viewing
import org.jetbrains.compose.resources.getString

private class DiscordDisconnected : Exception()

private const val ReconnectDelayMs = 15_000L

// Discord activity type: 0 = Playing, 2 = Listening, 3 = Watching, 5 = Competing.
// Nuvio is a media app, so every presence it publishes is a Watching one -- including the menus,
// where Discord renders "Watching Nuvio" instead of the default "Playing Nuvio" (a game).
private const val WatchingActivityType = 3

// Product name, deliberately not translated: it is what the Discord application is registered as.
private const val AppName = "Nuvio"

internal object DiscordPresenceManager {
    private val log = Logger.withTag("DiscordPresenceManager")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = DiscordIpcClient(DiscordConfig.CLIENT_ID)
    private var syncJob: Job? = null
    private var lastActivity: DiscordActivity? = null

    fun start() {
        if (DiscordConfig.CLIENT_ID.isBlank()) return
        DiscordRichPresenceRepository.ensureLoaded()
        scope.launch {
            DiscordRichPresenceRepository.enabled.collectLatest { enabled ->
                if (enabled) startSync() else stopSync()
            }
        }
    }

    fun shutdown() {
        runBlocking { stopSync() }
    }

    private suspend fun startSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                val connected = client.connect()
                if (connected) {
                    lastActivity = null
                    try {
                        // Re-emit on app language changes so the presence is rebuilt in the newly
                        // selected language without waiting for the next navigation.
                        combine(
                            AppPresenceState.current,
                            ThemeSettingsRepository.selectedAppLanguage,
                        ) { snapshot, _ -> snapshot }.collect { snapshot ->
                            val activity = snapshot?.toDiscordActivity() ?: idleActivity()
                            if (activity == lastActivity) return@collect
                            if (client.setActivity(activity)) {
                                lastActivity = activity
                            } else {
                                throw DiscordDisconnected()
                            }
                        }
                    } catch (e: DiscordDisconnected) {
                        log.d { "Discord IPC disconnected, retrying" }
                    }
                }
                delay(ReconnectDelayMs)
            }
        }
    }

    private suspend fun stopSync() {
        syncJob?.cancel()
        syncJob = null
        if (lastActivity != null) client.setActivity(null)
        delay(300L)
        lastActivity = null
        client.disconnect()
    }
}

private fun String.toDiscordEpisodeLabel(): String {
    val match = Regex("""S(\d+)E(\d+)(?:\s*-\s*(.*))?""").matchEntire(trim())
        ?: return this
    val season = match.groupValues[1]
    val episode = match.groupValues[2]
    val title = match.groupValues.getOrNull(3).orEmpty().trim()
    return if (title.isBlank()) "S$season, E$episode" else "S$season, E$episode: $title"
}

// Shown when nothing has been published yet, so the profile still reads "Watching Nuvio".
private suspend fun idleActivity(): DiscordActivity = DiscordActivity(
    type = WatchingActivityType,
    details = getString(Res.string.discord_presence_browsing_app, AppName),
)

private suspend fun AppScreenTab.localizedLabel(): String = getString(
    when (this) {
        AppScreenTab.Home -> Res.string.compose_nav_home
        AppScreenTab.Search -> Res.string.compose_nav_search
        AppScreenTab.Library -> Res.string.compose_nav_library
        AppScreenTab.Settings -> Res.string.compose_nav_settings
    },
)

private suspend fun PresenceSnapshot.toDiscordActivity(): DiscordActivity = when (this) {
    is PresenceSnapshot.Tab -> DiscordActivity(
        type = WatchingActivityType,
        // A separate string from the app one: a tab name is a common noun, so languages that
        // decline it or need an article cannot take it inside a sentence the way "Nuvio" fits.
        details = getString(Res.string.discord_presence_browsing_section, tab.localizedLabel()),
    )

    is PresenceSnapshot.Details -> DiscordActivity(
        type = WatchingActivityType,
        details = getString(Res.string.discord_presence_viewing, title),
    )

    is PresenceSnapshot.Player -> {
        val episode = episodeLabel?.toDiscordEpisodeLabel()
        // Discord expects Unix timestamps in seconds, not milliseconds.
        val startSecs = (System.currentTimeMillis() - positionMs) / 1_000L
        DiscordActivity(
            type = WatchingActivityType,
            name = title, // Show the media title under the pseudo when the client honors it.
            details = title, // Always keep the title here as a fallback for clients that ignore `name`.
            state = when {
                isPlaying -> episode
                episode != null -> getString(Res.string.discord_presence_paused_episode, episode)
                else -> getString(Res.string.discord_presence_paused)
            },
            timestamps = if (isPlaying) {
                // start + end -> Discord renders a live progress bar with time remaining.
                DiscordActivityTimestamps(
                    start = startSecs,
                    end = if (durationMs > 0L) startSecs + durationMs / 1_000L else null,
                )
            } else {
                null
            },
            assets = posterUrl?.let { DiscordActivityAssets(largeImage = it, largeText = title) },
        )
    }
}
