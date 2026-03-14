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

package de.dreier.mytargets.utils

import android.view.View
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import de.dreier.mytargets.R
import timber.log.Timber

object ToolbarUtils {
    private const val EXTRA_TOP_SPACE_DP = 8
    private const val EXTRA_BOTTOM_SPACE_DP = 12
    private const val EXTRA_SCROLL_SPACE_DP = 24

    private fun dpToPx(view: View, dp: Int): Int {
        val density = view.resources.displayMetrics.density
        return (dp * density).toInt()
    }
    
    /**
     * Apply window insets to toolbar for edge-to-edge display
     * Call this after setSupportActionBar
     */
    fun applyWindowInsets(toolbar: Toolbar) {
        // Store original padding
        val originalPaddingLeft = toolbar.paddingLeft
        val originalPaddingRight = toolbar.paddingRight
        val originalPaddingBottom = toolbar.paddingBottom
        
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val extraTop = dpToPx(view, EXTRA_TOP_SPACE_DP)
            
            Timber.d("Applying window insets to toolbar - Top: ${insets.top}")
            
            // Apply top inset as padding to toolbar
            view.setPadding(
                originalPaddingLeft,
                insets.top + extraTop,
                originalPaddingRight,
                originalPaddingBottom
            )
            
            // Don't consume - let other views handle insets too
            windowInsets
        }
        
        // Request insets when view is attached
        if (toolbar.isAttachedToWindow) {
            requestInsetsForView(toolbar)
        } else {
            toolbar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    requestInsetsForView(v)
                    v.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }
    
    /**
     * Apply window insets to a bottom view (like bottom navigation, button bar, or FAB)
     * This adds padding/margin so the view is above the navigation bar (soft keys)
     */
    fun applyWindowInsetsToBottom(view: View) {
        // Store original values
        val originalPaddingBottom = view.paddingBottom
        val originalPaddingLeft = view.paddingLeft
        val originalPaddingRight = view.paddingRight
        val originalPaddingTop = view.paddingTop
        val originalMarginBottom = (view.layoutParams as? android.view.ViewGroup.MarginLayoutParams)?.bottomMargin ?: 0
        
        // Determine if this is a FAB (use margin) or other view (use padding)
        val isFab = view is com.google.android.material.floatingactionbutton.FloatingActionButton
        
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val extraBottom = dpToPx(v, EXTRA_BOTTOM_SPACE_DP)
            
            Timber.d("Applying bottom window insets - Bottom: ${navInsets.bottom}, isFab: $isFab, view: ${v.javaClass.simpleName}")
            
            if (isFab) {
                // For FABs, use margin so they float above the nav bar
                val lp = v.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                if (lp != null) {
                    lp.bottomMargin = originalMarginBottom + navInsets.bottom + extraBottom
                    v.layoutParams = lp
                }
            } else {
                // For other views like button bars, use padding
                v.setPadding(
                    originalPaddingLeft,
                    originalPaddingTop,
                    originalPaddingRight,
                    originalPaddingBottom + navInsets.bottom + extraBottom
                )
            }
            
            // Don't consume insets so other views can use them
            windowInsets
        }
        
        // Request insets when view is attached and ready
        if (view.isAttachedToWindow) {
            requestInsetsForView(view)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    requestInsetsForView(v)
                    v.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }
    
    private fun requestInsetsForView(view: View) {
        // Use post to ensure layout is complete, then request insets from decor view
        view.post {
            view.context?.let { context ->
                if (context is AppCompatActivity) {
                    ViewCompat.requestApplyInsets(context.window.decorView)
                    Timber.d("Requested insets from decor view for ${view.javaClass.simpleName}")
                } else {
                    ViewCompat.requestApplyInsets(view)
                }
            } ?: ViewCompat.requestApplyInsets(view)
        }
    }
    
