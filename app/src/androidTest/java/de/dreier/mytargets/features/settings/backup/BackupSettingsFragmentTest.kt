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

package de.dreier.mytargets.features.settings.backup

import android.content.Context
import android.content.Intent
import androidx.preference.PreferenceFragmentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.dreier.mytargets.R
import de.dreier.mytargets.features.settings.ESettingsScreens
import de.dreier.mytargets.features.settings.SettingsActivity
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.features.settings.backup.provider.EBackupLocation
import de.dreier.mytargets.features.settings.backup.provider.IAsyncBackupRestore
import de.dreier.mytargets.test.base.UITestBase
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Instrumented tests for [BackupSettingsFragment].
 *
 * Covers the ANR regression (getBackups must not be called on the main thread) and
 * the key UI transitions (progress → list, error delivery).
 */
@RunWith(AndroidJUnit4::class)
class BackupSettingsFragmentTest : UITestBase() {

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Build an intent that opens [SettingsActivity] directly on the backup screen. */
    private fun backupScreenIntent(): Intent =
        Intent(ApplicationProvider.getApplicationContext(), SettingsActivity::class.java).apply {
            putExtra(
                PreferenceFragmentCompat.ARG_PREFERENCE_ROOT,
                ESettingsScreens.BACKUP.key
            )
        }

    /**
     * Walk the fragment back-stack of [activity] to find the first
     * [BackupSettingsFragment] that is currently added.
     */
    private fun findBackupFragment(activity: SettingsActivity): BackupSettingsFragment? {
        val allFragments = buildList {
            activity.supportFragmentManager.fragments.forEach { f ->
                add(f)
                addAll(f.childFragmentManager.fragments)
            }
        }
        return allFragments.filterIsInstance<BackupSettingsFragment>().firstOrNull { it.isAdded }
    }

    /**
     * Inject [fake] into the private `backup` field of [fragment] via reflection.
     * This lets tests control what [IAsyncBackupRestore.getBackups] does without
     * changing any production code.
     */
    private fun injectFakeBackup(
        fragment: BackupSettingsFragment,
        fake: IAsyncBackupRestore
    ) {
        BackupSettingsFragment::class.java
            .getDeclaredField("backup")
            .also { it.isAccessible = true }
            .set(fragment, fake)
    }

    /**
     * Invoke the private `loadBackupsAsync()` method on [fragment] via reflection.
     * Must be called on the main thread (inside [ActivityScenario.onActivity]).
     */
    private fun callLoadBackupsAsync(fragment: BackupSettingsFragment) {
        BackupSettingsFragment::class.java
            .getDeclaredMethod("loadBackupsAsync")
            .also { it.isAccessible = true }
            .invoke(fragment)
    }

    /**
     * Invoke the private `loadBackupsAsync(listener)` overload on [fragment].
     */
    private fun callLoadBackupsAsyncWithListener(
        fragment: BackupSettingsFragment,
        listener: IAsyncBackupRestore.OnLoadFinishedListener
    ) {
        BackupSettingsFragment::class.java
            .getDeclaredMethod(
                "loadBackupsAsync",
                IAsyncBackupRestore.OnLoadFinishedListener::class.java
            )
            .also { it.isAccessible = true }
            .invoke(fragment, listener)
    }

    // -------------------------------------------------------------------------
    // Setup
    // -------------------------------------------------------------------------

    @Before
    fun setUp() {
        SettingsManager.backupLocation = EBackupLocation.INTERNAL_STORAGE
    }

    // -------------------------------------------------------------------------
    // ANR regression: getBackups must never be called on the main thread
    // -------------------------------------------------------------------------

    /**
     * Regression test for the ContentResolver.query() ANR.
     *
     * Before the fix: all call-sites invoked backup.getBackups(this) directly on
     * the UI thread, blocking the main thread with a synchronous ContentResolver
     * query (InternalStorageBackup.getBackups calls resolver.query() internally).
     *
     * After the fix: loadBackupsAsync() wraps getBackups() inside a Dispatchers.IO
     * coroutine, so the calling thread must never be "main".
     */
    @Test
    fun getBackupsIsCalledOffMainThread() {
        val callingThread = AtomicReference<String>()
        val latch = CountDownLatch(1)

        val fakeBackup = object : IAsyncBackupRestore {
            override fun connect(context: Context, listener: IAsyncBackupRestore.ConnectionListener) =
                listener.onConnected()

            override fun getBackups(listener: IAsyncBackupRestore.OnLoadFinishedListener) {
                callingThread.set(Thread.currentThread().name)
                latch.countDown()
                listener.onLoadFinished(emptyList())
            }

            override fun restoreBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
            override fun deleteBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
            override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = false
        }

        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = findBackupFragment(activity)
                assertNotNull("BackupSettingsFragment not found in fragment manager", fragment)
                fragment!! // asserted above

                // Replace the real provider with our thread-tracking fake, then trigger a load.
                injectFakeBackup(fragment, fakeBackup)
                callLoadBackupsAsync(fragment)
            }

            // Wait up to 5 s for getBackups() to be called in the coroutine.
            assertTrue(
                "getBackups() was never called — coroutine did not run",
                latch.await(5, TimeUnit.SECONDS)
            )

