/*
 * Copyright (C) 2018 Florian Dreier
 *
 * This file is part of MyTargets.
 *
 * MyTargets is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 *
 * MyTargets is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 */

package de.dreier.mytargets.features.help

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.CallSuper
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import de.dreier.mytargets.R
import de.dreier.mytargets.base.navigation.NavigationController
import de.dreier.mytargets.databinding.FragmentWebBinding
import de.dreier.mytargets.features.help.licences.LicencesActivity
import de.dreier.mytargets.utils.ToolbarUtils
import timber.log.Timber
import java.io.IOException

/**
 * Shows all rounds of one training.
 */
class HelpFragment : Fragment() {

    private lateinit var navigationController: NavigationController
    private lateinit var binding: FragmentWebBinding

    private val helpHtmlPage: String
        get() {
            var prompt = ""
            try {
                val inputStream = resources.openRawResource(R.raw.help)
                val buffer = ByteArray(inputStream.available())
                inputStream.read(buffer)
                prompt = String(buffer)
                inputStream.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }

            return prompt
        }

    private fun addBottomSpacerToHtml(html: String): String {
        val spacer = "<div style=\"height: 140px;\"></div>"
        return if (html.contains("</body>", ignoreCase = true)) {
            html.replace("</body>", "$spacer</body>", ignoreCase = true)
        } else {
            html + spacer
        }
    }

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_web, container, false)

        try {
            val webView = WebView(requireContext())
            webView.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            binding.webViewContainer.addView(webView)

            val prompt = addBottomSpacerToHtml(helpHtmlPage)
            webView.loadDataWithBaseURL("file:///android_asset/", prompt, "text/html", "utf-8", "")
            webView.isHorizontalScrollBarEnabled = false

            val originalPaddingLeft = webView.paddingLeft
            val originalPaddingTop = webView.paddingTop
            val originalPaddingRight = webView.paddingRight
            val originalPaddingBottom = webView.paddingBottom
            ViewCompat.setOnApplyWindowInsetsListener(webView) { view, windowInsets ->
                val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
                val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
                val safeBottomInset = maxOf(navInsets.bottom, imeInsets.bottom)
                val extraBottomPx = (40 * view.resources.displayMetrics.density).toInt()
                view.setPadding(
                    originalPaddingLeft,
                    originalPaddingTop,
                    originalPaddingRight,
                    originalPaddingBottom + safeBottomInset + extraBottomPx
                )
                webView.clipToPadding = false
                windowInsets
            }
            ViewCompat.requestApplyInsets(webView)
        } catch (e: Exception) {
            Timber.e(e, "WebView initialization failed")
            binding.errorText.visibility = View.VISIBLE
        }
        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        navigationController = NavigationController(this)
        ToolbarUtils.showHomeAsUp(this)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.help, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_open_source_licences -> {
                startActivity(Intent(context, LicencesActivity::class.java))
                true
            }

            R.id.action_about -> {
                navigationController.navigateToAbout()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}
