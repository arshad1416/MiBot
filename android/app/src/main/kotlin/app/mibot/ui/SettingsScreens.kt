package app.mibot.ui

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mibot.BuildConfig
import app.mibot.core.AppsResponse
import app.mibot.core.HubException
import app.mibot.core.Toolkit
import app.mibot.notify.HubService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The phone's own settings, and the way into the hub's. */
@Composable
fun SettingsScreen() {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val app = LocalApp.current
    val state by hub.state.collectAsStateWithLifecycle()
    var notifications by remember { mutableStateOf(app.store.notifications && app.notifier.allowed) }
    var confirmSignOut by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }

    fun setNotifications(on: Boolean) {
        app.store.notifications = on
        notifications = on && app.notifier.allowed
        note = if (on && !app.notifier.allowed) "Notifications for MiBot are off in the phone's settings. Turn them on there." else null
        HubService.sync(app)
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { setNotifications(true) }

    Screen("Settings", { nav.back() }) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MiCard(padding = PaddingValues(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).border(1.dp, Mi.colors.border, CircleShape), contentAlignment = Alignment.Center) {
                        Text(initials(state.user), style = Type.bodyStrong, color = Mi.colors.text)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.user?.name?.takeIf { it.isNotBlank() } ?: "Signed in", style = Type.bodyStrong, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        state.user?.email?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, color = Mi.colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }

            MiCard {
                CardRow("Connect apps", icon = MiIcons.apps, onClick = { nav.go(Dest.Apps(null)) })
                Hairline()
                CardRow("Models", icon = MiIcons.sliders, onClick = { nav.go(Dest.Web("Models", "/?settings=models")) })
                Hairline()
                CardRow("Machines", icon = MiIcons.phone, onClick = { nav.go(Dest.Web("Machines", "/?settings=machines")) })
                Hairline()
                CardRow("Bot computer", icon = MiIcons.monitor, onClick = { nav.go(Dest.Web("Bot computer", "/?settings=computer")) })
                Hairline()
                CardRow("Permissions", icon = MiIcons.verified, onClick = { nav.go(Dest.Web("Permissions", "/?settings=permissions")) })
            }

            MiCard {
                CardRow("Notifications", icon = MiIcons.bell, subtitle = "When a bot finishes or is waiting for you. MiBot stays connected to your hub in the background for this.") {
                    MiToggle(notifications, { on ->
                        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !app.notifier.allowed) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else setNotifications(on)
                    }, label = "Notifications")
                }
            }
            note?.let { ErrorNote(it) }

            MiCard {
                CardRow("Hub", icon = MiIcons.globe, value = hub.client.account.url.substringAfter("://"))
                Hairline()
                CardRow("Open MiBot's web app", icon = MiIcons.link, onClick = { nav.go(Dest.Web("MiBot", "/")) })
            }

            MiCard { CardRow("Sign out", danger = true, onClick = { confirmSignOut = true }) }

            Text("MiBot ${BuildConfig.VERSION_NAME}", style = Type.caption, color = Mi.colors.textTertiary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
        }
    }

    if (confirmSignOut) {
        ConfirmDialog("Sign out of this phone?", "To use MiBot here again you add the phone with a new code from Settings › Machines.", "Sign out", { app.signOut() }, { confirmSignOut = false }, danger = true)
    }
}

/**
 * The apps a bot can work in. With a bot (`botId`), each connected app has a switch for that bot;
 * from Settings (`botId` null) it is the account's list. Connect once, then switch on per bot.
 */
