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

package com.kododake.aabrowser.gecko

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.kododake.aabrowser.R
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/**
 * Full-screen Google sign-in on GeckoView. Started by GoogleSignInBridge when the WebView navigates to
 * accounts.google.com. Once Google redirects away from its account pages, the cookies are copied into the
 * WebView and this screen closes, returning to the tab that asked for sign-in.
 */
class GeckoSignInActivity : AppCompatActivity() {
    private lateinit var geckoView: GeckoView
    private lateinit var progressBar: ProgressBar
    private lateinit var finishingOverlay: TextView
    private lateinit var session: GeckoSession

    private var currentUrl: String? = null
    private var canGoBack = false
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())

        val startUrl = intent.getStringExtra(EXTRA_URL) ?: GoogleSignInBridge.YOUTUBE_SIGN_IN_URL
        session = GeckoSession(
            GeckoSessionSettings.Builder()
                .usePrivateMode(false)
                .userAgentMode(GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
                .build()
        )
        attachDelegates()
        session.open(GeckoEngine.runtime(this))
        geckoView.setSession(session)
        session.loadUri(startUrl)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (canGoBack) {
                    session.goBack()
                } else {
                    GoogleSignInBridge.cancel()
                    finish()
                }
            }
        })
    }

    override fun onDestroy() {
        geckoView.releaseSession()
        session.close()
        super.onDestroy()
    }

    /** Google's sign-in hops between accounts.google.com(.xx) and accounts.youtube.com before landing. */
    private fun isAccountsPage(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return true
        val host = uri.host?.lowercase() ?: return true
        if (uri.scheme != "https" && uri.scheme != "http") return true
        return host.startsWith("accounts.") || uri.path.orEmpty().lowercase().startsWith("/accounts/")
    }

    private fun maybeFinishSignIn(url: String?) {
        if (finishing || isAccountsPage(url)) return
        finishing = true
        finishingOverlay.visibility = View.VISIBLE
        Log.i(TAG, "Sign-in handing back to WebView at $url; copying cookies")
        GeckoEngine.requestCookies(this) { cookies ->
            if (cookies == null) {
                Toast.makeText(this, R.string.gecko_sign_in_failed, Toast.LENGTH_LONG).show()
                GoogleSignInBridge.cancel()
                finish()
                return@requestCookies
            }
            GoogleSignInBridge.complete(cookies, url) {
                Toast.makeText(this, R.string.gecko_sign_in_done, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    private fun attachDelegates() {
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                currentUrl = url
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                this@GeckoSignInActivity.canGoBack = canGoBack
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // Keep popups (help links, "create account") in this screen.
                session.loadUri(uri)
                return null
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                // The first top-level navigation that leaves Google's account pages is the hand-back: the
                // YouTube landing page, or a third-party OAuth redirect_uri carrying ?code=&state=. Don't load it
                // here; the WebView owns the requesting site's state cookies, so it must finish the flow.
                if (!finishing && !isAccountsPage(request.uri)) {
                    maybeFinishSignIn(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }
        }

        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onProgressChange(session: GeckoSession, progress: Int) {
                progressBar.progress = progress
                progressBar.visibility = if (progress in 1..99) View.VISIBLE else View.INVISIBLE
            }
        }

        session.promptDelegate = DialogPromptDelegate()
    }

    private fun buildLayout(): View {
        val density = resources.displayMetrics.density
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        geckoView = GeckoView(this)
        root.addView(geckoView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
        }
        root.addView(progressBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (3 * density).toInt(), Gravity.TOP))

        finishingOverlay = TextView(this).apply {
            text = getString(R.string.gecko_sign_in_finishing)
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#CC000000"))
            visibility = View.GONE
            isClickable = true
        }
        root.addView(finishingOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        return root
    }

    /** Dialogs for alert/confirm/prompt and <select>, which Gecko does not render on its own. */
    private inner class DialogPromptDelegate : PromptDelegate {
        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            AlertDialog.Builder(this@GeckoSignInActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            AlertDialog.Builder(this@GeckoSignInActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val result = GeckoResult<PromptDelegate.PromptResponse>()
            val input = EditText(this@GeckoSignInActivity).apply { setText(prompt.defaultValue) }
            AlertDialog.Builder(this@GeckoSignInActivity)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptDelegate.PromptResponse> {
            val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
            fun collect(choices: Array<PromptDelegate.ChoicePrompt.Choice>) {
                for (choice in choices) {
                    val items = choice.items
                    if (items != null) collect(items) else if (!choice.separator && !choice.disabled) flat.add(choice)
                }
            }
            collect(prompt.choices)
            if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())

            val result = GeckoResult<PromptDelegate.PromptResponse>()
            AlertDialog.Builder(this@GeckoSignInActivity)
                .setTitle(prompt.title)
                .setSingleChoiceItems(flat.map { it.label }.toTypedArray(), flat.indexOfFirst { it.selected }) { dialog, which ->
                    result.complete(prompt.confirm(flat[which]))
                    dialog.dismiss()
                }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }
    }

    companion object {
        const val EXTRA_URL = "url"
        private const val TAG = "GeckoSignInActivity"
    }
}
