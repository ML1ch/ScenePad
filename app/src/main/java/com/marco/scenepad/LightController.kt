package com.marco.scenepad

import android.content.Context

interface LightController {
    val isConnected: Boolean
    suspend fun connect(context: Context): Result<Unit>
    suspend fun execute(action: LightAction): Result<Unit>
    fun disconnect()
}

/**
 * Placeholder used by the MVP. The rest of the app already treats lighting as a first-class action.
 * Replace this implementation with GoogleHomeLightController after registering a Home APIs project
 * and configuring user authorization in Google Home Developer Console.
 */
class DemoLightController : LightController {
    override var isConnected: Boolean = false
        private set

    override suspend fun connect(context: Context): Result<Unit> {
        isConnected = true
        return Result.success(Unit)
    }

    override suspend fun execute(action: LightAction): Result<Unit> {
        if (!action.enabled) return Result.success(Unit)
        return if (isConnected) Result.success(Unit)
        else Result.failure(IllegalStateException("Iluminação ainda não conectada"))
    }

    override fun disconnect() { isConnected = false }
}
