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

package de.dreier.mytargets.features.scoreboard

import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.*
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.base.db.RoundRepository
import de.dreier.mytargets.base.db.TrainingRepository
import de.dreier.mytargets.base.fragments.FragmentBase
import de.dreier.mytargets.base.fragments.LoaderUICallback
import de.dreier.mytargets.databinding.FragmentScoreboardBinding
import de.dreier.mytargets.databinding.PartialScoreboardSignaturesBinding
import de.dreier.mytargets.features.settings.ESettingsScreens
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.shared.models.db.End
import de.dreier.mytargets.shared.models.db.Round
import de.dreier.mytargets.shared.models.db.Signature
import de.dreier.mytargets.shared.models.db.Training
import de.dreier.mytargets.utils.MobileWearableClient
import de.dreier.mytargets.utils.MobileWearableClient.Companion.BROADCAST_UPDATE_TRAINING_FROM_REMOTE
import de.dreier.mytargets.utils.ToolbarUtils
import de.dreier.mytargets.utils.Utils
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.dreier.mytargets.utils.print.CustomPrintDocumentAdapter
import de.dreier.mytargets.utils.print.ViewToPdfWriter
import de.dreier.mytargets.utils.toUri
import org.threeten.bp.format.DateTimeFormatter
import java.io.File
import java.io.IOException
import java.util.*

class ScoreboardFragment : FragmentBase() {

    private var trainingId: Long = 0
    private var roundId: Long = 0
    private lateinit var binding: FragmentScoreboardBinding
    private var training: Training? = null
    private var rounds: List<Round> = emptyList()

    private val database by lazy(LazyThreadSafetyMode.NONE) { ApplicationInstance.db }
    private val trainingDAO by lazy(LazyThreadSafetyMode.NONE) { database.trainingDAO() }
    private val roundDAO by lazy(LazyThreadSafetyMode.NONE) { database.roundDAO() }
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
            if (this@ScoreboardFragment.roundId == roundId || trainingId == this@ScoreboardFragment.trainingId && this@ScoreboardFragment.roundId == -1L) {
                reloadData()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = FragmentScoreboardBinding.inflate(inflater, container, false)

        val args = requireArguments()
        trainingId = args.getLong(ScoreboardActivity.TRAINING_ID)
        roundId = args.getLong(ScoreboardActivity.ROUND_ID, -1L)
        setHasOptionsMenu(true)

        return binding.root
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        ToolbarUtils.setSupportActionBar(this, binding.toolbar)
        ToolbarUtils.showHomeAsUp(this)
        ToolbarUtils.applyWindowInsetsToScrollableContent(binding.scrollView)
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(
            updateReceiver,
            IntentFilter(BROADCAST_UPDATE_TRAINING_FROM_REMOTE)
        )
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(updateReceiver)
    }

    override fun onLoad(args: Bundle?): LoaderUICallback {
        training = trainingDAO.loadTraining(trainingId)
        val t = training ?: return {}
        val archerSignature = trainingRepository.getOrCreateArcherSignature(t)
        val witnessSignature = trainingRepository.getOrCreateWitnessSignature(t)

        rounds = if (roundId == -1L) {
            roundDAO.loadRounds(t.id)
        } else {
            listOf(roundDAO.loadRound(roundId))
        }
        val ctx = context ?: return {}
        val scoreboard = ScoreboardUtils
            .getScoreboardView(
                ctx,
                ApplicationInstance.db,
                Utils.getCurrentLocale(ctx),
                t,
                rounds,
                SettingsManager.scoreboardConfiguration
            )
        return {
            binding.progressBar.visibility = GONE
            scoreboard.layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            binding.container.removeAllViews()
            binding.container.addView(scoreboard)

            val signaturesView = scoreboard.findViewById<View>(R.id.signatures_layout)
            if (signaturesView != null) {
                val signatures = PartialScoreboardSignaturesBinding.bind(signaturesView)

                var archer = SettingsManager.profileFullName
                if (archer.trim { it <= ' ' }.isEmpty()) {
                    archer = getString(R.string.archer)
                }
                val finalArcher = archer

                signatures.editSignatureArcher
                    .setOnClickListener { onSignatureClicked(archerSignature, finalArcher) }
                signatures.editSignatureWitness
                    .setOnClickListener {
                        onSignatureClicked(
                            witnessSignature,
                            getString(R.string.target_captain)
                        )
                    }

                signatures.archerSignaturePlaceholder.visibility =
                        if (archerSignature.isSigned) GONE else VISIBLE
                signatures.witnessSignaturePlaceholder.visibility =
                        if (witnessSignature.isSigned) GONE else VISIBLE
            }
        }
    }

