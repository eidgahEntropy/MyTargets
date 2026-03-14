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

package de.dreier.mytargets.base.fragments

import android.os.Bundle
import androidx.annotation.UiThread
import androidx.annotation.WorkerThread
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import de.dreier.mytargets.base.navigation.NavigationController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


typealias LoaderUICallback = () -> Unit

/**
 * Generic fragment class used as base for most fragments.
 * Has Icepick build in to save state on orientation change.
 *
 * Uses coroutines scoped to the fragment's view lifecycle for background loading.
 * This ensures callbacks are automatically cancelled when the view is destroyed,
 * preventing null context/activity/binding crashes.
 */
abstract class FragmentBase : Fragment() {

    protected lateinit var navigationController: NavigationController

    private var loadJob: Job? = null

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        navigationController = NavigationController(this)
        reloadData()
    }

    /**
     * Called on a background thread to load data.
     * Returns a callback that will be invoked on the main thread to update the UI.
     *
     * The returned callback is guaranteed to only run while the fragment's view is alive,
     * so it is safe to access binding, context, and activity without null checks.
     */
    @WorkerThread
    protected open fun onLoad(args: Bundle?): LoaderUICallback {
        return { }
    }

    /**
     * Triggers a background reload. Any previous in-flight load is cancelled.
     * The load runs on [Dispatchers.IO] and the UI callback runs on the main thread,
     * scoped to [viewLifecycleOwner.lifecycleScope] so it auto-cancels if the view is destroyed.
     */
    protected fun reloadData() {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val uiCallback = withContext(Dispatchers.IO) {
                onLoad(null)
            }
            uiCallback.invoke()
        }
    }

    protected fun reloadData(args: Bundle) {
        loadJob?.cancel()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            val uiCallback = withContext(Dispatchers.IO) {
                onLoad(args)
            }
            uiCallback.invoke()
        }
    }

    companion object {
        private const val LOADER_ID = 0
    }
}
