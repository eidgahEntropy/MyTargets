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

package de.dreier.mytargets.features.statistics

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.databinding.DataBindingUtil
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.base.activities.ChildActivityBase
import de.dreier.mytargets.databinding.ActivityArrowRankingDetailsBinding
import de.dreier.mytargets.features.scoreboard.EFileType
import de.dreier.mytargets.features.settings.ESettingsScreens
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.shared.models.Dimension
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.utils.ToolbarUtils
import de.dreier.mytargets.utils.Utils
import de.dreier.mytargets.utils.parcelableExtra
import de.dreier.mytargets.utils.print.CustomPrintDocumentAdapter
import de.dreier.mytargets.utils.print.DrawableToPdfWriter
import de.dreier.mytargets.utils.toUri
import timber.log.Timber
import java.io.File
import java.io.IOException

class DispersionPatternActivity : ChildActivityBase() {
    private lateinit var binding: ActivityArrowRankingDetailsBinding
    private var statistic: ArrowStatistic? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            ?.isAppearanceLightStatusBars = true
        binding = DataBindingUtil
            .setContentView(this, R.layout.activity_arrow_ranking_details)
        ToolbarUtils.applyWindowInsetsToScrollableContent(binding.dispersionView)
        binding.dispersionBackButton.setOnClickListener { navigationController.finish() }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(binding.dispersionBackButton) { view, insets ->
            val topInset = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.statusBars()
                        or androidx.core.view.WindowInsetsCompat.Type.displayCutout()
            ).top
            val lp = view.layoutParams as ViewGroup.MarginLayoutParams
            val density = view.resources.displayMetrics.density
            lp.topMargin = topInset + (16 * density).toInt()
            view.layoutParams = lp
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(binding.dispersionBackButton)

        val roundIds = intent.getLongArrayExtra(ROUND_IDS)
        val target = intent.parcelableExtra<Target>(intent, TARGET)
        if (roundIds == null || target == null) {
            Timber.w("Dispersion: missing intent extras (roundIds=$roundIds, target=$target)")
            showErrorState()
            return
        }

        val arrowName = intent.getStringExtra(ARROW_NAME)
        val arrowNumber = intent.getStringExtra(ARROW_NUMBER)
        val exportFileName = intent.getStringExtra(EXPORT_FILE_NAME)

        val db = ApplicationInstance.db
        val roundDAO = db.roundDAO()
        val endDAO = db.endDAO()

