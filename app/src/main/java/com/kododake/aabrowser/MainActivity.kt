/*
 * Copyright (C) 2025 AABrowser Contributors (https://github.com/kododake/AABrowser)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://gnu.org>.
 */

package com.kododake.aabrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Window
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.DynamicColors
import com.kododake.aabrowser.data.BrowserPreferences
import com.kododake.aabrowser.databinding.ActivityMainBinding
import com.kododake.aabrowser.main.BrowserHostContext
import com.kododake.aabrowser.main.BrowserShellController
import com.kododake.aabrowser.main.WebViewWarmupHelper
import kotlinx.coroutines.CoroutineScope

class MainActivity : AppCompatActivity(), BrowserHostContext {
    private lateinit var binding: ActivityMainBinding
    private lateinit var browserShell: BrowserShellController
    private var pendingPickBackgroundCallback: ((Uri?) -> Unit)? = null

    override val hostContext: Context get() = this
    override val hostActivity: AppCompatActivity get() = this
    override val hostWindow: Window? get() = window
    override val coroutineScope: CoroutineScope get() = lifecycleScope

    override fun finishHost() {
        finish()
    }

    override fun recreateHost() {
        recreate()
    }

    override fun launchPickBackground(onPicked: (Uri?) -> Unit) {
        pendingPickBackgroundCallback = onPicked
        pickStartPageBackgroundLauncher.launch(arrayOf("image/*"))
    }

    private val pickStartPageBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        pendingPickBackgroundCallback?.invoke(uri)
        pendingPickBackgroundCallback = null
        if (::browserShell.isInitialized) {
            browserShell.onStartPageBackgroundPicked(uri)
        }
    }

    override fun attachBaseContext(newBase: Context?) {
        val scaled = newBase?.let { BrowserPreferences.createScaledContext(it) }
        super.attachBaseContext(scaled ?: newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        WebViewWarmupHelper.warmup(this)
        DynamicColors.applyToActivityIfAvailable(this)
        AppCompatDelegate.setDefaultNightMode(BrowserPreferences.getThemeMode(this).nightMode)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        browserShell = BrowserShellController(
            hostContext = this,
            binding = binding
        )
        browserShell.initialize(intent, savedInstanceState != null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        browserShell.onNewIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        browserShell.onResume()
    }

    override fun onPause() {
        browserShell.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        browserShell.onDestroy()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        browserShell.onRequestPermissionsResult(requestCode, grantResults)
    }
}
