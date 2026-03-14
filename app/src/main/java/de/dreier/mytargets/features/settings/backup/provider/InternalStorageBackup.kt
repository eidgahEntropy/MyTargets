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

package de.dreier.mytargets.features.settings.backup.provider

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import de.dreier.mytargets.R
import de.dreier.mytargets.app.ApplicationInstance
import de.dreier.mytargets.features.settings.backup.BackupEntry
import de.dreier.mytargets.features.settings.backup.BackupException
import de.dreier.mytargets.shared.SharedApplicationInstance.Companion.context
import de.dreier.mytargets.shared.SharedApplicationInstance.Companion.getStr
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.ref.WeakReference

object InternalStorageBackup {
    private const val FOLDER_NAME = "MyTargets"
    private const val TAG = "InternalStorageBackup"

    @Throws(IOException::class)
    private fun createDirectory(directory: File) {

        directory.mkdir()
        if (!directory.exists() || !directory.isDirectory) {
            throw IOException(getStr(R.string.dir_not_created))
        }
    }

    class AsyncRestore : IAsyncBackupRestore {

        private var context: WeakReference<Context>? = null

        override fun connect(context: Context, listener: IAsyncBackupRestore.ConnectionListener) {
            this.context = WeakReference(context)
            listener.onConnected()
        }

        override fun getBackups(listener: IAsyncBackupRestore.OnLoadFinishedListener) {
            val ctx = context?.get() ?: return listener.onLoadFinished(emptyList())
            val localBackups = readLocalBackups(ctx)
            val publicBackups = readPublicDownloadsBackups(ctx)
            val allBackups = (localBackups + publicBackups)
                .distinctBy { it.fileId }
                .sortedByDescending { it.lastModifiedAt }
            listener.onLoadFinished(allBackups)
        }

        private fun isBackup(file: File): Boolean {
            return file.isFile && file.name.contains("backup_") && file.name
                .endsWith(".zip")
        }

        private fun readLocalBackups(context: Context): List<BackupEntry> {
            val backupDir = File(getStorageDirectory(context), FOLDER_NAME)
            if (!backupDir.isDirectory) {
                return emptyList()
            }
            return backupDir.listFiles()
                ?.filter { isBackup(it) }
                ?.map {
                    BackupEntry(
                        it.absolutePath,
                        it.lastModified(),
                        it.length()
                    )
                } ?: emptyList()
        }

        private fun readPublicDownloadsBackups(context: Context): List<BackupEntry> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return emptyList()
            }
            val resolver = context.contentResolver
            val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DATE_MODIFIED,
                MediaStore.MediaColumns.SIZE
            )
            val selection =
                "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf(
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME/%",
                "MyTargets_backup_%.zip"
            )

            val backups = mutableListOf<BackupEntry>()
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val modifiedIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val uri = ContentUris.withAppendedId(collection, id)
                    val modified = cursor.getLong(modifiedIndex) * 1000L
                    val size = cursor.getLong(sizeIndex)
                    backups.add(BackupEntry(uri.toString(), modified, size))
                }
            }
            return backups
        }

        override fun restoreBackup(
            backup: BackupEntry,
            listener: IAsyncBackupRestore.BackupStatusListener
        ) {
            try {
                val ctx = context?.get() ?: return listener.onError("Context no longer available")
                if (isContentUri(backup.fileId)) {
                    val stream = ctx.contentResolver.openInputStream(Uri.parse(backup.fileId))
                        ?: throw IOException("Unable to open selected backup")
                    BackupUtils.importZip(ctx, stream)
                } else {
                    BackupUtils.importZip(ctx, FileInputStream(File(backup.fileId)))
                }
                listener.onFinished()
            } catch (e: IOException) {
                listener.onError(e.localizedMessage)
                e.printStackTrace()
            }

        }

        override fun deleteBackup(
            backup: BackupEntry,
            listener: IAsyncBackupRestore.BackupStatusListener
        ) {
            val deleted = if (isContentUri(backup.fileId)) {
                val ctx = context?.get()
                if (ctx == null) {
                    false
                } else {
                    ctx.contentResolver.delete(Uri.parse(backup.fileId), null, null) > 0
                }
            } else {
                File(backup.fileId).delete()
            }
            if (deleted) {
                listener.onFinished()
            } else {
                listener.onError("Backup could not be deleted!")
            }
        }

        override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
            return false
        }
    }

    class Backup : IBlockingBackup {

        @Throws(BackupException::class)
        override fun performBackup(context: Context) {
            try {
                val backupDir = File(
                    getStorageDirectory(context),
                    FOLDER_NAME
                )
                createDirectory(backupDir)
                val zipFile = File(backupDir, BackupUtils.backupName)
                BackupUtils.zip(context, ApplicationInstance.db, FileOutputStream(zipFile))
                try {
                    exportBackupToPublicDownloads(context, zipFile)
                } catch (e: IOException) {
                    Log.w(
                        TAG,
                        "Backup created locally but failed to export to Downloads: ${e.localizedMessage}",
                        e
                    )
                }
            } catch (e: IOException) {
                throw BackupException(e.localizedMessage, e)
            }

        }
    }

    private fun getStorageDirectory(context: Context): File {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.getExternalFilesDirs(context, null)[0] // Scoped Storage
        } else {
            Environment.getExternalStorageDirectory() // Legacy Storage
        }
    }

    @Throws(IOException::class)
    private fun exportBackupToPublicDownloads(context: Context, zipFile: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, zipFile.name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/zip")
            put(
                MediaStore.MediaColumns.RELATIVE_PATH,
                "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER_NAME"
            )
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, contentValues)
            ?: throw IOException("Unable to create backup in Downloads")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(zipFile).use { input -> input.copyTo(output) }
            } ?: throw IOException("Unable to open backup destination")

            contentValues.clear()
            contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        } catch (e: IOException) {
            resolver.delete(uri, null, null)
            throw e
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw IOException("Failed to export backup to Downloads", e)
        }
    }

    private fun isContentUri(id: String): Boolean {
        return id.startsWith("content://")
    }

}