        // Stage 1: load rounds from DB
        val rounds = try {
            roundDAO.loadRoundsBatched(roundIds).also {
                Timber.d("Dispersion: loaded ${it.size} rounds for ${roundIds.size} IDs, target.id=${target.id} scoringStyle=${target.scoringStyleIndex}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Dispersion: DB round query failed, roundIds.size=${roundIds.size}, target.id=${target.id}")
            showErrorState()
            return
        }

        // Stage 2: collect shots
        val shots = try {
            if (arrowNumber != null) {
                rounds.flatMap { roundDAO.loadEnds(it.id) }
                    .flatMap { endDAO.loadShots(it.id) }
                    .filter { it.arrowNumber == arrowNumber }
            } else {
                rounds.flatMap { roundDAO.loadEnds(it.id) }
                    .filter { it.exact }
                    .flatMap { endDAO.loadShots(it.id) }
                    .filter { it.scoringRing != Shot.NOTHING_SELECTED }
            }.also {
                Timber.d("Dispersion: collected ${it.size} shots from ${rounds.size} rounds (arrowNumber=$arrowNumber)")
            }
        } catch (e: Exception) {
            Timber.e(e, "Dispersion: shot collection failed, rounds.size=${rounds.size}, target.id=${target.id}")
            showErrorState()
            return
        }

        // Stage 3: build ArrowStatistic (average + score computation)
        statistic = try {
            if (arrowNumber != null) {
                ArrowStatistic(target, shots).also {
                    it.arrowName = arrowName
                    it.arrowNumber = arrowNumber
                }
            } else {
                ArrowStatistic(target, shots).also {
                    it.exportFileName = exportFileName
                    it.arrowDiameter = Dimension(5f, Dimension.Unit.MILLIMETER)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Dispersion: ArrowStatistic construction failed, shots.size=${shots.size}, target.id=${target.id}, scoringStyle=${target.scoringStyleIndex}")
            showErrorState()
            return
        }

        updateActionBarTitle()
    }

    override fun onResume() {
        super.onResume()
        val stat = statistic ?: return
        try {
            val drawable = DispersionPatternUtils.targetFromArrowStatistics(stat)
            binding.dispersionView.setImageDrawable(drawable)
        } catch (e: Exception) {
            Timber.e(e, "Dispersion: render failed, shots.size=${stat.shots.size}, target.id=${stat.target.id}")
            showErrorState()
        }
    }

    private fun showErrorState() {
        statistic = null
        binding.dispersionView.visibility = View.GONE
        binding.dispersionErrorView.visibility = View.VISIBLE
        updateActionBarTitle()
        invalidateOptionsMenu()
    }

    private fun updateActionBarTitle() {
        val actionBar = supportActionBar
        if (actionBar == null) {
            Timber.w("Dispersion: supportActionBar is null; skipping action bar updates")
            return
        }
        actionBar.setDisplayHomeAsUpEnabled(true)
        val stat = statistic
        if (stat?.arrowName != null) {
            actionBar.title = getString(R.string.arrow_number_x, stat.arrowNumber)
            actionBar.subtitle = stat.arrowName
        } else {
            actionBar.setTitle(R.string.dispersion_pattern)
            actionBar.subtitle = null
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_scoreboard, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val hasData = statistic != null
        menu.findItem(R.id.action_share)?.isVisible = hasData
        menu.findItem(R.id.action_print)?.isVisible = hasData && Utils.isKitKat
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> shareImage()
            R.id.action_print -> if (Utils.isKitKat) print()
            R.id.action_settings -> navigationController.navigateToSettings(ESettingsScreens.STATISTICS)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun shareImage() {
        val stat = statistic ?: return
        Thread {
            try {
                val fileType = SettingsManager.statisticsDispersionPatternFileType
                val f = File(cacheDir, getDefaultFileName(stat, fileType))
                if (fileType === EFileType.PDF && Utils.isKitKat) {
                    DispersionPatternUtils.generatePdf(f, stat)
                } else {
                    DispersionPatternUtils.createDispersionPatternImageFile(1200, f, stat)
                }

                val shareIntent = Intent(Intent.ACTION_SEND)
                shareIntent.type = fileType.mimeType
                shareIntent.putExtra(Intent.EXTRA_STREAM, f.toUri(this@DispersionPatternActivity))
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
            } catch (e: IOException) {
                Timber.e(e, "Dispersion: share failed")
                Snackbar.make(binding.root, R.string.sharing_failed, Snackbar.LENGTH_SHORT).show()
            }
        }.start()
    }

    @RequiresApi(Build.VERSION_CODES.KITKAT)
    private fun print() {
        val stat = statistic ?: return
        val drawable = DispersionPatternUtils.targetFromArrowStatistics(stat)
        val fileName = getDefaultFileName(stat, EFileType.PDF)
        val pda = CustomPrintDocumentAdapter(DrawableToPdfWriter(drawable), fileName)

        val printManager = getSystemService<PrintManager>()!!
        printManager.print("Dispersion Pattern", pda, PrintAttributes.Builder().build())
    }

    private fun getDefaultFileName(stat: ArrowStatistic, extension: EFileType): String {
        var name = if (stat.arrowName != null) {
            stat.arrowName!! + "-" + getString(R.string.arrow_number_x, stat.arrowNumber)
        } else {
            stat.exportFileName
        }
        if (!name.isNullOrEmpty()) {
            name = "-$name"
        }
        return getString(R.string.dispersion_pattern) + name + "." + extension.name.lowercase()
    }

    companion object {
        const val ROUND_IDS = "round_ids"
        const val TARGET = "target"
        const val ARROW_NAME = "arrow_name"
        const val ARROW_NUMBER = "arrow_number"
        const val EXPORT_FILE_NAME = "export_file_name"
    }
}
