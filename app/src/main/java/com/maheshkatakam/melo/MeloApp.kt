package com.maheshkatakam.melo

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory

class MeloApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MeloApp).container
