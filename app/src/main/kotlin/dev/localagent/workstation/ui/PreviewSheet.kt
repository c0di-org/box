package dev.localagent.workstation.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.localagent.workstation.OpenedPreview

/**
 * Something the agent is serving in the guest, over whatever the user is already doing.
 *
 * A sheet rather than a panel on the desktop, and the difference is the whole point. A preview
 * arrives from a link in the conversation, so it used to answer that tap by navigating to the
 * computer and drawing the page as a floating tool over the machine: the person lost the
 * transcript and the composer they were mid-sentence in, and got a small card of web page parked
 * over an xterm they had not asked to see. Everything in Box that is *to look at* rather than *to
 * work in* — sign-in, a permission diff, diagnostics — is a sheet over where you already were, and
 * a preview is the most look-at-it thing there is. Dismissing it puts the conversation back exactly
 * as it was, because it was never left.
 *
 * A `WebView` rather than handing the url to the phone's browser, because of the address: the
 * forward is on 127.0.0.1, so leaving Box to look at it would work and would put a page the agent
 * is still building into the user's browsing history and tabs.
 *
 * JavaScript is on because the thing previewed is almost always a dev server, and a React page with
 * scripting disabled is a blank rectangle that looks like a bug in Box. What bounds the risk is
 * reach: the page comes from a loopback port forwarding into the guest, the same VM the user
 * already opened deliberately.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewSheet(preview: OpenedPreview, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Held so the header's buttons and the back gesture can drive the page that is actually up.
    var web by remember { mutableStateOf<WebView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var canGoBack by remember { mutableStateOf(false) }
    // What this view has been *told* to load, as opposed to where the page has since gone. Compared
    // against the state's url instead of `WebView.url`, so following a link inside the preview does
    // not read as "the wrong page is up" on the next recomposition and get yanked back to the root.
    var loaded by remember { mutableStateOf<String?>(null) }

    // Back goes back through the page before it closes the sheet — a multi-page preview is a small
    // site, and the alternative is that one wrong tap costs you the whole thing.
    BackHandler(enabled = canGoBack) { web?.goBack() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Wider than the sheets that hold prose: this one holds a page someone wrote a layout for,
        // and on a desktop-sized window a 640dp column would be a phone emulator in a Dex session.
        sheetMaxWidth = 960.dp,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.96f)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "Preview",
                        style = MaterialTheme.typography.labelLarge,
                        fontSize = 11.sp,
                        letterSpacing = 1.1.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // In the header rather than floating over the page, where it used to sit: a
                    // loopback address printed across someone's own content is not a caption, it is
                    // damage to the thing they asked to look at.
                    Text(
                        preview.url ?: "opening port ${preview.guestPort}…",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { web?.reload() }, enabled = preview.url != null) {
                    Icon(
                        Icons.Outlined.Refresh,
                        contentDescription = "Reload preview",
                        modifier = Modifier.size(20.dp),
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Close preview",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())

            Box(Modifier.weight(1f).fillMaxWidth()) {
                val url = preview.url
                if (url == null) {
                    // The gap between the tap and the forward. Something has to be here, or the
                    // sheet reads as a page that failed to load rather than one on its way.
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    AndroidView(
                        factory = { context ->
                            @SuppressLint("SetJavaScriptEnabled")
                            WebView(context).apply {
                                // Kept inside the view: a preview that opened the phone's browser
                                // on a redirect would leave a loopback url in someone's history
                                // pointing at nothing.
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(
                                        view: WebView,
                                        url: String,
                                        favicon: android.graphics.Bitmap?,
                                    ) {
                                        loading = true
                                    }

                                    override fun onPageFinished(view: WebView, url: String) {
                                        loading = false
                                        canGoBack = view.canGoBack()
                                    }
                                }
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                web = this
                            }
                        },
                        update = { view ->
                            if (loaded != url) {
                                loaded = url
                                view.loadUrl(url)
                            }
                        },
                        // The forward is released with the sheet; the view holding a page against a
                        // port that no longer exists should not outlive it either.
                        onRelease = { view ->
                            web = null
                            view.destroy()
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}
