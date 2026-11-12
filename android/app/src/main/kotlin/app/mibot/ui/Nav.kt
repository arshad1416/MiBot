package app.mibot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import app.mibot.MiBotApp
import app.mibot.core.HubSession

/** A screen of the app. */
sealed interface Dest {
    data object Home : Dest
    data class Chat(val botId: String) : Dest
    data class Profile(val botId: String) : Dest
    data class EditBot(val botId: String) : Dest
    data object CreateBot : Dest
    data class Routines(val botId: String) : Dest
    data class Library(val botId: String) : Dest
    data class Computer(val botId: String) : Dest
    data object Settings : Dest
    /** `botId`: the bot whose switches to show for each connected app, or null from Settings. */
    data class Apps(val botId: String?) : Dest
    /** A page of the web app, shown inside this one. `path` is on the hub, such as "/settings/models". */
    data class Web(val title: String, val path: String) : Dest
}

/** Which screens are open, bottom to top. The last one is on screen. */
class Nav {
    val stack = mutableStateListOf<Dest>(Dest.Home)
    /** The last move went deeper (true) or back (false): decides which way screens slide. */
    var forward = true
        private set

    val top: Dest get() = stack.last()

    fun go(dest: Dest) {
        if (top == dest) return
        forward = true
        stack.add(dest)
    }

    fun back() {
        if (stack.size <= 1) return
        forward = false
        stack.removeAt(stack.lastIndex)
    }

    /** Leave whatever is open and show this bot's chat over the bot list. */
    fun openChat(botId: String) {
        forward = true
        stack.clear()
        stack.addAll(listOf(Dest.Home, Dest.Chat(botId)))
    }

    /** Back to the bot list, as after a bot is deleted. */
    fun home() {
        forward = false
        stack.clear()
        stack.add(Dest.Home)
    }
}

val LocalNav = staticCompositionLocalOf<Nav> { error("No navigation here") }
val LocalHub = staticCompositionLocalOf<HubSession> { error("Not signed in") }
val LocalApp = staticCompositionLocalOf<MiBotApp> { error("No app here") }

@Composable
fun Root(app: MiBotApp, openBot: MutableState<String?>) {
    Box(Modifier.fillMaxSize().background(Mi.colors.bg)) {
        CompositionLocalProvider(LocalApp provides app) {
            val hub = app.hub
            if (hub == null) PairScreen(onPaired = app::signIn)
            else key(hub) {
                CompositionLocalProvider(LocalHub provides hub) { Screens(openBot) }
            }
        }
    }
}

@Composable
private fun Screens(openBot: MutableState<String?>) {
    val nav = remember { Nav() }
    val holder = rememberSaveableStateHolder()
    LaunchedEffect(openBot.value) {
        openBot.value?.let {
            nav.openChat(it)
            openBot.value = null
        }
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.back() }
    CompositionLocalProvider(LocalNav provides nav) {
        AnimatedContent(
            targetState = nav.top,
            transitionSpec = {
                val dir = if (nav.forward) 1 else -1
                (slideInHorizontally(tween(220)) { it / 8 * dir } + fadeIn(tween(200))) togetherWith
                    (slideOutHorizontally(tween(180)) { -it / 8 * dir } + fadeOut(tween(120)))
            },
            label = "screen",
        ) { dest ->
            // Each screen keeps its own scroll position and typed text while another is on top of it.
            holder.SaveableStateProvider(dest.toString()) {
                when (dest) {
                    Dest.Home -> HomeScreen()
                    is Dest.Chat -> ChatScreen(dest.botId)
                    is Dest.Profile -> ProfileScreen(dest.botId)
                    is Dest.EditBot -> EditBotScreen(dest.botId)
                    Dest.CreateBot -> EditBotScreen(null)
                    is Dest.Routines -> RoutinesScreen(dest.botId)
                    is Dest.Library -> LibraryScreen(dest.botId)
                    is Dest.Computer -> ComputerScreen(dest.botId)
                    Dest.Settings -> SettingsScreen()
                    is Dest.Apps -> AppsScreen(dest.botId)
                    is Dest.Web -> WebScreen(dest.title, dest.path)
                }
            }
        }
    }
}