    private fun onSignatureClicked(signature: Signature, defaultName: String) {
        val fm = fragmentManager
        if (fm != null) {
            val signatureDialogFragment =
                SignatureDialogFragment.newInstance(signature, defaultName)
            signatureDialogFragment.show(fm, "signature")
            fm.registerFragmentLifecycleCallbacks(object :
                FragmentManager.FragmentLifecycleCallbacks() {
                override fun onFragmentViewDestroyed(fm: FragmentManager, f: Fragment) {
                    fm.unregisterFragmentLifecycleCallbacks(this)
                    reloadData()
                }
            }, false)
        }
    }

    override fun onResume() {
        super.onResume()
        reloadData()
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_scoreboard, menu)
        menu.findItem(R.id.action_print).isVisible = Utils.isKitKat
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> {
                share()
                return true
            }
            R.id.action_print -> {
                if (Utils.isKitKat) {
                    print()
                }
                return true
            }
            R.id.action_settings -> {
                navigationController.navigateToSettings(ESettingsScreens.SCOREBOARD)
                return true
            }
            else -> return super.onOptionsItemSelected(item)
        }
    }

    /* Called after the user selected with items he wants to share */
    private fun share() {
        val ctx = context ?: return
        val t = training ?: return
        if (rounds.isEmpty()) return
        val fileType = SettingsManager.scoreboardShareFileType
        viewLifecycleOwner.lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val scoreboardFile = File(ctx.cacheDir, getDefaultFileName(fileType))
                    val content = ScoreboardUtils
                        .getScoreboardView(
                            ctx,
                            ApplicationInstance.db,
                            Utils.getCurrentLocale(ctx),
                            t,
                            rounds,
                            SettingsManager.scoreboardConfiguration
                        )
                    if (fileType === EFileType.PDF && Utils.isKitKat) {
                        ScoreboardUtils.generatePdf(content, scoreboardFile)
                    } else {
                        ScoreboardUtils.generateBitmap(ctx, content, scoreboardFile)
                    }
                    scoreboardFile.toUri(ctx)
                } catch (e: IOException) {
                    e.printStackTrace()
                    null
                }
            }
            if (uri == null) {
                Snackbar.make(binding.root, R.string.sharing_failed, Snackbar.LENGTH_SHORT)
                    .show()
            } else {
                val shareIntent = Intent(Intent.ACTION_SEND)
                shareIntent.type = fileType.mimeType
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
                startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.KITKAT)
    private fun print() {
        val context = context ?: return
        val training = training ?: return
        if (rounds.isEmpty()) return

        val fileName = getDefaultFileName(EFileType.PDF)

        val content = ScoreboardUtils.getScoreboardView(
            context, ApplicationInstance.db, Utils
                .getCurrentLocale(context), training, rounds, SettingsManager
                .scoreboardConfiguration
        )

        val jobName = getString(R.string.scoreboard) + " Document"
        val pda = CustomPrintDocumentAdapter(ViewToPdfWriter(content), fileName)

        // Create a print job with name and adapter instance
        val printManager = context.getSystemService<PrintManager>()!!
        printManager.print(jobName, pda, PrintAttributes.Builder().build())
    }

    fun getDefaultFileName(extension: EFileType): String {
        val t = training ?: return "scoreboard.${extension.name.lowercase(Locale.US)}"
        return t.date.format(DateTimeFormatter.ISO_LOCAL_DATE) + "-" +
                getString(R.string.scoreboard) + "." + extension.name.lowercase(Locale.US)
    }
}
