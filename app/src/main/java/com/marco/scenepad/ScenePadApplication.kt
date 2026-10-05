package com.marco.scenepad

import android.app.Application

class ScenePadApplication : Application() {
    val audioEngine: AudioEngine by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AudioEngine(applicationContext) }
}
