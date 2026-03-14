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

import android.annotation.SuppressLint
import android.app.LoaderManager
import android.content.AsyncTaskLoader
import android.content.Intent
import android.content.Loader
import android.net.Uri
import android.os.AsyncTask
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.core.view.GravityCompat.END
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentStatePagerAdapter
import com.afollestad.materialdialogs.MaterialDialog
import com.evernote.android.state.State
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.base.activities.ChildActivityBase
import de.dreier.mytargets.databinding.ActivityStatisticsBinding
import de.dreier.mytargets.shared.models.Target
import de.dreier.mytargets.shared.models.db.Round
import de.dreier.mytargets.shared.models.db.Training
import de.dreier.mytargets.utils.ToolbarUtils
import de.dreier.mytargets.utils.toSparseArray
import de.dreier.mytargets.utils.toUri
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StatisticsActivity : ChildActivityBase(),
    LoaderManager.LoaderCallbacks<List<Pair<Training, Round>>> {

    private lateinit var binding: ActivityStatisticsBinding
    private var rounds: List<Pair<Training, Round>>? = null
    private var filteredRounds: List<Pair<Target, List<Round>>>? = null

    @State
    var distanceTags: HashSet<String>? = null

    @State
    var diameterTags: HashSet<String>? = null

    @State
    var arrowTags: HashSet<Long?>? = null

    @State
    var bowTags: HashSet<Long?>? = null

    private val trainingDAO by lazy(LazyThreadSafetyMode.NONE) { ApplicationInstance.db.trainingDAO() }
    private val roundDAO by lazy(LazyThreadSafetyMode.NONE) { ApplicationInstance.db.roundDAO() }
    private val bowDAO by lazy(LazyThreadSafetyMode.NONE) { ApplicationInstance.db.bowDAO() }
    private val arrowDAO by lazy(LazyThreadSafetyMode.NONE) { ApplicationInstance.db.arrowDAO() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_statistics)
        setSupportActionBar(binding.toolbar)
        ToolbarUtils.applyWindowInsets(binding.toolbar)
        ToolbarUtils.applyWindowInsetsToBottom(binding.filterView)

        binding.reset.setOnClickListener { resetFilter() }

        binding.progressBar.show()

        ToolbarUtils.showHomeAsUp(this)

        loaderManager.initLoader(0, intent.extras, this).forceLoad()
    }

    @SuppressLint("StaticFieldLeak")
    override fun onCreateLoader(i: Int, bundle: Bundle): Loader<List<Pair<Training, Round>>> {
        return object : AsyncTaskLoader<List<Pair<Training, Round>>>(this) {
            override fun loadInBackground(): List<Pair<Training, Round>> {
                // Resolve round IDs here (background thread) to avoid main-thread DB access,
                // and use loadRoundsBatched to avoid SQLite's 999-variable limit.
                val roundIds = if (intent.hasExtra(TRAINING_ID)) {
                    roundDAO.loadRounds(intent.getLongExtra(TRAINING_ID, 0))
                        .map { it.id }.toLongArray()
                } else {
                    intent.getLongArrayExtra(ROUND_IDS) ?: LongArray(0)
                }
                if (roundIds.isEmpty()) {
                    return emptyList()
                }
                val rounds = roundDAO.loadRoundsBatched(roundIds)
                val trainingsMap = rounds.mapNotNull { (_, trainingId) -> trainingId }
                    .distinct()
                    .map { id -> Pair(id, trainingDAO.loadTraining(id)) }
                    .toSparseArray()
                return rounds.mapNotNull { round ->
                    val trainingId = round.trainingId ?: return@mapNotNull null
                    val training = trainingsMap.get(trainingId) ?: return@mapNotNull null
                    Pair(training, round)
                }
            }
        }
    }

    override fun onLoadFinished(
        loader: Loader<List<Pair<Training, Round>>>,
        data: List<Pair<Training, Round>>
    ) {
        rounds = data
        binding.progressBar.hide()
        binding.distanceTags.tags = getDistanceTags()
        binding.distanceTags.setOnTagClickListener { applyFilter() }
        binding.diameterTags.tags = getDiameterTags()
        binding.diameterTags.setOnTagClickListener { applyFilter() }
        binding.arrowTags.tags = getArrowTags()
        binding.arrowTags.setOnTagClickListener { applyFilter() }
        binding.bowTags.tags = getBowTags()
        binding.bowTags.setOnTagClickListener { applyFilter() }

        if (distanceTags != null && diameterTags != null && arrowTags != null && bowTags != null) {
            restoreCheckedStates()
        }

        applyFilter()
        invalidateOptionsMenu()
    }

    private fun restoreCheckedStates() {
        val dt = distanceTags ?: return
        val dmt = diameterTags ?: return
        val at = arrowTags ?: return
        val bt = bowTags ?: return
        binding.distanceTags.tags.forEach { it.isChecked = dt.contains(it.text) }
        binding.diameterTags.tags.forEach { it.isChecked = dmt.contains(it.text) }
        binding.arrowTags.tags.forEach { it.isChecked = at.contains(it.id) }
        binding.bowTags.tags.forEach { it.isChecked = bt.contains(it.id) }
        binding.distanceTags.notifyTagsListChanged()
        binding.diameterTags.notifyTagsListChanged()
        binding.arrowTags.notifyTagsListChanged()
        binding.bowTags.notifyTagsListChanged()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.export_filter, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        val filter = menu.findItem(R.id.action_filter)
        val export = menu.findItem(R.id.action_export)
        // only show filter if we have at least one category to filter by
        val filterAvailable = (binding.distanceTags.tags.size > 1
                || binding.diameterTags.tags.size > 1
                || binding.bowTags.tags.size > 1
                || binding.arrowTags.tags.size > 1)
        filter.isVisible = rounds != null && filterAvailable
        export.isVisible = rounds != null
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_export -> {
                export()
                true
            }

            R.id.action_filter -> {
                if (!binding.drawerLayout.isDrawerOpen(END)) {
                    binding.drawerLayout.openDrawer(END)
                } else {
                    binding.drawerLayout.closeDrawer(END)
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun resetFilter() {
        binding.distanceTags.tags.forEach { it.isChecked = true }
        binding.diameterTags.tags.forEach { it.isChecked = true }
        binding.arrowTags.tags.forEach { it.isChecked = true }
        binding.bowTags.tags.forEach { it.isChecked = true }
        binding.distanceTags.tags = binding.distanceTags.tags
        binding.diameterTags.tags = binding.diameterTags.tags
        binding.arrowTags.tags = binding.arrowTags.tags
        binding.bowTags.tags = binding.bowTags.tags
        applyFilter()
    }

    private fun applyFilter() {
        distanceTags = binding.distanceTags.checkedTags.map { it.text }.toHashSet()
        diameterTags = binding.diameterTags.checkedTags.map { it.text }.toHashSet()
        arrowTags = binding.arrowTags.checkedTags.map { it.id }.toHashSet()
        bowTags = binding.bowTags.checkedTags.map { it.id }.toHashSet()
        val currentRounds = rounds ?: return
        val currentDistanceTags = distanceTags ?: return
        val currentDiameterTags = diameterTags ?: return
        val currentArrowTags = arrowTags ?: return
        val currentBowTags = bowTags ?: return
        filteredRounds = currentRounds
            .filter { (training, round) ->
                currentDistanceTags.contains(round.distance.toString())
                        && currentDiameterTags.contains(round.target.diameter.toString())
                        && currentArrowTags.contains(training.arrowId)
                        && currentBowTags.contains(training.bowId)
            }
            .map { it.second }
            .groupBy { value -> Pair(value.target.id, value.target.getScoringStyle()) }
            .map { value1 -> Pair(value1.value[0].target, value1.value) }
            .sortedByDescending { it.second.size }
        val animate = binding.viewPager.adapter == null
        val environment =
            if (trainingDAO.loadTrainings().first().environment.indoor) "Indoor" else "Outdoor"
        val currentFilteredRounds = filteredRounds ?: return
        val adapter = StatisticsPagerAdapter(
            supportFragmentManager, currentFilteredRounds, animate, environment
        )
        binding.viewPager.adapter = adapter
    }

    private fun getBowTags(): List<Tag> {
        val currentRounds = rounds ?: return emptyList()
        return currentRounds
            .map { it.first.bowId }
            .distinct()
            .map { bid ->
                if (bid != null) {
                    val bow = bowDAO.loadBowOrNull(bid) ?: return@map Tag(bid, "Deleted " + bid)
                    Tag(bow.id, bow.name, bow.thumbnail, true)
                } else {
                    Tag(null, getString(R.string.unknown))
                }
            }
    }

    private fun getArrowTags(): List<Tag> {
        val currentRounds = rounds ?: return emptyList()
        return currentRounds
            .map { it.first.arrowId }
            .distinct()
            .map { aid ->
                if (aid != null) {
                    val arrow =
                        arrowDAO.loadArrowOrNull(aid) ?: return@map Tag(aid, "Deleted " + aid)
                    Tag(arrow.id, arrow.name, arrow.thumbnail, true)
                } else {
                    Tag(null, getString(R.string.unknown))
                }
            }
    }

    private fun getDistanceTags(): List<Tag> {
        val currentRounds = rounds ?: return emptyList()
        return currentRounds
            .map { it.second.distance }
            .distinct()
            .sorted()
            .map { d -> Tag(d.id, d.toString()) }
    }

    private fun getDiameterTags(): List<Tag> {
        val currentRounds = rounds ?: return emptyList()
        return currentRounds
            .map { it.second.target.diameter }
            .distinct()
            .sorted()
            .map { Tag(it.id, it.toString()) }
    }

    override fun onLoaderReset(loader: Loader<List<Pair<Training, Round>>>) {

    }

    @SuppressLint("StaticFieldLeak")
    internal fun export() {
        val progress = MaterialDialog.Builder(this)
            .content(R.string.exporting)
            .progress(true, 0)
            .show()
        object : AsyncTask<Void, Void, Uri>() {

            override fun doInBackground(vararg params: Void): Uri? {
                return try {
                    val f = File(cacheDir, exportFileName)
                    CsvExporter(applicationContext, ApplicationInstance.db)
                        .exportAll(f, (filteredRounds ?: return null).flatMap { it.second }.map { it.id })
                    f.toUri(this@StatisticsActivity)
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }
            }

            override fun onPostExecute(uri: Uri?) {
                super.onPostExecute(uri)
                progress.dismiss()
                if (uri != null) {
                    val email = Intent(Intent.ACTION_SEND)
                    email.putExtra(Intent.EXTRA_STREAM, uri)
                    email.type = "text/csv"
                    startActivity(Intent.createChooser(email, getString(R.string.send_exported)))
                } else {
                    Snackbar.make(
                        binding.root, R.string.exporting_failed,
                        Snackbar.LENGTH_LONG
                    ).show()
                }
            }
        }.execute()
    }

    private val exportFileName: String
        get() {
            val format = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US)
            return "MyTargets_exported_data_" + format.format(Date()) + ".csv"
        }

    inner class StatisticsPagerAdapter internal constructor(
        fm: FragmentManager,
        private val targets: List<Pair<Target, List<Round>>>,
        private val animate: Boolean,
        private val environment: String
    ) : FragmentStatePagerAdapter(fm) {

        override fun getItem(position: Int): Fragment {
            val item = targets[position]
            val roundIds = item.second.map { it.id }
            return StatisticsFragment.newInstance(roundIds, item.first, animate)
        }

        override fun getCount(): Int {
            return targets.size
        }

        override fun getPageTitle(position: Int): CharSequence {
            return targets[position].first.toString() + " - " + environment
        }
    }

    companion object {
        const val ROUND_IDS = "round_ids"
        const val TRAINING_ID = "training_id"
    }
}
