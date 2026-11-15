package com.wmods.cinefy.enhance

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

/**
 * Cinefy's player is an HTML page inside react-native-webview. When the video enters fullscreen,
 * the WebView calls `onShowCustomView` on RNC's WebChromeClient; on exit, `onHideCustomView`.
 *
 * - Entering fullscreen: force sensor landscape and set FLAG_KEEP_SCREEN_ON.
 * - Leaving fullscreen: clear the flag and let RNC restore the original orientation (portrait).
 * - While in fullscreen, any other orientation request (e.g. expo-screen-orientation locking
 *   to portrait from JS) is replaced with landscape.
 */
class CinefyModule : XposedModule() {

    @Volatile
    private var fullscreenActivity: Activity? = null

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE || !param.isFirstPackage) return
        val cl = param.classLoader

        val chromeClient = runCatching { cl.loadClass(RNC_CHROME_CLIENT) }.getOrElse {
            log(Log.ERROR, TAG, "RNC WebChromeClient class not found", it)
            return
        }

        hookShowCustomView(chromeClient)
        hookHideCustomView(chromeClient)
        hookSetRequestedOrientation()
        log(Log.INFO, TAG, "Hooks installed in $TARGET_PACKAGE")
    }

    private fun hookShowCustomView(chromeClient: Class<*>) {
        val method = chromeClient.getDeclaredMethod(
            "onShowCustomView", View::class.java, WebChromeClient.CustomViewCallback::class.java
        )
        hook(method).intercept { chain ->
            val result = chain.proceed()
            val view = chain.getArg(0) as View
            val activity = findActivity(chain.thisObject, view) ?: run {
                log(Log.WARN, TAG, "onShowCustomView: Activity not found")
                return@intercept result
            }
            fullscreenActivity = activity
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            view.keepScreenOn = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            result
        }
    }

    private fun hookHideCustomView(chromeClient: Class<*>) {
        val method = chromeClient.getDeclaredMethod("onHideCustomView")
        hook(method).intercept { chain ->
            val activity = fullscreenActivity ?: findActivity(chain.thisObject, null)
            fullscreenActivity = null
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            chain.proceed()
        }
    }

    private fun hookSetRequestedOrientation() {
        val method = Activity::class.java.getDeclaredMethod(
            "setRequestedOrientation", Int::class.javaPrimitiveType
        )
        hook(method).intercept { chain ->
            val active = fullscreenActivity
            val requested = chain.getArg(0) as Int
            if (active != null && chain.thisObject === active && !isLandscape(requested)) {
                chain.proceed(arrayOf<Any>(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE))
            } else {
                chain.proceed()
            }
        }
    }

    private fun isLandscape(orientation: Int) = when (orientation) {
        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
        ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE -> true
        else -> false
    }

    /** Uses the `$activity` field captured by the anonymous class; falls back to walking the Context chain. */
    private fun findActivity(client: Any?, view: View?): Activity? {
        client?.let { obj ->
            runCatching {
                obj.javaClass.getDeclaredField($$"$activity").apply { isAccessible = true }.get(obj)
            }.getOrNull()?.let { if (it is Activity) return it }
        }
        var ctx: Context? = view?.context
        while (ctx is ContextWrapper) {
            if (ctx is Activity) return ctx
            ctx = ctx.baseContext
        }
        return null
    }

    companion object {
        private const val TAG = "CinefyEnhancer"
        private const val TARGET_PACKAGE = "com.smarttbr.cinefy"
        private const val RNC_CHROME_CLIENT =
            $$"com.reactnativecommunity.webview.RNCWebViewManagerImpl$setupWebChromeClient$webChromeClient$1"
    }
}
