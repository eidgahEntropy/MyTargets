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

package de.dreier.mytargets.base.gallery

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.afollestad.materialdialogs.MaterialDialog
import com.evernote.android.state.State
import de.dreier.mytargets.R
import de.dreier.mytargets.base.activities.ChildActivityBase
import de.dreier.mytargets.base.gallery.adapters.HorizontalListAdapters
import de.dreier.mytargets.base.gallery.adapters.ViewPagerAdapter
import de.dreier.mytargets.base.navigation.NavigationController
import de.dreier.mytargets.databinding.ActivityGalleryBinding
import de.dreier.mytargets.utils.*
import de.dreier.mytargets.utils.PermissionUtils
import pl.aprilapps.easyphotopicker.DefaultCallback
import pl.aprilapps.easyphotopicker.EasyImage
import java.io.File
import java.io.IOException
import java.util.*

class GalleryActivity : ChildActivityBase() {

    internal var adapter: ViewPagerAdapter? = null
    internal var layoutManager: LinearLayoutManager? = null
    internal lateinit var previewAdapter: HorizontalListAdapters

    @State
    lateinit var imageList: ImageList

    private lateinit var binding: ActivityGalleryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_gallery)

        val title = intent.getStringExtra(EXTRA_TITLE)
        if (savedInstanceState == null) {
            imageList = intent.parcelableExtra(intent,EXTRA_IMAGES) ?: ImageList()
        }

        setSupportActionBar(binding.toolbar)
        ToolbarUtils.applyWindowInsets(binding.toolbar)
        ToolbarUtils.showHomeAsUp(this)
        if (title != null) {
            ToolbarUtils.setTitle(this, title)
        }
        Utils.showSystemUI(this)

        layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.imagesHorizontalList.layoutManager = layoutManager

        adapter = ViewPagerAdapter(this, imageList, binding.toolbar, binding.imagesHorizontalList)
        binding.pager.adapter = adapter

        previewAdapter = HorizontalListAdapters(this, imageList) { this.goToImage(it) }
        binding.imagesHorizontalList.adapter = previewAdapter
        previewAdapter.notifyDataSetChanged()

        binding.pager.addOnPageChangeListener(object : ViewPager.OnPageChangeListener {
            override fun onPageScrolled(
                position: Int,
                positionOffset: Float,
                positionOffsetPixels: Int
            ) {
            }

            override fun onPageSelected(position: Int) {
                binding.imagesHorizontalList.smoothScrollToPosition(position)
                previewAdapter.setSelectedItem(position)
            }

            override fun onPageScrollStateChanged(state: Int) {

            }
        })

        val currentPos = 0
        previewAdapter.setSelectedItem(currentPos)
        binding.pager.currentItem = currentPos

        if (imageList.size() == 0 && savedInstanceState == null) {
            if (PermissionUtils.hasCameraPermission(this)) {
                onTakePicture()
            } else {
                PermissionUtils.requestCameraPermission(this)
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.gallery, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.action_share).isVisible = !imageList.isEmpty
        menu.findItem(R.id.action_delete).isVisible = !imageList.isEmpty
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> {
                val currentItem = binding.pager.currentItem
                shareImage(currentItem)
                return true
            }

            R.id.action_delete -> {
                val currentItem = binding.pager.currentItem
                deleteImage(currentItem)
                return true
            }

            android.R.id.home -> {
                navigationController.finish()
                return true
            }

            else -> return super.onOptionsItemSelected(item)
        }
    }

    private fun shareImage(currentItem: Int) {
        val currentImage = imageList[currentItem]
        val file = File(filesDir, currentImage.fileName)
        val uri = file.toUri(this)
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "*/*"
        shareIntent.putExtra(Intent.EXTRA_STREAM, uri)
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share)))
    }

    private fun deleteImage(currentItem: Int) {
        MaterialDialog.Builder(this)
            .content(R.string.delete_image)
            .negativeText(android.R.string.cancel)
            .negativeColorRes(R.color.md_grey_500)
            .positiveText(R.string.delete)
            .positiveColorRes(R.color.md_red_500)
            .onPositive { _, _ ->
                imageList.remove(currentItem)
                updateResult()
                invalidateOptionsMenu()
                adapter?.notifyDataSetChanged()
                val nextItem = Math.min(imageList.size() - 1, currentItem)
                previewAdapter.setSelectedItem(nextItem)
                binding.pager.currentItem = nextItem
            }
            .show()
    }

    private fun updateResult() {
        navigationController.setResultSuccess(imageList)
    }

    internal fun onTakePicture() {
        try {
            EasyImage.openCameraForImage(this, 0)
        } catch (e: ActivityNotFoundException) {
            timber.log.Timber.e(e, "No camera app available")
            Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_LONG).show()
        }
    }

    internal fun onSelectImage() {
        try {
            val intent = Intent(
                Intent.ACTION_PICK,
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            )
            intent.type = "image/*"
            startActivityForResult(intent, REQUEST_GALLERY_IMAGE)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT)
                fallback.type = "image/*"
                fallback.addCategory(Intent.CATEGORY_OPENABLE)
                startActivityForResult(fallback, REQUEST_GALLERY_IMAGE)
            } catch (e2: Exception) {
                timber.log.Timber.e(e2, "No gallery app available")
            }
        }
    }

    @SuppressLint("NeedOnRequestPermissionsResult")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PermissionUtils.REQUEST_CAMERA -> {
                if (PermissionUtils.isPermissionGranted(grantResults)) {
                    onTakePicture()
                }
            }
            PermissionUtils.REQUEST_STORAGE -> {
                if (PermissionUtils.isPermissionGranted(grantResults)) {
                    onSelectImage()
                }
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_GALLERY_IMAGE && resultCode == RESULT_OK) {
            val uri = data?.data
                ?: data?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            if (uri != null) {
                val file = copyUriToLocalFile(uri)
                if (file != null) loadImages(listOf(file))
            }
            return
        }

        EasyImage.handleActivityResult(requestCode, resultCode, data, this,
            object : DefaultCallback() {

                override fun onImagesPicked(
                    imageFiles: List<File>,
                    source: EasyImage.ImageSource,
                    type: Int
                ) {
                    loadImages(imageFiles)
                }

                override fun onCanceled(source: EasyImage.ImageSource?, type: Int) {
                    if (source == EasyImage.ImageSource.CAMERA_IMAGE) {
                        val photoFile = EasyImage
                            .lastlyTakenButCanceledPhoto(applicationContext)
                        photoFile?.delete()
                    }
                }
            })
    }

    private fun copyUriToLocalFile(uri: android.net.Uri): File? {
        return try {
            val input = contentResolver.openInputStream(uri) ?: return null
            val file = File.createTempFile("gallery_img", ".jpg", filesDir)
            input.use { it.copyTo(file.outputStream()) }
            file
        } catch (e: Exception) {
            timber.log.Timber.e(e, "Failed to copy gallery image")
            null
        }
    }

    private fun loadImages(imageFile: List<File>) {
        lifecycleScope.launch {
            val files = withContext(Dispatchers.IO) {
                imageFile.mapNotNull { file ->
                    try {
                        val internal = File.createTempFile("img", file.name, filesDir)
                        file.moveTo(internal)
                        internal.name
                    } catch (e: IOException) {
                        timber.log.Timber.e(e, "Failed to move image file")
                        null
                    }
                }
            }
            imageList.addAll(files)
            updateResult()
            invalidateOptionsMenu()
            previewAdapter.notifyDataSetChanged()
            adapter?.notifyDataSetChanged()
            val currentPos = imageList.size() - 1
            previewAdapter.setSelectedItem(currentPos)
            binding.pager.currentItem = currentPos
        }
    }

    private fun goToImage(pos: Int) {
        if (imageList.size() == pos) {
            if (PermissionUtils.hasCameraPermission(this)) {
                onTakePicture()
            } else {
                PermissionUtils.requestCameraPermission(this)
            }
        } else {
            binding.pager.setCurrentItem(pos, true)
        }
    }

    companion object {
        const val EXTRA_IMAGES = "images"
        const val EXTRA_TITLE = "title"
        private const val REQUEST_GALLERY_IMAGE = 7723

        fun getResult(data: Intent): ImageList {
            return data.getParcelableExtra(NavigationController.ITEM)!!
        }
    }
}