@Composable
fun AppsScreen(botId: String?) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by hub.state.collectAsStateWithLifecycle()
    val bot = state.bot(botId)
    var query by rememberSaveable { mutableStateOf("") }
    var apps by remember { mutableStateOf<AppsResponse?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val flow = remember { ConnectFlow(hub.client, context) }
    /** The app a key is being asked for. */
    var keyFor by remember { mutableStateOf<String?>(null) }

    // Fetched again when the search changes (after a pause in typing) and whenever the hub says a connection changed.
    LaunchedEffect(query, state.connectionsVersion, reload) {
        if (apps != null) delay(300)
        try {
            apps = hub.client.apps(query.trim())
            error = null
        } catch (e: HubException) {
            error = e.message
        }
    }
    fun run(work: suspend () -> Unit) {
        scope.launch {
            try { work(); error = null; reload++ } catch (e: HubException) { error = e.message }
        }
    }

    Screen(if (bot != null) "Apps for ${bot.name}" else "Connect apps", { nav.back() }) {
        MiTextField(query, { query = it }, "Search apps", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), leading = { MiIcon(MiIcons.search, 20.dp, Mi.colors.textTertiary) })
        error?.let { ErrorNote(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
        val all = apps
        if (all == null) {
            EmptyNote(if (error == null) "Loading apps…" else "")
            return@Screen
        }
        val connected = all.connectedApps.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        val others = all.apps.filter { a -> !a.connected && connected.none { it.slug == a.slug } }
        LazyColumn(Modifier.weight(1f).imePadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (connected.isNotEmpty()) {
                item { SectionLabel("Connected") }
                items(connected, key = { "c-${it.slug}" }) { app ->
                    AppRow(app) {
                        if (botId != null) {
                            MiToggle(botId in app.bots, { on -> run { hub.client.setAppForBot(app.slug, botId, on) } }, label = "${app.name} for ${bot?.name.orEmpty()}")
                        } else {
                            MoreButton(listOf(MenuItem("Disconnect", MiIcons.close, danger = true) { run { hub.client.disconnectApp(app.slug) } }))
                        }
                    }
                }
            }
            if (others.isNotEmpty()) {
                item { SectionLabel(if (connected.isEmpty()) "Apps" else "More apps") }
                items(others, key = { "a-${it.slug}" }) { app ->
                    Column {
                        AppRow(app) {
                            PillButton("Connect", {
                                keyFor = app.slug
                                flow.keyPrompt = null
                                run { flow.start(app.slug, botId) }
                            }, primary = false, small = true)
                        }
                        val prompt = flow.keyPrompt
                        if (keyFor == app.slug && prompt != null) {
                            KeyEntry(prompt, { key -> run { flow.start(app.slug, botId, apiKey = key) } }, Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp))
                        }
                    }
                }
            }
            if (connected.isEmpty() && others.isEmpty()) item { EmptyNote("No app matches that.") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = Type.small, fontWeight = FontWeight.SemiBold, color = Mi.colors.textSecondary, modifier = Modifier.padding(start = 22.dp, top = 14.dp, bottom = 4.dp))
}

@Composable
private fun AppRow(app: Toolkit, trailing: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AppLogo(app.name, app.logo, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(app.name.ifBlank { app.slug }, style = Type.bodyStrong, color = Mi.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            app.description?.takeIf { it.isNotBlank() }?.let { Text(it, style = Type.small, color = Mi.colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/**
 * A page of MiBot's web app inside this one, signed in as this phone. It is the way to the settings
 * this app does not have screens of its own for yet (models, machines, permissions).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(title: String, path: String) {
    val hub = LocalHub.current
    val nav = LocalNav.current
    var view by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    BackHandler(enabled = canGoBack) { view?.goBack() }

    Screen(title, { nav.back() }) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize().imePadding(),
                factory = { context ->
                    val address = hub.client.url(path)
                    CookieManager.getInstance().apply {
                        setAcceptCookie(true)
                        setCookie(address, "${hub.client.cookie}; Path=/")
                    }
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // The web app is laid out for a wide screen: show all of it, and let the person zoom.
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        webViewClient = object : WebViewClient() {
                            override fun doUpdateVisitedHistory(web: WebView, url: String?, isReload: Boolean) {
                                canGoBack = web.canGoBack()
                            }

                            override fun onReceivedError(web: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) failed = true
                            }

                            override fun onReceivedHttpError(web: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                                if (request.isForMainFrame) failed = true
                            }
                        }
                        loadUrl(address)
                        view = this
                    }
                },
                onRelease = { it.destroy() },
            )
            if (failed) {
                Column(
                    Modifier.fillMaxSize().background(Mi.colors.bg).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Text("This page did not open. Your hub may be out of reach from here.", style = Type.body, color = Mi.colors.textSecondary, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    PillButton("Try again", { failed = false; view?.reload() }, primary = false)
                }
            }
        }
    }
}
