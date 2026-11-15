package com.nexus.offline

import android.app.Application

class NexusApp : Application() {
    companion object {
        lateinit var engine: MeshEngine
            private set
    }

    override fun onCreate() {
        super.onCreate()
        engine = MeshEngine(this)
    }
}
