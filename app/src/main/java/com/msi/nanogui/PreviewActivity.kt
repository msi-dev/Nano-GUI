package com.msi.nanogui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Base64
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.msi.nanogui.ui.theme.MyApplicationTheme

class PreviewActivity : ComponentActivity() {

    companion object {
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_CONTENT = "extra_content"
        const val EXTRA_EXTENSION = "extra_extension"
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Untitled"
        val content = intent.getStringExtra(EXTRA_CONTENT) ?: ""
        val extension = intent.getStringExtra(EXTRA_EXTENSION) ?: "html"

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = fileName,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "Back to Editor"
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface,
                                navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        WebViewContainer(
                            content = content,
                            extension = extension,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                BackHandler {
                    finish()
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewContainer(
    content: String,
    extension: String,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val webView = remember {
        WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            webViewClient = WebViewClient()
            webChromeClient = WebChromeClient()

            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
        }
    }

    val isDark = isSystemInDarkTheme()

    LaunchedEffect(content, extension, isDark) {
        val htmlToLoad = if (extension.lowercase() == "md") {
            generateMarkdownHtml(content, isDark)
        } else {
            content
        }

        webView.loadDataWithBaseURL(
            "https://localhost/", // Allows CORS and local origin requests safely
            htmlToLoad,
            "text/html",
            "UTF-8",
            null
        )
    }

    AndroidView(
        factory = { webView },
        modifier = modifier
    )
}

/**
 * Generates beautiful self-contained HTML that compiles Markdown using marked.js
 * and styles it with Github Markdown CSS.
 */
private fun generateMarkdownHtml(markdownText: String, isDark: Boolean): String {
    val base64Markdown = Base64.encodeToString(markdownText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val themeClass = if (isDark) "markdown-body dark" else "markdown-body"
    val backgroundColor = if (isDark) "#0d1117" else "#ffffff"
    val textColor = if (isDark) "#c9d1d9" else "#24292f"

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1.0">
          <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/github-markdown-css/5.2.0/github-markdown.min.css">
          <script src="https://cdn.jsdelivr.net/npm/marked/marked.min.js"></script>
          <style>
            body {
              box-sizing: border-box;
              min-width: 200px;
              max-width: 980px;
              margin: 0 auto;
              padding: 16px;
              background-color: $backgroundColor;
              color: $textColor;
            }
            .markdown-body {
              background-color: transparent !important;
              color: inherit !important;
            }
            /* Dark mode specific fine-tuning */
            .markdown-body.dark {
              --color-canvas-default: $backgroundColor;
              --color-fg-default: $textColor;
            }
          </style>
        </head>
        <body>
          <div id="content" class="$themeClass"></div>
          <script>
            try {
              var base64Data = "$base64Markdown";
              var decodedText = decodeURIComponent(escape(window.atob(base64Data)));
              document.getElementById('content').innerHTML = marked.parse(decodedText);
            } catch (e) {
              document.getElementById('content').innerText = "Failed to render Markdown: " + e.message;
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
