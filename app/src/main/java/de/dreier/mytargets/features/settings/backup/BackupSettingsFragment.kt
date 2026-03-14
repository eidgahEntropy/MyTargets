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

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
import android.content.ContentResolver.SYNC_OBSERVER_TYPE_PENDING
import android.content.Context
import android.content.Intent
import android.content.SyncStatusObserver
import android.net.Uri
import android.os.AsyncTask
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.View.GONE
import android.view.View.VISIBLE
import android.view.ViewGroup
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.DividerItemDecoration.VERTICAL
import com.afollestad.materialdialogs.MaterialDialog
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.databinding.FragmentBackupBinding
import de.dreier.mytargets.features.settings.SettingsFragmentBase
import de.dreier.mytargets.features.settings.SettingsManager
import de.dreier.mytargets.features.settings.backup.provider.BackupUtils
import de.dreier.mytargets.features.settings.backup.provider.EBackupLocation
import de.dreier.mytargets.features.settings.backup.provider.IAsyncBackupRestore
import de.dreier.mytargets.features.settings.backup.synchronization.GenericAccountService
import de.dreier.mytargets.features.settings.backup.synchronization.SyncUtils
import de.dreier.mytargets.utils.ToolbarUtils
import de.dreier.mytargets.utils.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import de.dreier.mytargets.utils.PermissionUtils
import timber.log.Timber
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Timer
import java.util.TimerTask

class BackupSettingsFragment : SettingsFragmentBase(), IAsyncBackupRestore.OnLoadFinishedListener {

    private var backup: IAsyncBackupRestore? = null
    private var adapter: BackupAdapter? = null
    private lateinit var binding: FragmentBackupBinding
    private var updateLabelTimer: Timer? = null
    private var isLeaving = false

    /**
     * Handle to a SyncObserver. The ProgressBar element is visible until the SyncObserver reports
     * that the sync is complete.
     *
     * This allows us to delete our SyncObserver once the application is no longer in the
     * foreground.
     */
    private var syncObserverHandle: Any? = null
    private var isRefreshing = false
    private var syncTimeoutHandler: Handler? = null
    private var syncStartTime: Long = 0
    private var lastBackupCheckHandler: Handler? = null
    private var backupCountBeforeSync: Int = 0
    private var ignoreNextSyncStatus = false

    /**
     * Create a new anonymous SyncStatusObserver. It's attached to the app's ContentResolver in
     * onResume(), and removed in onPause(). If status changes, it sets the state of the Progress
     * bar. If a sync is active or pending, the progress is shown.
     */
    private val syncStatusObserver = SyncStatusObserver {
        activity?.runOnUiThread {
            // Check if we should ignore this status update (just force-cleared UI)
            if (ignoreNextSyncStatus) {
                Timber.d("Ignoring sync status update after force-clear")
                ignoreNextSyncStatus = false
                return@runOnUiThread
            }
            
            val account = GenericAccountService.account
            val syncActive = ContentResolver.isSyncActive(account, SyncUtils.CONTENT_AUTHORITY)
            val syncPending = ContentResolver.isSyncPending(account, SyncUtils.CONTENT_AUTHORITY)
            val wasRefreshing = isRefreshing
            isRefreshing = syncActive || syncPending
            
            Timber.d("Sync status changed - Active: $syncActive, Pending: $syncPending, Refreshing: $isRefreshing")
            
            // Keep button enabled so user can cancel if needed
            binding.backupProgressBar.isIndeterminate = true
            binding.backupProgressBar.visibility = if (isRefreshing) VISIBLE else GONE
            
            // Update button text based on state
            binding.backupNowButton.text = if (isRefreshing) {
                getString(R.string.cancel)
            } else {
                getString(R.string.backup_now)
            }
            
            if (isRefreshing && !wasRefreshing) {
                // Sync just started - record start time and set timeout
                syncStartTime = System.currentTimeMillis()
                backupCountBeforeSync = adapter?.itemCount ?: 0
                startSyncTimeout()
                startBackupCompletionCheck()
            } else if (!isRefreshing && wasRefreshing) {
                // Sync completed - cancel timeout and refresh backup list
                cancelSyncTimeout()
                stopBackupCompletionCheck()
                if (backup != null) {
                    backup?.getBackups(this)
                }
            }
        }
    }
    
