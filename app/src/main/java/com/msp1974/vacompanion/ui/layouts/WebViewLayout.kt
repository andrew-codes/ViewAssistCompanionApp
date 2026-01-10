package com.msp1974.vacompanion.ui.layouts

import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
    // FIRST THING - log that we're called
    android.util.Log.e("WebViewScreen", "!!! WebViewScreen CALLED !!!")
    android.util.Log.e("WebViewScreen", "!!! HA WebView: ${haWebView.url}")
    android.util.Log.e("WebViewScreen", "!!! External WebView: ${externalWebView.url}")

    val vaUiState by vaViewModel.vacaState.collectAsState()

    // Log the state value
    android.util.Log.e("WebViewScreen", "!!! showingHAView state: ${vaUiState.showingHAView}")

    // Log visibility changes
    LaunchedEffect(vaUiState.showingHAView) {
        android.util.Log.d("WebViewScreen", "=== WebView Visibility Changed ===")
        android.util.Log.d("WebViewScreen", "  showingHAView: ${vaUiState.showingHAView}")
        android.util.Log.d("WebViewScreen", "  Visible WebView: ${if (vaUiState.showingHAView) "HA WebView" else "External WebView"}")
        android.util.Log.d("WebViewScreen", "  HA WebView URL: ${haWebView.url}")
        android.util.Log.d("WebViewScreen", "  External WebView URL: ${externalWebView.url}")
    }

    // Log every recomposition
    android.util.Log.d("WebViewScreen", ">>> RECOMPOSING WebViewScreen - showingHAView: ${vaUiState.showingHAView}")

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
            // HA WebView - declared FIRST (bottom layer when both visible)
            Box(modifier = Modifier.fillMaxSize()) {
                WebViewWrapper(
                        webView = haWebView,
                        modifier = Modifier.fillMaxSize(),
                        swipeRefreshEnabled = vaViewModel.config!!.swipeRefresh,
                        isVisible = vaUiState.showingHAView
                )
            }

            // External WebView - declared SECOND (top layer, will be on top if both visible)
            Box(modifier = Modifier.fillMaxSize()) {
                WebViewWrapper(
                        webView = externalWebView,
                        modifier = Modifier.fillMaxSize(),
                        swipeRefreshEnabled = vaViewModel.config!!.swipeRefresh,
                        isVisible = !vaUiState.showingHAView
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
                android.util.Log.d("WebViewWrapper", "FACTORY: Creating wrapper for WebView: ${webView.url}, isVisible: $isVisible")
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

                    // Set initial visibility in factory
                    val initialVisibility = if (isVisible) android.view.View.VISIBLE else android.view.View.GONE
                    visibility = initialVisibility
                    webView.visibility = initialVisibility
                    android.util.Log.d("WebViewWrapper", "FACTORY: Set initial visibility to ${if (isVisible) "VISIBLE" else "GONE"} for ${webView.url}")
                }
            },
            update = { view ->
                view.isRefreshing = refreshing
                view.isEnabled = swipeRefreshEnabled

                // Control visibility at the SwipeRefreshLayout level to properly handle touch events
                val newVisibility = if (isVisible) android.view.View.VISIBLE else android.view.View.GONE

                // Log EVERY update, not just changes
                android.util.Log.e(
                        "WebViewWrapper",
                        "UPDATE: WebView ${webView.url} - isVisible param: $isVisible, setting to: ${if (newVisibility == android.view.View.VISIBLE) "VISIBLE" else "GONE"}"
                )

                if (view.visibility != newVisibility) {
                    android.util.Log.e(
                            "WebViewWrapper",
                            "=== VISIBILITY ACTUALLY CHANGING ==="
                    )
                    android.util.Log.e(
                            "WebViewWrapper",
                            "WebView URL: ${webView.url}"
                    )
                    android.util.Log.e(
                            "WebViewWrapper",
                            "Old visibility: ${if (view.visibility == android.view.View.VISIBLE) "VISIBLE" else "GONE"}"
                    )
                    android.util.Log.e(
                            "WebViewWrapper",
                            "New visibility: ${if (isVisible) "VISIBLE" else "GONE"}"
                    )
                }

                // Set visibility on both the container and the WebView itself
                view.visibility = newVisibility
                webView.visibility = newVisibility

                // Also set clickable state to prevent touch event blocking
                view.isClickable = isVisible
                view.isFocusable = isVisible
                webView.isClickable = isVisible
                webView.isFocusable = isVisible

                // Force the view to request layout
                view.requestLayout()
                webView.requestLayout()
            }
    )
}