    /**
     * Apply window insets to a scrollable container (like NestedScrollView or RecyclerView)
     * This adds bottom padding so content can scroll above the navigation bar (soft keys)
     */
    fun applyWindowInsetsToScrollableContent(view: View) {
        // Store original padding
        val originalPaddingBottom = view.paddingBottom
        val originalPaddingLeft = view.paddingLeft
        val originalPaddingRight = view.paddingRight
        val originalPaddingTop = view.paddingTop
        
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val navInsets = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val extraBottom = dpToPx(v, EXTRA_SCROLL_SPACE_DP)
            val safeBottomInset = maxOf(navInsets.bottom, imeInsets.bottom)
            
            Timber.d("Applying scroll content window insets - Bottom: $safeBottomInset")
            
            v.setPadding(
                originalPaddingLeft,
                originalPaddingTop,
                originalPaddingRight,
                originalPaddingBottom + safeBottomInset + extraBottom
            )
            
            // For scrollable views, disable clip to padding so content can scroll behind nav bar initially
            if (v is androidx.recyclerview.widget.RecyclerView) {
                v.clipToPadding = false
            } else if (v is androidx.core.widget.NestedScrollView) {
                v.clipToPadding = false
            } else if (v is android.widget.ScrollView) {
                v.clipToPadding = false
            }
            
            windowInsets
        }
        
        // Request insets when view is attached
        if (view.isAttachedToWindow) {
            requestInsetsForView(view)
        } else {
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    requestInsetsForView(v)
                    v.removeOnAttachStateChangeListener(this)
                }
                override fun onViewDetachedFromWindow(v: View) {}
            })
        }
    }

    fun showUpAsX(fragment: Fragment) {
        showUpAsX(fragment.requireActivity() as AppCompatActivity)
    }

    private fun showUpAsX(activity: AppCompatActivity) {
        val supportActionBar = activity.supportActionBar!!
        supportActionBar.setDisplayHomeAsUpEnabled(true)
        supportActionBar.setHomeAsUpIndicator(R.drawable.ic_close_white_24dp)
    }

    fun showHomeAsUp(fragment: Fragment) {
        showHomeAsUp(fragment.requireActivity() as AppCompatActivity)
    }

    fun showHomeAsUp(activity: AppCompatActivity) {
        val supportActionBar = activity.supportActionBar!!
        supportActionBar.setDisplayHomeAsUpEnabled(true)
    }

    fun setSupportActionBar(fragment: Fragment, toolbar: Toolbar) {
        val activity = fragment.requireActivity() as AppCompatActivity
        activity.setSupportActionBar(toolbar)
        
        // If the activity is SimpleFragmentActivityBase, hide its toolbar since fragment has its own
        if (activity is de.dreier.mytargets.base.activities.SimpleFragmentActivityBase) {
            activity.hideActivityToolbar()
            // Edge-to-edge is already enabled in SimpleFragmentActivityBase.onCreate()
        }
        
        // Automatically apply window insets for edge-to-edge display
        Timber.d("setSupportActionBar called for ${fragment.javaClass.simpleName} - applying insets")
        applyWindowInsets(toolbar)
        
        // Force a fresh inset dispatch to all views after toolbar is set up
        // This ensures FABs and other views get the correct insets for soft navigation keys
        toolbar.post {
            activity.window?.decorView?.let { decorView ->
                ViewCompat.requestApplyInsets(decorView)
            }
        }
    }

    fun setTitle(fragment: Fragment, @StringRes title: Int) {
        setTitle(fragment.requireActivity() as AppCompatActivity, title)
    }

    fun setTitle(fragment: Fragment, title: String) {
        setTitle(fragment.requireActivity() as AppCompatActivity, title)
    }

    fun setTitle(activity: AppCompatActivity, @StringRes title: Int) {
        assert(activity.supportActionBar != null)
        activity.supportActionBar!!.setTitle(title)
    }

    fun setTitle(activity: AppCompatActivity, title: String) {
        assert(activity.supportActionBar != null)
        activity.supportActionBar!!.title = title
    }

    fun setSubtitle(fragment: Fragment, subtitle: String) {
        val activity = fragment.requireActivity() as AppCompatActivity
        setSubtitle(activity, subtitle)
    }

    fun setSubtitle(activity: AppCompatActivity, subtitle: String) {
        assert(activity.supportActionBar != null)
        activity.supportActionBar!!.subtitle = subtitle
    }
}
