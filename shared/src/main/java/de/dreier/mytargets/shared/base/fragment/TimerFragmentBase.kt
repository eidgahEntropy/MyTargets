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

package de.dreier.mytargets.shared.base.fragment

import android.content.Context
import android.media.MediaPlayer
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Vibrator
import android.view.View
import android.view.WindowManager
import androidx.core.content.getSystemService
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import de.dreier.mytargets.shared.R
import de.dreier.mytargets.shared.models.TimerSettings
import de.dreier.mytargets.shared.utils.VibratorCompat
import de.dreier.mytargets.shared.utils.parcelable
import kotlin.math.ceil

abstract class TimerFragmentBase : Fragment(), View.OnClickListener {

    private var currentStatus = ETimerState.WAIT_FOR_START
    private var countdown: CountDownTimer? = null
    private var horn: MediaPlayer? = null
    private var hornInitJob: kotlinx.coroutines.Job? = null
    lateinit var settings: TimerSettings
    private var exitAfterStop = true

    override fun onAttach(context: Context) {
        super.onAttach(context)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = requireArguments().parcelable(requireArguments(), ARG_TIMER_SETTINGS)!!
        exitAfterStop = requireArguments().getBoolean(ARG_EXIT_AFTER_STOP)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.setOnClickListener(this)
        // Pre-initialize MediaPlayer off main thread to avoid ANR
        val ctx = requireContext().applicationContext
        hornInitJob = viewLifecycleOwner.lifecycleScope.launch {
            val player = withContext(Dispatchers.IO) {
                try {
                    MediaPlayer.create(ctx, R.raw.horn)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to create MediaPlayer for horn")
                    null
                }
            }
            if (isAdded && viewLifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                horn = player
            } else {
                // Fragment view gone — release immediately to avoid leak
                player?.release()
            }
        }
        changeStatus(currentStatus)
    }

    override fun onStop() {
        super.onStop()
        countdown?.cancel()
        countdown = null
    }

    override fun onDestroyView() {
        hornInitJob?.cancel()
        hornInitJob = null
        try {
            horn?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaPlayer already released in onDestroyView")
        }
        horn = null
        super.onDestroyView()
    }

    override fun onDetach() {
        countdown?.cancel()
        countdown = null
        hornInitJob?.cancel()
        hornInitJob = null
        try {
            horn?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaPlayer already released")
        }
        horn = null
        super.onDetach()
    }

    override fun onClick(v: View) {
        changeStatus(currentStatus.next)
    }

    private fun changeStatus(status: ETimerState) {
        if (!isAdded) return
        countdown?.cancel()
        if (status === ETimerState.EXIT) {
            if (exitAfterStop) {
                activity?.finish()
            } else {
                changeStatus(ETimerState.WAIT_FOR_START)
            }
            return
        }
        currentStatus = status
        applyStatus(status)
        playSignal(status.signalCount)

        if (status === ETimerState.FINISHED) {
            applyTime(getString(R.string.stop))
            countdown = object : CountDownTimer(6000, 100) {
                override fun onTick(millisUntilFinished: Long) {}

                override fun onFinish() {
                    changeStatus(status.next)
                }
            }.start()
        } else {
            if (status !== ETimerState.PREPARATION && status !== ETimerState.SHOOTING && status !== ETimerState.COUNTDOWN) {
                applyTime("")
            } else {
                val offset = getOffset(status)
                countdown = object : CountDownTimer((getDuration(status) * 1000).toLong(), 1000) {
                    override fun onTick(millisUntilFinished: Long) {
                        val countdown = offset + ceil(millisUntilFinished / 1000.0).toInt()
                        applyTime(countdown.toString())
                    }

                    override fun onFinish() {
                        changeStatus(status.next)
                    }
                }.start()
            }
        }
    }

    protected fun getDuration(status: ETimerState): Int {
        return when (status) {
            ETimerState.PREPARATION -> settings.waitTime
            ETimerState.SHOOTING -> settings.shootTime - settings.warnTime
            ETimerState.COUNTDOWN -> settings.warnTime
            else -> throw IllegalArgumentException()
        }
    }

    private fun getOffset(status: ETimerState): Int {
        return if (status === ETimerState.SHOOTING) {
            settings.warnTime
        } else {
            0
        }
    }

    private fun playSignal(n: Int) {
        if (n > 0) {
            if (settings.sound) {
                playHorn(n)
            }
            if (settings.vibrate) {
                val pattern = LongArray(1 + n * 2)
                val v = requireActivity().getSystemService<Vibrator>()!!
                pattern[0] = 150
                for (i in 0 until n) {
                    pattern[i * 2 + 1] = 400
                    pattern[i * 2 + 2] = 750
                }
                VibratorCompat.vibrate(v, pattern, -1)
            }
        }
    }

    private fun playHorn(n: Int) {
        val player = horn ?: return
        try {
            if (!player.isPlaying && !isDetached) {
                player.setOnCompletionListener(null)
                player.setOnCompletionListener {
                    if (n > 1) {
                        playHorn(n - 1)
                    }
                }
                player.start()
            }
        } catch (e: IllegalStateException) {
            Timber.e(e, "MediaPlayer in bad state during playHorn")
        }
    }


    abstract fun applyTime(text: String)

    protected abstract fun applyStatus(status: ETimerState)

    companion object {
        const val ARG_TIMER_SETTINGS = "timer_settings"
        const val ARG_EXIT_AFTER_STOP = "exit_after_stop"
    }

}
