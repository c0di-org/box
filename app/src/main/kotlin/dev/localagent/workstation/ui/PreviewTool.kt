package dev.localagent.workstation.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.localagent.workstation.BoxUiState

/**
 * Something the agent is serving in the guest, drawn over the machine that is serving it.
 *
 * A `WebView` rather than handing the url to the phone's browser, and the reason is the address:
 * the forward is on 127.0.0.1, so it only exists inside this app's own network namespace as far as
 * the user is concerned — leaving Box to look at it would work, but it would put a page the agent
 * is still building in the middle of their browsing history and their tabs.
 *
 * JavaScript is on because the thing being previewed is almost always a dev server, and a React
 * page with scripting disabled is a blank rectangle that looks like a bug in Box. What bounds the
 * risk is where it can reach: the page is loaded from a loopback port that forwards into the
 * guest, which is the same VM the user already opened deliberately.
 */
@Composable
fun PreviewTool(state: BoxUiState) {
    val preview = state.preview

    if (preview == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { context ->
                @SuppressLint("SetJavaScriptEnabled")
                WebView(context).apply {
                    // Kept inside the view: a preview that opened the phone's browser on a redirect
                    // would leave a loopback url in someone's history pointing at nothing.
                    webViewClient = WebViewClient()
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                }
            },
            // Keyed on the url so a second preview reloads rather than showing the first one's page.
            update = { view -> if (view.url != preview.url) view.loadUrl(preview.url) },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            preview.url,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}
