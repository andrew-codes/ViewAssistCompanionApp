package com.msp1974.vacompanion.ui.layouts

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.msp1974.vacompanion.ui.VAViewModel
import com.msp1974.vacompanion.ui.components.VADialog
import com.msp1974.vacompanion.utils.CustomWebView

@Composable
fun MainLayout(webView: CustomWebView, viewModel: VAViewModel) {
    val vaUiState by viewModel.vacaState.collectAsState()

    Surface(
        modifier = Modifier
            .fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        if (vaUiState.satelliteRunning) {
            WebViewScreen(webView)
        } else {
            ConnectionScreen()
        }
        when {
            vaUiState.alertDialog != null -> {
                VADialog(
                    onDismissRequest = {
                        vaUiState.alertDialog!!.onDismiss()
                    },
                    onConfirmation = {
                        vaUiState.alertDialog!!.onConfirm()
                    },
                    dialogTitle = vaUiState.alertDialog!!.title,
                    dialogText = vaUiState.alertDialog!!.message,
                    confirmText = vaUiState.alertDialog!!.confirmText,
                    dismissText = vaUiState.alertDialog!!.dismissText
                )
            }
        }
    }
}