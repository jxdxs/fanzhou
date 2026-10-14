package com.kevin.fanzhou

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.kevin.fanzhou.data.Repo

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        com.kevin.fanzhou.data.ConfigSync.init(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        com.kevin.fanzhou.cloud.VideoThumbnails.trimMemory(level)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Repo.http)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .build()
}
