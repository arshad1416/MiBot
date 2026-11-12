package app.mibot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.mibot.MiBotApp
import app.mibot.core.HubAccount
import app.mibot.core.HubClient
import app.mibot.core.HubException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.launch

/**
 * The first screen: add this phone to a hub. The hub shows a QR code in Settings › Machines; scanning
 * it signs the phone in. The same can be done by typing the hub's address and the short code next to the QR.
 */
@Composable
fun PairScreen(onPaired: (HubAccount) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typing by rememberSaveable { mutableStateOf(false) }
    var address by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scroll = rememberScrollState()
    // With the keyboard up there is less room: keep the button under the fields in view.
    val keyboard = WindowInsets.isImeVisible
    LaunchedEffect(keyboard, scroll.maxValue) { if (keyboard) scroll.scrollTo(scroll.maxValue) }

    fun pair(hubUrl: String, pairCode: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                onPaired(HubClient.pair(hubUrl, pairCode, userAgent = MiBotApp.USER_AGENT))
            } catch (e: HubException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    fun scan() {
        error = null
        val options = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(context, options).startScan()
            .addOnSuccessListener { found ->
                val link = HubClient.parsePairLink(found.rawValue.orEmpty())
                if (link != null) pair(link.hubUrl, link.code)
                else error = "That is not a MiBot code. Scan the one under Settings › Machines › Add a device."
            }
            .addOnFailureListener {
                error = "The scanner could not start on this phone. Type the address and the code instead."
                typing = true
            }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().verticalScroll(scroll).padding(horizontal = 28.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BotAvatar("blob", "black", if (typing) 76.dp else 132.dp, mood = Mood.Awake)
        Spacer(Modifier.height(if (typing) 14.dp else 24.dp))
        Text("MiBot", style = if (typing) Type.title else Type.display, color = Mi.colors.text)
        Spacer(Modifier.height(10.dp))
        Text(
            if (typing) "On a computer where MiBot is open, go to Settings › Machines › Add a device. It shows the code and your hub's address next to the QR."
            else "Add this phone to your hub. On a computer where MiBot is open, go to Settings › Machines › Add a device, and scan the code it shows.",
            style = if (typing) Type.small else Type.body, color = Mi.colors.textSecondary, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(if (typing) 20.dp else 28.dp))

        if (!typing) {
            PillButton(if (busy) "Adding this phone…" else "Scan the code", ::scan, Modifier.fillMaxWidth(), enabled = !busy, icon = MiIcons.qr)
            Spacer(Modifier.height(6.dp))
            TextLink("Type the code instead", { typing = true; error = null })
        } else {
            FieldLabel("Your hub's address", Modifier.fillMaxWidth())
            MiTextField(
                address, { address = it.trim() }, "https://mac-mini.example.ts.net",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false),
            )
            Spacer(Modifier.height(16.dp))
            FieldLabel("The code next to the QR", Modifier.fillMaxWidth())
            val ready = address.isNotBlank() && code.length >= 4
            MiTextField(
                code, { code = HubClient.normalizeCode(it) }, "ABCD2345",
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { if (ready) pair(address, code) }),
            )
            Spacer(Modifier.height(20.dp))
            PillButton(if (busy) "Adding this phone…" else "Add this phone", { pair(address, code) }, Modifier.fillMaxWidth(), enabled = ready && !busy)
            Spacer(Modifier.height(6.dp))
            TextLink("Scan the code instead", { typing = false; error = null })
        }

        error?.let {
            Spacer(Modifier.height(16.dp))
            ErrorNote(it)
        }
    }
}
