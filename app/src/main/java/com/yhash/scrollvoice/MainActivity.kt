package com.yhash.scrollvoice // Keep your actual package name here at the top

import android.content.Context
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Enable remote debugging for WebViews
        android.webkit.WebView.setWebContentsDebuggingEnabled(true)

        webView = findViewById(R.id.webView)

        // Required WebSettings for Vite React apps
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            // Allow local assets to bypass CORS restrictions
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }   

        // Attach Android Bridge
        webView.addJavascriptInterface(WebAppInterface(this), "Android")

        // Load local HTML asset
        webView.loadUrl("file:///android_asset/web/index.html")
    }

    // Bridge methods exposed to JavaScript
    inner class WebAppInterface(private val context: Context) {

        @JavascriptInterface
        fun setServiceActive(active: Boolean) {
            if (active) {
                // TODO: Add code to start your background listening service
            } else {
                // TODO: Add code to stop your background listening service
            }
        }

        @JavascriptInterface
        fun setMode(mode: String) {
            // TODO: Add code to update your active mode (Voice, Clap, Camera)
        }
    }
}