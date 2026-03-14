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

package de.dreier.mytargets.features.training.input

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.databinding.DataBindingUtil
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import androidx.loader.app.LoaderManager
import androidx.loader.content.AsyncTaskLoader
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.loader.content.Loader
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.text.InputType
import android.transition.Transition
import android.view.Menu
import android.view.MenuItem
import android.view.View.GONE
import android.view.View.VISIBLE
import com.afollestad.materialdialogs.MaterialDialog
import com.evernote.android.state.State
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.base.activities.ChildActivityBase
import de.dreier.mytargets.base.db.RoundRepository
import de.dreier.mytargets.base.db.TrainingRepository
import de.dreier.mytargets.base.db.dao.ArrowDAO
import de.dreier.mytargets.base.db.dao.BowDAO
import de.dreier.mytargets.base.db.dao.StandardRoundDAO
import de.dreier.mytargets.base.gallery.GalleryActivity
import de.dreier.mytargets.databinding.ActivityInputBinding
import de.dreier.mytargets.features.settings.ESettingsScreens
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.shared.models.db.End
import de.dreier.mytargets.shared.models.db.Round
import de.dreier.mytargets.shared.models.db.Shot
import de.dreier.mytargets.shared.models.sum
import de.dreier.mytargets.shared.views.TargetViewBase
import de.dreier.mytargets.shared.views.TargetViewBase.EInputMethod
import de.dreier.mytargets.shared.wearable.WearableClientBase.Companion.BROADCAST_TIMER_SETTINGS_FROM_REMOTE
import de.dreier.mytargets.utils.*
import de.dreier.mytargets.utils.MobileWearableClient.Companion.BROADCAST_UPDATE_TRAINING_FROM_REMOTE
import de.dreier.mytargets.utils.Utils.getCurrentLocale
import de.dreier.mytargets.utils.transitions.FabTransform
import de.dreier.mytargets.utils.transitions.TransitionAdapter
import timber.log.Timber
import org.threeten.bp.LocalTime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class InputActivity : ChildActivityBase(), TargetViewBase.OnEndFinishedListener,
    TargetView.OnEndUpdatedListener, LoaderManager.LoaderCallbacks<LoaderResult> {

    @State
    var data: LoaderResult? = null

    private lateinit var binding: ActivityInputBinding
    private var transitionFinished = true
    private var summaryShowScope = ETrainingScope.END
    private var targetView: TargetView? = null
    private val persistenceExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val saveLock = Any()
    private var pendingSave: PendingEndSave? = null
    private var saveWorkerRunning = false

    private val database by lazy(LazyThreadSafetyMode.NONE) { ApplicationInstance.db }
    private val trainingDAO by lazy(LazyThreadSafetyMode.NONE) { database.trainingDAO() }
    private val roundDAO by lazy(LazyThreadSafetyMode.NONE) { database.roundDAO() }
    private val endDAO by lazy(LazyThreadSafetyMode.NONE) { database.endDAO() }
    private val bowDAO by lazy(LazyThreadSafetyMode.NONE) { database.bowDAO() }
    private val arrowDAO by lazy(LazyThreadSafetyMode.NONE) { database.arrowDAO() }
    private val standardRoundDAO by lazy(LazyThreadSafetyMode.NONE) { database.standardRoundDAO() }
    private val roundRepository by lazy(LazyThreadSafetyMode.NONE) { RoundRepository(database) }
    private val trainingRepository by lazy(LazyThreadSafetyMode.NONE) {
        TrainingRepository(
            database,
            trainingDAO,
            roundDAO,
            roundRepository,
            database.signatureDAO()
        )
    }

    private val updateReceiver = object : MobileWearableClient.EndUpdateReceiver() {

        override fun onUpdate(trainingId: Long, roundId: Long, end: End) {
            val extras = intent.extras ?: return
            extras.putLong(TRAINING_ID, trainingId)
            extras.putLong(ROUND_ID, roundId)
            extras.putInt(END_INDEX, end.index)
            supportLoaderManager.restartLoader(0, extras, this@InputActivity).forceLoad()
        }
    }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            invalidateOptionsMenu()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_input)
        setSupportActionBar(binding.toolbar)
        ToolbarUtils.applyWindowInsets(binding.toolbar)
        ToolbarUtils.applyWindowInsetsToBottom(binding.butBar)
        ToolbarUtils.showHomeAsUp(this)

        Utils.setupFabTransform(this, binding.root)

        if (Utils.isLollipop) {
            setupTransitionListener()
        }

        updateSummaryVisibility()

        if (data == null) {
            supportLoaderManager.initLoader(0, intent.extras, this).forceLoad()
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(
            updateReceiver,
            IntentFilter(BROADCAST_UPDATE_TRAINING_FROM_REMOTE)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            timerReceiver,
            IntentFilter(BROADCAST_TIMER_SETTINGS_FROM_REMOTE)
        )
    }

    override fun onResume() {
        super.onResume()
        if (data != null) {
            onDataLoadFinished()
            updateEnd()
        }
        Utils.setShowWhenLocked(this, SettingsManager.inputKeepAboveLockscreen)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GALLERY_REQUEST_CODE && resultCode == Activity.RESULT_OK && data != null) {
            val d = this.data ?: return
            val imageList = GalleryActivity.getResult(data)
            val currentEnd = d.currentEnd
            currentEnd.images = imageList.toEndImageList()
            for (image in imageList.removedImages) {
                File(filesDir, image).delete()
            }
            endDAO.replaceImages(currentEnd.end, currentEnd.images)
            updateEnd()
            invalidateOptionsMenu()
        }
    }

    private fun saveCurrentEnd() {
        val d = data ?: return
        val currentEnd = d.currentEnd
        if (currentEnd.end.saveTime == null) {
            currentEnd.end.saveTime = LocalTime.now()
        }
        currentEnd.end.score = d.currentRound.round.target.getReachedScore(currentEnd.shots)
        enqueueEndSave(currentEnd.end, currentEnd.shots)
    }

    private fun enqueueEndSave(end: End, shots: List<Shot>) {
        val save = PendingEndSave(
            end = end.copy(score = end.score.copy()),
            shots = shots.map { it.copy() }
        )
        synchronized(saveLock) {
            pendingSave = save
            if (!saveWorkerRunning) {
                saveWorkerRunning = true
                persistenceExecutor.execute { drainPendingSaves() }
            }
        }
    }

    private fun drainPendingSaves() {
        while (true) {
            val save = synchronized(saveLock) {
                val next = pendingSave
                pendingSave = null
                if (next == null) {
                    saveWorkerRunning = false
                }
                next
            } ?: return
            try {
                endDAO.updateEnd(save.end)
                endDAO.updateShots(save.shots)
            } catch (e: Exception) {
                Timber.e(e, "Failed to persist end ${save.end.id}")
            }
        }
    }

    private fun flushPendingSaves(timeoutMs: Long = 1200) {
        val latch = CountDownLatch(1)
        try {
            persistenceExecutor.execute { latch.countDown() }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Timber.w("Timed out waiting for end persistence flush")
            }
        } catch (e: RejectedExecutionException) {
            Timber.w(e, "Persistence executor rejected flush request")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Timber.w(e, "Interrupted while waiting for end persistence flush")
        }
    }

    override fun onStop() {
        flushPendingSaves()
        super.onStop()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(updateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(timerReceiver)
        flushPendingSaves(timeoutMs = 500)
        persistenceExecutor.shutdown()
        super.onDestroy()
    }

    private fun updateSummaryVisibility() {
        val (showEnd, showRound, showTraining, showAverage, averageScope) = SettingsManager.inputSummaryConfiguration
        binding.endSummary.visibility = if (showEnd) VISIBLE else GONE
        binding.roundSummary.visibility = if (showRound) VISIBLE else GONE
        binding.trainingSummary.visibility = if (showTraining) VISIBLE else GONE
        binding.averageSummary.visibility = if (showAverage) VISIBLE else GONE
        summaryShowScope = averageScope
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun setupTransitionListener() {
        val sharedElementEnterTransition = window.sharedElementEnterTransition
        if (sharedElementEnterTransition != null && sharedElementEnterTransition is FabTransform) {
            transitionFinished = false
            window.sharedElementEnterTransition.addListener(object : TransitionAdapter() {
                override fun onTransitionEnd(transition: Transition) {
                    transitionFinished = true
                    window.sharedElementEnterTransition.removeListener(this)
                }
            })
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.input_end, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val d = data
        val timer = menu.findItem(R.id.action_timer)
        val newRound = menu.findItem(R.id.action_new_round)
        val takePicture = menu.findItem(R.id.action_photo)
        if (targetView == null || d == null || d.ends.size == 0) {
            takePicture.isVisible = false
            timer.isVisible = false
            newRound.isVisible = false
        } else {
            takePicture.isVisible = Utils.hasCameraHardware(this)
            timer.setIcon(
                if (SettingsManager.timerEnabled)
                    R.drawable.ic_timer_off_white_24dp
                else
                    R.drawable.ic_timer_white_24dp
            )
            timer.isVisible = true
            timer.isChecked = SettingsManager.timerEnabled
            newRound.isVisible = d.training.training.standardRoundId == null
            takePicture.isVisible = Utils.hasCameraHardware(this)
            takePicture.setIcon(
                if (d.currentEnd.images.isEmpty())
                    R.drawable.ic_photo_camera_white_24dp
                else
                    R.drawable.ic_image_white_24dp
            )
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_photo -> {
                val d = data ?: return true
                val imageList = ImageList(d.currentEnd.images)
                val title = getString(R.string.end_n, d.endIndex + 1)
                navigationController.navigateToGallery(imageList, title, GALLERY_REQUEST_CODE)
            }
            R.id.action_comment -> {
                val d = data ?: return true
                MaterialDialog.Builder(this)
                    .title(R.string.comment)
                    .inputType(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                    .input("", d.currentEnd.end.comment) { _, input ->
                        d.currentEnd.end.comment = input.toString()
                        saveCurrentEnd()
                    }
                    .negativeText(android.R.string.cancel)
                    .show()
            }
            R.id.action_timer -> {
                val timerEnabled = !SettingsManager.timerEnabled
                SettingsManager.timerEnabled = timerEnabled
                ApplicationInstance.wearableClient.sendTimerSettingsFromLocal(SettingsManager.timerSettings)
                openTimer()
                item.isChecked = timerEnabled
                invalidateOptionsMenu()
            }
            R.id.action_settings -> navigationController.navigateToSettings(ESettingsScreens.INPUT)
            R.id.action_new_round -> {
                val d = data ?: return true
                navigationController.navigateToCreateRound(trainingId = d.training.training.id)
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onCreateLoader(id: Int, args: Bundle?): Loader<LoaderResult> {
        if (args == null) {
            throw IllegalArgumentException("Bundle expected")
        }
        val trainingId = args.getLong(TRAINING_ID)
        val roundId = args.getLong(ROUND_ID)
        val endIndex = args.getInt(END_INDEX)
        return UITaskAsyncTaskLoader(
            this,
            trainingId,
            roundId,
            endIndex,
            trainingRepository,
            standardRoundDAO,
            arrowDAO,
            bowDAO
        )
    }

    override fun onLoadFinished(loader: Loader<LoaderResult>, data: LoaderResult) {
        this.data = data
        onDataLoadFinished()
        showEnd(data.endIndex)
    }

    private fun onDataLoadFinished() {
        val d = data ?: return
        title = d.training.training.title
        if (!binding.targetViewStub.isInflated) {
            binding.targetViewStub.viewStub?.inflate()
        }
        targetView = binding.targetViewStub.binding?.root as TargetView
        targetView?.initWithTarget(d.currentRound.round.target)
        targetView?.setArrow(
            d.arrowDiameter, d.training.training.arrowNumbering, d
                .maxArrowNumber
        )
        targetView?.setOnTargetSetListener(this@InputActivity)
        targetView?.setUpdateListener(this@InputActivity)
        targetView?.reloadSettings()
        targetView?.setAggregationStrategy(SettingsManager.aggregationStrategy)
        targetView?.inputMethod = SettingsManager.inputMethod
        updateOldShoots()
    }

    override fun onLoaderReset(loader: Loader<LoaderResult>) {

    }

    private fun showEnd(endIndex: Int) {
        // Create a new end
        val d = data ?: return
        d.setAdjustEndIndex(endIndex)
        if (endIndex >= d.ends.size) {
            val end = d.currentRound.addEnd()
            end.end.exact = SettingsManager.inputMethod === EInputMethod.PLOTTING
            updateOldShoots()
        }

        // Open timer if end has not been saved yet
        openTimer()
        updateEnd()
        invalidateOptionsMenu()
    }

    private fun updateOldShoots() {
        val d = data ?: return
        val currentEnd = d.currentEnd
        val currentRoundId = d.currentRound.round.id
        val currentEndId = currentEnd.end.id
        val shotShowScope = SettingsManager.showMode
        val shots = d.training.rounds
            .filter { r -> shouldShowRound(r.round, shotShowScope, currentRoundId) }
            .flatMap { r -> r.ends }
            .filter { end -> shouldShowEnd(end.end, currentEndId) }
            .flatMap { (_, shots) -> shots }
        targetView?.setTransparentShots(shots)
    }

    private fun openTimer() {
        val d = data ?: return
        if (d.currentEnd.isEmpty && SettingsManager.timerEnabled) {
            if (transitionFinished) {
                navigationController.navigateToTimer(true)
            } else if (Utils.isLollipop) {
                startTimerDelayed()
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private fun startTimerDelayed() {
        window.sharedElementEnterTransition.addListener(object : TransitionAdapter() {
            override fun onTransitionEnd(transition: Transition) {
                navigationController.navigateToTimer(true)
                window.sharedElementEnterTransition.removeListener(this)
            }
        })
    }

    private fun updateEnd() {
        val d = data ?: return
        targetView?.replaceWithEnd(d.currentEnd.shots, d.currentEnd.end.exact)
        val totalEnds = d.currentRound.round.maxEndCount ?: d.ends.size
        binding.endTitle.text = getString(R.string.end_x_of_y, d.endIndex + 1, totalEnds)
        binding.roundTitle.text = getString(
            R.string.round_x_of_y,
            d.currentRound.round.index + 1,
            d.training.rounds.size
        )
        updateNavigationButtons()
        updateWearNotification()
    }

    private fun updateWearNotification() {
        val d = data ?: return
        ApplicationInstance.wearableClient.sendUpdateTrainingFromLocalBroadcast(d.training)
    }

    private fun updateNavigationButtons() {
        updatePreviousButton()
        updateNextButton()
    }

    private fun updatePreviousButton() {
        val d = data ?: return
        val isFirstEnd = d.endIndex == 0
        val isFirstRound = d.roundIndex == 0
        val showPreviousRound = isFirstEnd && !isFirstRound
        val isEnabled = !isFirstEnd || !isFirstRound
        val color: Int
        if (showPreviousRound) {
            val round = d.training.rounds[d.roundIndex - 1]
            binding.prev.setOnClickListener { openRound(round.round, round.ends.size - 1) }
            binding.prev.setText(R.string.previous_round)
            color = ContextCompat.getColor(this, R.color.colorPrimary)
        } else {
            binding.prev.setOnClickListener { showEnd(d.endIndex - 1) }
            binding.prev.setText(R.string.prev)
            color = Color.BLACK
        }
        binding.prev.setTextColor(Utils.argb(if (isEnabled) 0xFF else 0x42, color))
        binding.prev.isEnabled = isEnabled
    }

    private fun updateNextButton() {
        val d = data ?: return
        val isLastEnd =
                d.currentRound.round.maxEndCount != null &&
                d.endIndex + 1 == d.currentRound.round.maxEndCount
        val hasOneMoreRound = d.roundIndex + 1 < d.training.rounds.size
        val showNextRound = isLastEnd && hasOneMoreRound
        val isEnabled = !isLastEnd || hasOneMoreRound
        val color: Int
        if (showNextRound) {
            val round = d.training.rounds[d.roundIndex + 1].round
            binding.next.setOnClickListener { openRound(round, 0) }
            binding.next.setText(R.string.next_round)
            color = ContextCompat.getColor(this, R.color.colorPrimary)
        } else {
            binding.next.setOnClickListener { showEnd(d.endIndex + 1) }
            binding.next.setText(R.string.next)
            color = Color.BLACK
        }
        binding.next.setTextColor(Utils.argb(if (isEnabled) 0xFF else 0x42, color))
        binding.next.isEnabled = isEnabled
    }

    private fun openRound(round: Round, endIndex: Int) {
        finish()
        navigationController.navigateToRound(round)
            .noAnimation()
            .start()
        navigationController.navigateToEditEnd(round, endIndex)
            .start()
    }

    override fun onEndUpdated(shots: List<Shot>) {
        val d = data ?: return
        d.currentEnd.shots = shots.toMutableList()
        saveCurrentEnd()

        // Set current end score
        val reachedEndScore = d.currentRound.round.target
            .getReachedScore(d.currentEnd.shots)
        binding.endScore.text = reachedEndScore.toString()

        // Set current round score
        val reachedRoundScore = d.ends
            .map { end -> d.currentRound.round.target.getReachedScore(end.shots) }
            .sum()
        binding.roundScore.text = reachedRoundScore.toString()

        // Set current training score
        val reachedTrainingScore = d.training.rounds
            .flatMap { r -> r.ends.map { end -> r.round.target.getReachedScore(end.shots) } }
            .sum()
        binding.trainingScore.text = reachedTrainingScore.toString()

        when (summaryShowScope) {
            ETrainingScope.END -> binding.averageScore.text =
                    reachedEndScore.getShotAverageFormatted(getCurrentLocale(this))
            ETrainingScope.ROUND -> binding.averageScore.text =
                    reachedRoundScore.getShotAverageFormatted(getCurrentLocale(this))
            ETrainingScope.TRAINING -> binding.averageScore.text = reachedTrainingScore
                .getShotAverageFormatted(getCurrentLocale(this))
        }
    }

    override fun onEndFinished(shotList: List<Shot>) {
        val d = data ?: return
        d.currentEnd.shots = shotList.toMutableList()
        d.currentEnd.end.exact = targetView?.inputMode === EInputMethod.PLOTTING
        saveCurrentEnd()

        updateWearNotification()
        updateNavigationButtons()
        invalidateOptionsMenu()
    }

    private class UITaskAsyncTaskLoader(
        context: Context,
        private val trainingId: Long,
        private val roundId: Long,
        private val endIndex: Int,
        val trainingRepository: TrainingRepository,
        val standardRoundDAO: StandardRoundDAO,
        val arrowDAO: ArrowDAO,
        val bowDAO: BowDAO
    ) : AsyncTaskLoader<LoaderResult>(context) {

        override fun loadInBackground(): LoaderResult? {
            val training = trainingRepository.loadAugmentedTraining(trainingId)
            val standardRoundId = training.training.standardRoundId
            val standardRound =
                if (standardRoundId == null) null else standardRoundDAO.loadStandardRound(
                    standardRoundId
                )
            val result = LoaderResult(training, standardRound)
            result.setRoundId(roundId)
            result.setAdjustEndIndex(endIndex)

            val arrowId = training.training.arrowId
            if (arrowId != null) {
                val arrow = arrowDAO.loadArrow(arrowId)
                result.maxArrowNumber = arrow.maxArrowNumber
                result.arrowDiameter = arrow.diameter
            }
            val bowId = training.training.bowId
            val distance = result.distance
            if (bowId != null && distance != null) {
                result.sightMark = bowDAO.loadSightMarks(bowId)
                    .firstOrNull { it.distance == distance }
            }
            return result
        }
    }

    companion object {
        internal const val TRAINING_ID = "training_id"
        internal const val ROUND_ID = "round_id"
        internal const val END_INDEX = "end_ind"
        internal const val GALLERY_REQUEST_CODE = 1

        private fun shouldShowRound(
            r: Round,
            shotShowScope: ETrainingScope,
            roundId: Long?
        ): Boolean {
            return shotShowScope !== ETrainingScope.END && (shotShowScope === ETrainingScope.TRAINING || r.id == roundId)
        }

        private fun shouldShowEnd(end: End, currentEndId: Long?): Boolean {
            return end.id != currentEndId && end.exact
        }
    }

    private data class PendingEndSave(
        val end: End,
        val shots: List<Shot>
    )
}