            // THE KEY ASSERTION: getBackups() must run on a background thread, not "main".
            assertFalse(
                "getBackups() was called on the main thread — ANR regression detected! " +
                        "Thread was: '${callingThread.get()}'",
                callingThread.get()?.startsWith("main") == true
            )
        }
    }

    /**
     * Companion check: the result from getBackups() is delivered back to the
     * fragment's [IAsyncBackupRestore.OnLoadFinishedListener] on the main thread
     * (Android UI operations require the main thread).
     */
    @Test
    fun loadBackupsAsync_deliversResultOnMainThread() {
        val deliveryThread = AtomicReference<String>()
        val latch = CountDownLatch(1)

        val fakeBackup = object : IAsyncBackupRestore {
            override fun connect(context: Context, listener: IAsyncBackupRestore.ConnectionListener) =
                listener.onConnected()

            override fun getBackups(listener: IAsyncBackupRestore.OnLoadFinishedListener) =
                listener.onLoadFinished(emptyList())

            override fun restoreBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
            override fun deleteBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
            override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = false
        }

        val customListener = object : IAsyncBackupRestore.OnLoadFinishedListener {
            override fun onLoadFinished(backupEntries: List<BackupEntry>) {
                deliveryThread.set(Thread.currentThread().name)
                latch.countDown()
            }
            override fun onError(message: String) {
                deliveryThread.set(Thread.currentThread().name)
                latch.countDown()
            }
        }

        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = findBackupFragment(activity)
                assertNotNull("BackupSettingsFragment not found in fragment manager", fragment)
                fragment!!

                injectFakeBackup(fragment, fakeBackup)
                callLoadBackupsAsyncWithListener(fragment, customListener)
            }

            assertTrue(
                "Custom listener was never called",
                latch.await(5, TimeUnit.SECONDS)
            )

            assertTrue(
                "Result was not delivered on the main thread. " +
                        "Thread was: '${deliveryThread.get()}'",
                deliveryThread.get()?.startsWith("main") == true
            )
        }
    }

    // -------------------------------------------------------------------------
    // Error path: onError from getBackups is forwarded to the listener
    // -------------------------------------------------------------------------

    @Test
    fun loadBackupsAsyncWithListener_forwardsErrorToListener() {
        val errorReceived = AtomicBoolean(false)
        val errorMessage = AtomicReference<String>()
        val latch = CountDownLatch(1)

        val fakeBackup = object : IAsyncBackupRestore {
            override fun connect(context: Context, listener: IAsyncBackupRestore.ConnectionListener) =
                listener.onConnected()

            override fun getBackups(listener: IAsyncBackupRestore.OnLoadFinishedListener) =
                listener.onError("simulated provider error")

            override fun restoreBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
            override fun deleteBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
            override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = false
        }

        val errorListener = object : IAsyncBackupRestore.OnLoadFinishedListener {
            override fun onLoadFinished(backupEntries: List<BackupEntry>) = Unit
            override fun onError(message: String) {
                errorReceived.set(true)
                errorMessage.set(message)
                latch.countDown()
            }
        }

        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = findBackupFragment(activity)
                assertNotNull("BackupSettingsFragment not found in fragment manager", fragment)
                fragment!!

                injectFakeBackup(fragment, fakeBackup)
                callLoadBackupsAsyncWithListener(fragment, errorListener)
            }

            assertTrue("onError() was never called", latch.await(5, TimeUnit.SECONDS))
            assertTrue("errorReceived flag not set", errorReceived.get())
            assertTrue(
                "Error message not forwarded correctly",
                errorMessage.get().contains("simulated provider error")
            )
        }
    }

    // -------------------------------------------------------------------------
    // Null safety: loadBackupsAsync() is a no-op when backup is null
    // -------------------------------------------------------------------------

    @Test
    fun loadBackupsAsync_isNoOpWhenBackupFieldIsNull() {
        val getBackupsCalled = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use { scenario ->
            scenario.onActivity { activity ->
                val fragment = findBackupFragment(activity)
                assertNotNull("BackupSettingsFragment not found in fragment manager", fragment)
                fragment!!

                // Null out the backup field — simulates state before provider connects.
                injectFakeBackup(fragment, object : IAsyncBackupRestore {
                    override fun connect(context: Context, listener: IAsyncBackupRestore.ConnectionListener) =
                        listener.onConnected()
                    override fun getBackups(listener: IAsyncBackupRestore.OnLoadFinishedListener) {
                        getBackupsCalled.set(true)
                        listener.onLoadFinished(emptyList())
                        latch.countDown()
                    }
                    override fun restoreBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
                    override fun deleteBackup(backup: BackupEntry, listener: IAsyncBackupRestore.BackupStatusListener) = Unit
                    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) = false
                })

                // Now null out the field so loadBackupsAsync returns early.
                BackupSettingsFragment::class.java
                    .getDeclaredField("backup")
                    .also { it.isAccessible = true }
                    .set(fragment, null)

                // This must NOT throw and must NOT call getBackups.
                callLoadBackupsAsync(fragment)
            }

            // Give the coroutine time to run if it was wrongly launched.
            val wasLaunched = latch.await(2, TimeUnit.SECONDS)
            assertFalse(
                "getBackups() was called even though backup was null — early return is missing",
                wasLaunched
            )
        }
    }

    // -------------------------------------------------------------------------
    // UI smoke: backup screen opens and the progress spinner eventually hides
    // (proves that background loading ran and called back on the main thread)
    // -------------------------------------------------------------------------

    @Test
    fun backupScreen_progressHidesAfterLoadCompletes() {
        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use {
            // Wait for Espresso idle (background load + main-thread UI update finished).
            onView(withId(R.id.recentBackupsProgress))
                .check(matches(not(isDisplayed())))
        }
    }

    @Test
    fun backupScreen_backupNowButtonIsVisible() {
        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use {
            onView(withText(R.string.backup_now)).check(matches(isDisplayed()))
        }
    }

    @Test
    fun backupScreen_recreateDoesNotCrash() {
        ActivityScenario.launch<SettingsActivity>(backupScreenIntent()).use { scenario ->
            onView(withText(R.string.backup_now)).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText(R.string.backup_now)).check(matches(isDisplayed()))
        }
    }
}