    private fun startSyncTimeout() {
        cancelSyncTimeout()
        syncTimeoutHandler = Handler(requireContext().mainLooper)
        // Set a 2-minute timeout for sync operations (reduced from 3 minutes)
        syncTimeoutHandler?.postDelayed({
            if (isRefreshing) {
                val elapsedTime = (System.currentTimeMillis() - syncStartTime) / 1000
                Timber.w("Sync timeout after $elapsedTime seconds - forcing progress bar to hide")
                
                // Force hide the progress bar
                isRefreshing = false
                binding.backupProgressBar.visibility = GONE
                binding.backupNowButton.text = getString(R.string.backup_now)
                
                // Try to cancel any pending syncs
                try {
                    val account = GenericAccountService.account
                    ContentResolver.cancelSync(account, SyncUtils.CONTENT_AUTHORITY)
                    Timber.i("Cancelled sync due to timeout")
                } catch (e: Exception) {
                    Timber.e(e, "Failed to cancel sync")
                }
                
                // Refresh the backup list
                backup?.getBackups(this)
                
                // Show a toast to the user
                Toast.makeText(
                    requireContext(),
                    "Backup operation timed out. Please check your connection and try again.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }, 120000) // 2 minutes = 120,000 milliseconds
    }
    
    private fun cancelSyncTimeout() {
        syncTimeoutHandler?.removeCallbacksAndMessages(null)
        syncTimeoutHandler = null
    }
    
    private fun startBackupCompletionCheck() {
        stopBackupCompletionCheck()
        lastBackupCheckHandler = Handler(requireContext().mainLooper)
        
        // Check every 5 seconds if a new backup appeared
        fun scheduleNextCheck() {
            lastBackupCheckHandler?.postDelayed({
                if (isRefreshing && backup != null) {
                    Timber.d("Checking for backup completion...")
                    backup?.getBackups(object : IAsyncBackupRestore.OnLoadFinishedListener {
                        override fun onLoadFinished(backupEntries: List<BackupEntry>) {
                            val currentCount = backupEntries.size
                            Timber.d("Backup count check - Before: $backupCountBeforeSync, Current: $currentCount")
                            
                            if (currentCount > backupCountBeforeSync) {
                                // New backup detected! Force completion
                                Timber.i("New backup detected - forcing sync completion")
                                forceCompleteSyncUI()
                            } else {
                                // Keep checking
                                scheduleNextCheck()
                            }
                        }
                        
                        override fun onError(message: String) {
                            Timber.e("Error checking backups: $message")
                            // Keep checking despite error
                            scheduleNextCheck()
                        }
                    })
                }
            }, 5000)
        }
        
        // Start checking after 10 seconds (give backup time to start)
        lastBackupCheckHandler?.postDelayed({
            scheduleNextCheck()
        }, 10000)
    }
    
    private fun stopBackupCompletionCheck() {
        lastBackupCheckHandler?.removeCallbacksAndMessages(null)
        lastBackupCheckHandler = null
    }
    
    private fun forceCompleteSyncUI() {
        Timber.i("Forcing sync UI completion")
        
        // Cancel any pending syncs
        try {
            val account = GenericAccountService.account
            ContentResolver.cancelSync(account, SyncUtils.CONTENT_AUTHORITY)
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel sync")
        }
        
        // Force clear UI state
        isRefreshing = false
        binding.backupProgressBar.visibility = GONE
        binding.backupNowButton.text = getString(R.string.backup_now)
        cancelSyncTimeout()
        stopBackupCompletionCheck()
        
        // Refresh the backup list one final time
        backup?.getBackups(this)
        
        Toast.makeText(requireContext(), "Backup completed", Toast.LENGTH_SHORT).show()
    }

    /**
     * Create SyncAccount at launch, if needed.
     *
     * This will create a new account with the system for our application and register our
     * [de.dreier.mytargets.features.settings.backup.synchronization.SyncService] with it.
     */
    override fun onAttach(context: Context) {
        super.onAttach(context)

        // Create account, if needed
        SyncUtils.createSyncAccount(context)
    }

    public override fun onCreatePreferences() {
        /* Overridden to no do anything. Normally this would try to inflate the preferences,
        * but in this case we want to show our own UI. */
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        binding = DataBindingUtil.inflate(inflater, R.layout.fragment_backup, container, false)
        ToolbarUtils.showHomeAsUp(this)

        binding.backupNowButton.setOnClickListener {
            if (isRefreshing) {
                // If backup is in progress, cancel it
                Timber.i("User requested to cancel backup")
                try {
                    val account = GenericAccountService.account
                    ContentResolver.cancelSync(account, SyncUtils.CONTENT_AUTHORITY)
                    
                    // Force clear the UI state
                    isRefreshing = false
                    binding.backupProgressBar.visibility = GONE
                    binding.backupNowButton.text = getString(R.string.backup_now)
                    cancelSyncTimeout()
                    
                    Toast.makeText(requireContext(), "Backup cancelled", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Timber.e(e, "Failed to cancel backup")
                }
            } else {
                // Start backup
                SyncUtils.triggerBackup()
            }
        }

        binding.automaticBackupSwitch.setOnClickListener { onAutomaticBackupChanged() }

        binding.backupIntervalPreference.root.setOnClickListener { onBackupIntervalClicked() }
        binding.backupIntervalPreference.image
            .setImageResource(R.drawable.ic_query_builder_grey600_24dp)
        binding.backupIntervalPreference.name.setText(R.string.backup_interval)
        updateInterval()

        binding.backupLocation.root.setOnClickListener { onBackupLocationClicked() }

        binding.recentBackupsList.isNestedScrollingEnabled = false
        binding.recentBackupsList
            .addItemDecoration(DividerItemDecoration(requireContext(), VERTICAL))
        ToolbarUtils.applyWindowInsetsToScrollableContent(binding.backupScrollView)

        setHasOptionsMenu(true)
        return binding.root
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.settings_backup, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_import) {
            if (PermissionUtils.hasStoragePermission(requireContext())) {
                showFilePicker()
            } else {
                PermissionUtils.requestStoragePermission(this)
            }
            return true
        } else if (item.itemId == R.id.action_fix_db) {
            ApplicationInstance.ensureDbInitialized(requireContext())
            DatabaseFixer.fix(ApplicationInstance.db)
            return true
        } else if (item.itemId == R.id.action_remove_photos) {
            deleteAllPhotos()

        }
        return super.onOptionsItemSelected(item)
    }

    private fun deleteAllPhotos() {
        lifecycleScope.launch(Dispatchers.IO) {  // Launch coroutine on IO dispatcher (background thread)
            ApplicationInstance.ensureDbInitialized(requireContext())
            ApplicationInstance.db.imageDAO().removeAllPhotos(requireContext())

            // Back on the main thread for UI updates (Toast)
            withContext(Dispatchers.Main) {
                Toast.makeText(requireContext(), "All photos removed", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!isLeaving) {
            internalApplyBackupLocation(SettingsManager.backupLocation)
            updateAutomaticBackupSwitch()
        }

        // Check if sync appears to be stuck from a previous session
        val account = GenericAccountService.account
        val syncActive = ContentResolver.isSyncActive(account, SyncUtils.CONTENT_AUTHORITY)
        val syncPending = ContentResolver.isSyncPending(account, SyncUtils.CONTENT_AUTHORITY)
        
        Timber.d("onResume - Sync state: Active=$syncActive, Pending=$syncPending")
        
        if (syncActive || syncPending) {
            Timber.w("Found sync in active/pending state on resume - cancelling it")
            // Force cancel it to clear the stuck state
            try {
                ContentResolver.cancelSync(account, SyncUtils.CONTENT_AUTHORITY)
                Timber.i("Cancelled potentially stuck sync")
            } catch (e: Exception) {
                Timber.e(e, "Failed to cancel stuck sync")
            }
        }
        
        // Always force clear UI state on resume to prevent progress bar from reappearing
        isRefreshing = false
        binding.backupProgressBar.visibility = GONE
        binding.backupNowButton.text = getString(R.string.backup_now)
        
        // Set flag to ignore the next sync status check
        ignoreNextSyncStatus = true
        
        // Refresh the backup list
        backup?.getBackups(this)

        // Now register the observer (after clearing UI state)
        syncObserverHandle = ContentResolver.addStatusChangeListener(
            SYNC_OBSERVER_TYPE_PENDING or SYNC_OBSERVER_TYPE_ACTIVE, syncStatusObserver
        )
        
        // Trigger observer to check current state (will be ignored due to flag)
        syncStatusObserver.onStatusChanged(0)
    }

    override fun onPause() {
        updateLabelTimer?.cancel()
        cancelSyncTimeout()
        stopBackupCompletionCheck()
        if (syncObserverHandle != null) {
            ContentResolver.removeStatusChangeListener(syncObserverHandle)
            syncObserverHandle = null
        }
        super.onPause()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        backup?.onActivityResult(requestCode, resultCode, data)
        if (requestCode == IMPORT_FROM_URI && resultCode == AppCompatActivity.RESULT_OK && data != null) {
            data.data?.let { importFromUri(it) }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private fun onAutomaticBackupChanged() {
        val autoBackupEnabled = binding.automaticBackupSwitch.isChecked
        binding.backupIntervalLayout.visibility = if (autoBackupEnabled) VISIBLE else GONE
        SyncUtils.isSyncAutomaticallyEnabled = autoBackupEnabled
    }

    private fun updateAutomaticBackupSwitch() {
        val autoBackupEnabled = SyncUtils.isSyncAutomaticallyEnabled
        binding.automaticBackupSwitch.isChecked = autoBackupEnabled
    }

    private fun onBackupIntervalClicked() {
        val backupIntervals = listOf(*EBackupInterval.values())
        MaterialDialog.Builder(requireContext())
            .title(R.string.backup_interval)
            .items(backupIntervals)
            .itemsCallbackSingleChoice(
                backupIntervals.indexOf(SettingsManager.backupInterval)
            ) { _, _, index, _ ->
                SettingsManager.backupInterval = EBackupInterval.values()[index]
                updateInterval()
                true
            }
            .show()
    }

    private fun updateInterval() {
        val autoBackupEnabled = SyncUtils.isSyncAutomaticallyEnabled
        binding.backupIntervalLayout.visibility = if (autoBackupEnabled) VISIBLE else GONE
        binding.backupIntervalPreference.summary.text = SettingsManager.backupInterval.toString()
    }

    private fun onBackupLocationClicked() {
        val item = SettingsManager.backupLocation
        MaterialDialog.Builder(requireContext())
            .title(R.string.backup_location)
            .items(EBackupLocation.list)
            .itemsCallbackSingleChoice(
                EBackupLocation.list.indexOf(item)
            ) { _, _, index, _ ->
                val location = EBackupLocation.list[index]
                internalApplyBackupLocation(location)
                true
            }
            .show()
    }

    private fun updateBackupLocation() {
        val backupLocation = SettingsManager.backupLocation
        binding.backupLocation.image.setImageResource(backupLocation.drawableRes)
        binding.backupLocation.name.setText(R.string.backup_location)
        binding.backupLocation.summary.text = backupLocation.toString()
    }

    private fun internalApplyBackupLocation(item: EBackupLocation) {
        if (item.needsStoragePermissions()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                retrieveBackup(item)
            } else {
                if (PermissionUtils.hasWriteStoragePermission(requireContext())) {
                    applyBackupLocation(item)
                } else {
                    PermissionUtils.requestWriteStoragePermission(this)
                }
            }

        } else {
            applyBackupLocation(item)
        }
    }

    internal fun applyBackupLocation(item: EBackupLocation) {
        retrieveBackup(item)
    }

    private fun retrieveBackup(item: EBackupLocation) {
        SettingsManager.backupLocation = item
        backup = item.createAsyncRestore()
        binding.recentBackupsProgress.visibility = VISIBLE
        binding.recentBackupsList.visibility = GONE
        adapter = BackupAdapter(requireContext(), object : OnItemClickListener<BackupEntry> {
            override fun onItemClicked(item: BackupEntry) {
                showBackupDetails(item)
            }
        }, object : OnItemClickListener<BackupEntry> {
            override fun onItemClicked(item: BackupEntry) {
                deleteBackup(item)
            }
        })
        binding.recentBackupsList.adapter = adapter
        backup?.connect(
            requireContext(),
            object : IAsyncBackupRestore.ConnectionListener {
                override fun onLoginCancelled() {
                    internalApplyBackupLocation(EBackupLocation.INTERNAL_STORAGE)
                }

                override fun onStartIntent(intent: Intent, code: Int) {
                    startActivityForResult(intent, code)
                }

                override fun onConnected() {
                    updateBackupLocation()
                    backup?.getBackups(this@BackupSettingsFragment)
                }

                override fun onConnectionSuspended() {
                    binding.recentBackupsProgress.visibility = GONE
                    binding.recentBackupsList.visibility = VISIBLE
                    showError(
                        R.string.loading_backups_failed,
                        getString(R.string.connection_failed)
                    )
                }
            })
    }


    override fun setActivityTitle() {
        requireActivity().setTitle(R.string.backup_action)
    }

    private fun onBackupsLoaded(list: List<BackupEntry>) {
        binding.recentBackupsProgress.visibility = GONE
        binding.recentBackupsList.visibility = VISIBLE
        adapter?.setList(list.toMutableList())
        binding.lastBackupLabel.visibility = if (list.isNotEmpty()) VISIBLE else GONE
        updateLabelTimer?.cancel()
        if (list.isNotEmpty()) {
            val time = list[0].lastModifiedAt
            updateLabelTimer = Timer()
            val timerTask = object : TimerTask() {
                override fun run() {
                    activity?.runOnUiThread {
                        val relativeTime = DateUtils.getRelativeTimeSpanString(time).toString()
                        binding.lastBackupLabel.text = try {
                            getString(R.string.last_backup, relativeTime)
                        } catch (_: IllegalArgumentException) {
                            "${getString(R.string.last_backup).replace("%s", "").replace("% s", "").trim()}: $relativeTime"
                        }
                    }
                }
            }
            updateLabelTimer!!.schedule(timerTask, 0, 10000)
        }
    }

    private fun showError(@StringRes title: Int, message: String) {
        MaterialDialog.Builder(requireContext())
            .title(title)
            .content(message)
            .positiveText(android.R.string.ok)
            .show()
    }

    private fun showRestoreProgressDialog(): MaterialDialog {
        return MaterialDialog.Builder(requireContext())
            .content(R.string.restoring)
            .progress(true, 0)
            .show()
    }

    private fun showBackupDetails(item: BackupEntry) {
        val html = String.format(
            Locale.US,
            "%s<br><br><b>%s</b><br>%s<br>%s",
            getString(R.string.restore_desc),
            getString(R.string.backup_details),
            SimpleDateFormat.getDateTimeInstance()
                .format(item.lastModifiedAt),
            item.humanReadableSize
        )
        MaterialDialog.Builder(requireContext())
            .title(R.string.dialog_restore_title)
            .content(Utils.fromHtml(html))
            .positiveText(R.string.restore)
            .negativeText(android.R.string.cancel)
            .positiveColor(-0x1ac6cb)
            .negativeColor(-0x78000000)
            .onPositive { _, _ -> restoreBackup(item) }
            .show()
    }

    private fun restoreBackup(item: BackupEntry) {
        val progress = showRestoreProgressDialog()
        backup?.restoreBackup(item,
            object : IAsyncBackupRestore.BackupStatusListener {
                override fun onFinished() {
                    progress.dismiss()
                    Utils.doRestart(requireContext())
                }

                override fun onError(message: String) {
                    progress.dismiss()
                    showError(R.string.restore_failed, message)
                }
            })
    }

    private fun deleteBackup(backupEntry: BackupEntry) {
        backup?.deleteBackup(backupEntry, object : IAsyncBackupRestore.BackupStatusListener {
            override fun onFinished() {
                adapter!!.remove(backupEntry)
                backup?.getBackups(this@BackupSettingsFragment)
            }

            override fun onError(message: String) {
                showError(R.string.delete_failed, message)
            }
        })
    }

    internal fun showFilePicker() {
        val getContentIntent = Intent(Intent.ACTION_GET_CONTENT)
        getContentIntent.type = "application/zip"
        getContentIntent.addCategory(Intent.CATEGORY_OPENABLE)
        val intent = Intent.createChooser(getContentIntent, getString(R.string.select_a_file))
        startActivityForResult(intent, IMPORT_FROM_URI)
    }

    fun neverAskAgainForWritePermission() {
        isLeaving = true
        MaterialDialog.Builder(requireContext())
            .title(R.string.permission_required)
            .content(R.string.backup_permission_explanation)
            .positiveText(android.R.string.ok).negativeText(android.R.string.cancel)
            .onPositive { _, _ -> leaveBackupSettings() }
            .onNegative { _, _ -> onBackupLocationClicked() }
            .show()
    }

    internal fun showDeniedForWrite() {
        leaveBackupSettings()
    }

    private fun leaveBackupSettings() {
        isLeaving = true
        val h = Handler()
        h.post { requireActivity().supportFragmentManager.popBackStack() }
    }


    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PermissionUtils.REQUEST_STORAGE -> {
                if (PermissionUtils.isPermissionGranted(grantResults)) {
                    showFilePicker()
                }
            }
            PermissionUtils.REQUEST_WRITE_STORAGE -> {
                if (PermissionUtils.isPermissionGranted(grantResults)) {
                    applyBackupLocation(SettingsManager.backupLocation)
                } else {
                    showDeniedForWrite()
                }
            }
        }
    }

    private fun importFromUri(uri: Uri) {
        val progress = showRestoreProgressDialog()
        object : AsyncTask<Void, Void, String>() {
            override fun doInBackground(vararg params: Void): String? {
                return try {
                    Timber.i("Importing backup from $uri")
                    val st = requireContext().contentResolver.openInputStream(uri)
                    BackupUtils.importZip(requireContext(), st!!)
                    null
                } catch (ioe: FileNotFoundException) {
                    ioe.printStackTrace()
                    getString(R.string.file_not_found)
                } catch (e: Exception) {
                    e.printStackTrace()
                    getString(R.string.failed_reading_file)
                }
            }

            override fun onPostExecute(errorMessage: String?) {
                progress.dismiss()
                if (errorMessage == null) {
                    Utils.doRestart(requireContext())
                } else {
                    showError(R.string.import_failed, errorMessage)
                }
            }
        }.execute()
    }

    override fun onLoadFinished(backupEntries: List<BackupEntry>) {
        onBackupsLoaded(backupEntries)
    }

    override fun onError(message: String) {
        binding.recentBackupsProgress.visibility = GONE
        binding.recentBackupsList.visibility = VISIBLE
        showError(R.string.loading_backups_failed, message)
    }

    companion object {
        private const val IMPORT_FROM_URI = 1234
    }
}
