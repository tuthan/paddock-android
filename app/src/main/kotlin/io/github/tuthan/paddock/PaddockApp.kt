package io.github.tuthan.paddock

import android.app.Application

class PaddockApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this).also { it.start() }
    }
}
