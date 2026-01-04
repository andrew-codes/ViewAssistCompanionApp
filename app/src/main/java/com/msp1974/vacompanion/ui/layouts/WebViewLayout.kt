package com.msp1974.vacompanion.ui.layouts

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.msp1974.vacompanion.settings.PageLoadingStage
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.DiagnosticBar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun WebViewScreen(
        haWebView: WebView,
        externalWebView: WebView,
        vaViewModel: VAViewModel = viewModel()
) {
    val vaUiState by vaViewModel.vacaState.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        var containerModifier =
                Modifier.fillMaxSize()
                        .background(
                                if (vaUiState.satelliteRunning) Color.Black
                                else MaterialTheme.colorScheme.background
                        )

        if (vaUiState.isDND) {
            containerModifier = containerModifier.border(4.dp, Color.Red)
        }

        Box(modifier = containerModifier) {
            // External WebView - visible when showingHAView is false
            Box(modifier = Modifier.fillMaxSize()) {
                WebViewWrapper(
                        webView = externalWebView,
                        modifier = Modifier.fillMaxSize(),
                        swipeRefreshEnabled = vaViewModel.config!!.swipeRefresh,
                        isVisible = !vaUiState.showingHAView
                )
            }

            // HA WebView - visible when showingHAView is true
            Box(modifier = Modifier.fillMaxSize()) {
                WebViewWrapper(
                        webView = haWebView,
                        modifier = Modifier.fillMaxSize(),
                        swipeRefreshEnabled = vaViewModel.config!!.swipeRefresh,
                        isVisible = vaUiState.showingHAView
                )
            }
        }

        // Show loading overlay when navigating to prevent flash of HA chrome before kiosk mode
        // activates
        if (vaUiState.webViewPageLoadingStage != PageLoadingStage.LOADED &&
                        vaUiState.webViewPageLoadingStage != PageLoadingStage.NOT_STARTED
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                // Optional: Add a loading indicator here if desired
                // For now, just show black screen to prevent flash
            }
        }

        if (vaUiState.diagnosticInfo.show) {
            DiagnosticBar(vaUiState.diagnosticInfo, modifier = Modifier.align(Alignment.TopCenter))
        }
    }
}

@Composable
fun WebViewWrapper(
        webView: WebView,
        modifier: Modifier = Modifier,
        swipeRefreshEnabled: Boolean = true,
        isVisible: Boolean = true,
) {
    val refreshScope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }

    AndroidView(
            modifier = modifier.fillMaxSize(),
            factory = { context ->
                SwipeRefreshLayout(context).apply {
                    setOnRefreshListener {
                        refreshScope.launch {
                            refreshing = true
                            webView.reload()
                            delay(1500)
                            refreshing = false
                        }
                    }
                    if (webView.parent != null) {
                        (webView.parent as ViewGroup).removeView(webView)
                    }
                    addView(webView).apply { tag = "vaWebView" }
                }
            },
            update = { view ->
                view.isRefreshing = refreshing
                view.isEnabled = swipeRefreshEnabled
                // Control visibility at the SwipeRefreshLayout level to properly handle touch
                // events
                val newVisibility =
                        if (isVisible) android.view.View.VISIBLE else android.view.View.GONE
                if (view.visibility != newVisibility) {
                    android.util.Log.d(
                            "WebViewWrapper",
                            "Changing view visibility to ${if (isVisible) "VISIBLE" else "GONE"}, URL: ${webView.url}"
                    )
                }
                view.visibility = newVisibility

                // Also set clickable state to prevent touch event blocking
                view.isClickable = isVisible
                view.isFocusable = isVisible
            }
    )
}
