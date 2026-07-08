package com.example.torrentstreamer

import android.net.Uri
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TorrWebScreen(onClose: () -> Unit) {
    var webView: WebView? by remember { mutableStateOf(null) }
    var filePathCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    // Вибір торент-файлів з пам'яті
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filePathCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        filePathCallback = null
    }

    BackHandler {
        if (webView?.canGoBack() == true) webView?.goBack() else onClose()
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Сервер MatriX") },
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.Default.Close, null) }
                },
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { padding ->
        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    webViewClient = WebViewClient()
                    webChromeClient = object : WebChromeClient() {
                        override fun onShowFileChooser(
                            webView: WebView?,
                            callback: ValueCallback<Array<Uri>>?,
                            params: FileChooserParams?
                        ): Boolean {
                            filePathCallback = callback
                            filePicker.launch("*/*")
                            return true
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    loadUrl("http://127.0.0.1:8090")
                    webView = this
                }
            },
            modifier = Modifier.padding(padding).fillMaxSize()
        )
    }
}