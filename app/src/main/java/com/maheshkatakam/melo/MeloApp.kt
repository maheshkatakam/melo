package com.maheshkatakam.melo

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory

class MeloApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        container = AppContainer(this)
    }

    companion object {
        @Volatile
        private var startedActivities = 0

        /** True while Melo is on screen. Android only lets apps start background work then. */
        val isInForeground: Boolean get() = startedActivities > 0
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MeloApp).container
