package com.ab.assistant.tools

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

class FlashlightController(context: Context) {
    private val appContext = context.applicationContext
    private val cameraManager = appContext.getSystemService(CameraManager::class.java)

    fun isAvailable(): Boolean = appContext.packageManager.hasSystemFeature(
        PackageManager.FEATURE_CAMERA_FLASH,
    )

    fun execute(command: ToolCommand): String {
        if (!isAvailable()) {
            return "ERROR: This device has no camera flash."
        }

        return try {
            val cameraId = findFlashlightCameraId()
                ?: return "ERROR: No flashlight-capable camera was found."
            val enabled = command == ToolCommand.FlashlightOn
            cameraManager.setTorchMode(cameraId, enabled)
            if (enabled) "Flashlight turned ON." else "Flashlight turned OFF."
        } catch (exception: SecurityException) {
            "ERROR: Camera permission is required to control the flashlight."
        } catch (exception: Exception) {
            "ERROR: Could not change flashlight state."
        }
    }

    private fun findFlashlightCameraId(): String? = cameraManager.cameraIdList.firstOrNull { cameraId ->
        val camera = cameraManager.getCameraCharacteristics(cameraId)
        camera.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
            camera.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
    }
}
