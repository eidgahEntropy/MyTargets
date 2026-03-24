package de.dreier.mytargets.features.main

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import de.dreier.mytargets.R
import de.dreier.mytargets.base.fragments.FragmentBase
import timber.log.Timber

class ShopFragment : FragmentBase() {
    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_shop, container, false)
        val webViewContainer = view.findViewById<FrameLayout>(R.id.webViewContainer)
        val errorText = view.findViewById<TextView>(R.id.errorText)

        try {
            val webView = WebView(requireContext())
            webView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            webViewContainer.addView(webView)

            webView.settings.javaScriptEnabled = true
            webView.webViewClient = WebViewClient()
            webView.loadUrl("https://mantisarchery.com?utm_source=android&utm_medium=app&utm_app=MyTarget")
            webView.setOnKeyListener { _, keyCode, _ ->
                if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
                    webView.goBack()
                    webView.scrollTo(0, 0)
                }
                true
            }
        } catch (e: Exception) {
            Timber.e(e, "WebView initialization failed")
            errorText.visibility = View.VISIBLE
        }
        return view
    }
}
