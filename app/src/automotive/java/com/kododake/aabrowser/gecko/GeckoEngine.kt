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

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kododake.aabrowser.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension

/**
 * Process-wide GeckoView runtime: every tab on the automotive build (GeckoPage) and the Google sign-in screen.
 * GeckoRuntime.create may only be called once per process.
 *
 * Gecko keeps its own cookie jar, so a bundled extension ("cookie_bridge") reads the cookies after sign-in and
 * sends them here over a native-messaging port; GoogleSignInBridge then copies them into the WebView.
 */
object GeckoEngine {
    private const val TAG = "GeckoEngine"
    private const val COOKIE_BRIDGE_URI = "resource://android/assets/extensions/cookie_bridge/"
    private const val COOKIE_BRIDGE_ID = "cookie-bridge@motion.carlosrpg"
    private const val NATIVE_APP = "motionCookies"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var runtime: GeckoRuntime? = null
    private var port: WebExtension.Port? = null
    private var nextRequestId = 0
    private val pending = HashMap<Int, (JSONObject?) -> Unit>()
    private val waitingForPort = ArrayList<() -> Unit>()

    fun runtime(context: Context): GeckoRuntime {
        runtime?.let { return it }
        return synchronized(this) {
            runtime ?: create(context.applicationContext).also { runtime = it }
        }
    }

    private fun create(context: Context): GeckoRuntime {
        val settings = GeckoRuntimeSettings.Builder()
            .consoleOutput(BuildConfig.DEBUG)
            .remoteDebuggingEnabled(BuildConfig.DEBUG)
            .build()
        val created = GeckoRuntime.create(context, settings)
        installCookieBridge(created)
        installPageBridge(created)
        return created
    }

    // Page bridge (assets/extensions/page_bridge): GeckoPage's evaluateJavascript, and document-start scripts.
    private const val PAGE_BRIDGE_URI = "resource://android/assets/extensions/page_bridge/"
    private const val PAGE_BRIDGE_ID = "page-bridge@motion.carlosrpg"
    private const val PAGE_SCRIPTS_APP = "motionPageScripts"
    const val PAGE_APP = "motionPage"

    private var pageBridge: WebExtension? = null
    private val pageBridgeWaiters = ArrayList<(WebExtension) -> Unit>()
    private var scriptsPort: WebExtension.Port? = null
    private val startupScripts = LinkedHashMap<String, Pair<String, List<String>>>()

    /** Runs [action] with the page bridge once it is installed (right away after the first time). */
    fun withPageBridge(context: Context, action: (WebExtension) -> Unit) {
        runtime(context)
        pageBridge?.let(action) ?: pageBridgeWaiters.add(action)
    }

    /** Like WebView's addDocumentStartJavaScript, for every Gecko tab; null [code] removes it. */
    fun setStartupScript(context: Context, key: String, code: String?, matches: List<String>) {
        runtime(context)
        if (code == null) startupScripts.remove(key) else startupScripts[key] = code to matches
        scriptsPort?.postMessage(startupScriptMessage(key))
    }

    private fun startupScriptMessage(key: String): JSONObject {
        val script = startupScripts[key] ?: return JSONObject().put("type", "unregister").put("key", key)
        return JSONObject().put("type", "register").put("key", key)
            .put("code", script.first).put("matches", JSONArray(script.second))
    }

    private fun installPageBridge(geckoRuntime: GeckoRuntime) {
        geckoRuntime.webExtensionController.ensureBuiltIn(PAGE_BRIDGE_URI, PAGE_BRIDGE_ID).accept({ extension ->
            extension ?: return@accept
            extension.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    scriptsPort = newPort
                    newPort.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, port: WebExtension.Port) {
                            Log.w(TAG, "Page script registration failed: $message")
                        }

                        override fun onDisconnect(port: WebExtension.Port) {
                            if (scriptsPort === port) scriptsPort = null
                        }
                    })
                    // The background page (re)started: give it every script again.
                    startupScripts.keys.forEach { newPort.postMessage(startupScriptMessage(it)) }
                }
            }, PAGE_SCRIPTS_APP)
            pageBridge = extension
            val waiters = ArrayList(pageBridgeWaiters)
            pageBridgeWaiters.clear()
            waiters.forEach { it(extension) }
        }, { error ->
            Log.e(TAG, "Failed to install page bridge", error)
        })
    }

    private fun installCookieBridge(geckoRuntime: GeckoRuntime) {
        geckoRuntime.webExtensionController.ensureBuiltIn(COOKIE_BRIDGE_URI, COOKIE_BRIDGE_ID).accept({ extension ->
            extension?.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(newPort: WebExtension.Port) {
                    Log.i(TAG, "Cookie bridge connected")
                    newPort.setDelegate(portDelegate)
                    port = newPort
                    val waiters = ArrayList(waitingForPort)
                    waitingForPort.clear()
                    waiters.forEach { it() }
                }
            }, NATIVE_APP)
        }, { error ->
            Log.e(TAG, "Failed to install cookie bridge", error)
        })
    }

    private val portDelegate = object : WebExtension.PortDelegate {
        override fun onPortMessage(message: Any, port: WebExtension.Port) {
            val json = message as? JSONObject ?: return
            val callback = pending.remove(json.optInt("id", -1)) ?: return
            if (json.optString("type") == "error") {
                Log.w(TAG, "Cookie bridge error: ${json.optString("message")}")
                callback(null)
            } else {
                callback(json)
            }
        }

        override fun onDisconnect(port: WebExtension.Port) {
            if (this@GeckoEngine.port === port) this@GeckoEngine.port = null
        }
    }

    /** Reads every cookie in Gecko's jar. Calls back on the main thread with null on failure or timeout. */
    fun requestCookies(context: Context, timeoutMs: Long = 8_000, callback: (JSONArray?) -> Unit) =
        request(context, JSONObject().put("type", "dump"), timeoutMs) { callback(it?.optJSONArray("cookies")) }

    /** Seeds Gecko with the WebView's cookies ([url, name, value] objects). Calls back once done, failed or timed out. */
    fun importCookies(context: Context, cookies: JSONArray, timeoutMs: Long = 5_000, callback: () -> Unit) =
        request(context, JSONObject().put("type", "import").put("cookies", cookies), timeoutMs) { reply ->
            Log.i(TAG, "Seeded Gecko from ${cookies.length()} WebView cookies: ${reply?.optInt("count") ?: "timed out"} updated")
            callback()
        }

    private fun request(context: Context, message: JSONObject, timeoutMs: Long, callback: (JSONObject?) -> Unit) {
        runtime(context)
        var done = false
        val finish: (JSONObject?) -> Unit = { result ->
            if (!done) {
                done = true
                callback(result)
            }
        }
        val send = {
            val id = nextRequestId++
            pending[id] = finish
            port?.postMessage(message.put("id", id)) ?: finish(null)
        }
        if (port != null) send() else waitingForPort.add(send)
        mainHandler.postDelayed({ finish(null) }, timeoutMs)
    }

    /** Signs Gecko out too, so clearing cookies in Settings does not leave a session behind. */
    fun clearAllData(context: Context): GeckoResult<Void> =
        runtime(context).storageController.clearData(StorageController.ClearFlags.ALL)
}
