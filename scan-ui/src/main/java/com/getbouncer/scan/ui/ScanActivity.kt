package com.getbouncer.scan.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.getbouncer.scan.camera.CameraAdapter
import com.getbouncer.scan.camera.CameraErrorListener
import com.getbouncer.scan.camera.CameraPreviewImage
import com.getbouncer.scan.camera.getCameraAdapter
import com.getbouncer.scan.framework.Config
import com.getbouncer.scan.framework.Stats
import com.getbouncer.scan.framework.StorageFactory
import com.getbouncer.scan.framework.api.ERROR_CODE_NOT_AUTHENTICATED
import com.getbouncer.scan.framework.api.NetworkResult
import com.getbouncer.scan.framework.api.dto.ScanStatistics
import com.getbouncer.scan.framework.api.uploadScanStats
import com.getbouncer.scan.framework.api.validateApiKey
import com.getbouncer.scan.framework.util.AppDetails
import com.getbouncer.scan.framework.util.Device
import com.getbouncer.scan.framework.util.getAppPackageName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.parcelize.Parcelize
import kotlin.coroutines.CoroutineContext

const val PERMISSION_RATIONALE_SHOWN = "permission_rationale_shown"

interface ScanResultListener {

    /**
     * The user canceled the scan.
     */
    fun userCanceled(reason: CancellationReason)

    /**
     * The scan failed because of an error.
     */
    fun failed(cause: Throwable?)
}

sealed interface CancellationReason : Parcelable {

    @Parcelize
    object Closed : CancellationReason

    @Parcelize
    object Back : CancellationReason

    @Parcelize
    object UserCannotScan : CancellationReason

    @Parcelize
    object CameraPermissionDenied : CancellationReason
}

/**
 * A basic implementation that displays error messages when there is a problem with the camera.
 */
open class CameraErrorListenerImpl(
    protected val context: Context,
    protected val callback: (Throwable?) -> Unit
) : CameraErrorListener {
    override fun onCameraOpenError(cause: Throwable?) {
        showCameraError(R.string.bouncer_error_camera_open, cause)
    }

    override fun onCameraAccessError(cause: Throwable?) {
        showCameraError(R.string.bouncer_error_camera_access, cause)
    }

    override fun onCameraUnsupportedError(cause: Throwable?) {
        Log.e(Config.logTag, "Camera not supported", cause)
        showCameraError(R.string.bouncer_error_camera_unsupported, cause)
    }

    private fun showCameraError(@StringRes message: Int, cause: Throwable?) {
        AlertDialog.Builder(context)
            .setTitle(R.string.bouncer_error_camera_title)
            .setMessage(message)
            .setPositiveButton(R.string.bouncer_error_camera_acknowledge_button) { _, _ -> callback(cause) }
            .show()
    }
}

abstract class ScanActivity : AppCompatActivity(), CoroutineScope {
    companion object {
        const val PERMISSION_REQUEST_CODE = 1200
    }

    override val coroutineContext: CoroutineContext = Dispatchers.Main

    protected val scanStat = Stats.trackTask("scan_activity")
    private val permissionStat = Stats.trackTask("camera_permission")

    protected var isFlashlightOn: Boolean = false
        private set

    protected val cameraAdapter by lazy { buildCameraAdapter() }
    private val cameraErrorListener by lazy {
        CameraErrorListenerImpl(this) { t -> cameraErrorCancelScan(t) }
    }

    /**
     * The listener which will handle the results from the scan.
     */
    protected abstract val resultListener: ScanResultListener

    protected val storage by lazy {
        StorageFactory.getStorageInstance(this, "scan_camera_permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Stats.startScan()

        ensureValidApiKey()

        if (!CameraAdapter.isCameraSupported(this)) {
            showCameraNotSupportedDialog()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()

        launch {
            delay(1500)
            hideSystemUi()
        }

        if (!cameraAdapter.isBoundToLifecycle()) {
            ensurePermissionAndStartCamera()
        }
    }

    protected open fun hideSystemUi() {
        // Prevent screenshots and keep the screen on while scanning.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE + WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            WindowManager.LayoutParams.FLAG_SECURE + WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        // Hide both the navigation bar and the status bar. Allow system gestures to show the navigation and status bar,
        // but prevent the UI from resizing when they are shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(true)
        } else {
            @Suppress("deprecation")
            window.decorView.apply {
                systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            }
        }
    }

    override fun onPause() {
        super.onPause()
        setFlashlightState(false)
    }

    /**
     * Ensure that the camera permission is available. If so, start the camera. If not, request it.
     */
    protected open fun ensurePermissionAndStartCamera() = when {
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> {
            runBlocking { permissionStat.trackResult("already_granted") }
            prepareCamera { onCameraReady() }
        }
        ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA) -> showPermissionRationaleDialog()
        storage.getBoolean(PERMISSION_RATIONALE_SHOWN, false) -> showPermissionDeniedDialog()
        else -> requestCameraPermission()
    }

    /**
     * Handle permission status changes. If the camera permission has been granted, start it. If
     * not, show a dialog.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE && grantResults.isNotEmpty()) {
            when (grantResults[0]) {
                PackageManager.PERMISSION_GRANTED -> {
                    runBlocking { permissionStat.trackResult("granted") }
                    prepareCamera { onCameraReady() }
                }
                else -> {
                    runBlocking { permissionStat.trackResult("denied") }
                    cameraPermissionDenied()
                }
            }
        }
    }

    /**
     * Show a dialog explaining that the camera is not available.
     */
    protected open fun showCameraNotSupportedDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bouncer_error_camera_title)
            .setMessage(R.string.bouncer_error_camera_unsupported)
            .setPositiveButton(R.string.bouncer_error_camera_acknowledge_button) { _, _ -> cameraErrorCancelScan() }
            .show()
    }

    /**
     * Show an explanation dialog for why we are requesting camera permissions.
     */
    protected open fun showPermissionRationaleDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.bouncer_camera_permission_denied_message)
            .setPositiveButton(R.string.bouncer_camera_permission_denied_ok) { _, _ -> requestCameraPermission() }
        builder.show()
        storage.storeValue(PERMISSION_RATIONALE_SHOWN, true)
    }

    /**
     * Show an explanation dialog for why we are requesting camera permissions when the permission
     * has been permanently denied.
     */
    protected open fun showPermissionDeniedDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.bouncer_camera_permission_denied_message)
            .setPositiveButton(R.string.bouncer_camera_permission_denied_ok) { _, _ ->
                storage.storeValue(PERMISSION_RATIONALE_SHOWN, false)
                openAppSettings()
            }
            .setNegativeButton(R.string.bouncer_camera_permission_denied_cancel) { _, _ -> cameraPermissionDenied() }
        builder.show()
    }

    /**
     * Request permission to use the camera.
     */
    protected open fun requestCameraPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.CAMERA),
            PERMISSION_REQUEST_CODE,
        )
    }

    /**
     * Open the settings for this app
     */
    protected open fun openAppSettings() {
        val intent = Intent()
            .setAction(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", getAppPackageName(this), null))
        startActivity(intent)
    }

    /**
     * Validate the API key against the server. If it's invalid, close the scanner.
     */
    protected fun ensureValidApiKey() {
        if (Config.apiKey != null) {
            launch {
                when (val apiKeyValidateResult = validateApiKey(this@ScanActivity)) {
                    is NetworkResult.Success -> {
                        if (!apiKeyValidateResult.body.isApiKeyValid) {
                            Log.e(
                                Config.logTag,
                                "API key is invalid: ${apiKeyValidateResult.body.keyInvalidReason}"
                            )
                            onInvalidApiKey()
                            showApiKeyInvalidError()
                        }
                    }
                    is NetworkResult.Error -> {
                        if (apiKeyValidateResult.error.errorCode == ERROR_CODE_NOT_AUTHENTICATED) {
                            Log.e(
                                Config.logTag,
                                "API key is invalid: ${apiKeyValidateResult.error.errorMessage}"
                            )
                            onInvalidApiKey()
                            showApiKeyInvalidError()
                        } else {
                            Log.w(
                                Config.logTag,
                                "Unable to validate API key: ${apiKeyValidateResult.error.errorMessage}"
                            )
                        }
                    }
                    is NetworkResult.Exception -> Log.w(
                        Config.logTag,
                        "Unable to validate API key",
                        apiKeyValidateResult.exception
                    )
                }
            }
        }
    }

    protected open fun showApiKeyInvalidError() {
        AlertDialog.Builder(this)
            .setTitle(R.string.bouncer_api_key_invalid_title)
            .setMessage(R.string.bouncer_api_key_invalid_message)
            .setPositiveButton(R.string.bouncer_api_key_invalid_ok) { _, _ -> userCancelScan() }
            .setCancelable(false)
            .show()
    }

    /**
     * Turn the flashlight on or off.
     */
    protected open fun toggleFlashlight() {
        isFlashlightOn = !isFlashlightOn
        setFlashlightState(isFlashlightOn)
        runBlocking { Stats.trackRepeatingTask("torch_state").trackResult(if (isFlashlightOn) "on" else "off") }
    }

    /**
     * Toggle between available cameras.
     */
    protected open fun toggleCamera() {
        cameraAdapter.changeCamera()
        runBlocking { Stats.trackRepeatingTask("swap_camera").trackResult("${cameraAdapter.getCurrentCamera()}") }
    }

    /**
     * Called when the flashlight state has changed.
     */
    protected abstract fun onFlashlightStateChanged(flashlightOn: Boolean)

    /**
     * Turn the flashlight on or off.
     */
    private fun setFlashlightState(on: Boolean) {
        cameraAdapter.setTorchState(on)
        isFlashlightOn = on
        onFlashlightStateChanged(on)
    }

    /**
     * Cancel scanning due to a camera error.
     */
    protected open fun cameraErrorCancelScan(cause: Throwable? = null) {
        Log.e(Config.logTag, "Canceling scan due to camera error", cause)
        runBlocking { scanStat.trackResult("camera_error") }
        resultListener.failed(cause)
        closeScanner()
    }

    /**
     * The scan has been cancelled by the user.
     */
    protected open fun userCancelScan() {
        runBlocking { scanStat.trackResult("user_canceled") }
        resultListener.userCanceled(CancellationReason.Closed)
        closeScanner()
    }

    protected open fun cameraPermissionDenied() {
        runBlocking { scanStat.trackResult("user_canceled") }
        resultListener.userCanceled(CancellationReason.CameraPermissionDenied)
        closeScanner()
    }

    /**
     * Cancel scanning due to analyzer failure
     */
    protected open fun analyzerFailureCancelScan(cause: Throwable? = null) {
        Log.e(Config.logTag, "Canceling scan due to analyzer error", cause)
        runBlocking { scanStat.trackResult("analyzer_failure") }
        resultListener.failed(cause)
        closeScanner()
    }

    /**
     * Close the scanner.
     */
    protected open fun closeScanner() {
        setFlashlightState(false)
        if (Config.uploadStats) {
            uploadStats(
                instanceId = Stats.instanceId,
                scanId = Stats.scanId,
                device = Device.fromContext(this),
                appDetails = AppDetails.fromContext(this),
                scanStatistics = ScanStatistics.fromStats(),
            )
        }
        finish()
    }

    /**
     * Upload stats to the bouncer servers. Override this to perform some other action.
     */
    protected open fun uploadStats(
        instanceId: String,
        scanId: String?,
        device: Device,
        appDetails: AppDetails,
        scanStatistics: ScanStatistics
    ) {
        uploadScanStats(
            context = this,
            instanceId = instanceId,
            scanId = scanId,
            device = device,
            appDetails = appDetails,
            scanStatistics = scanStatistics
        )
    }

    /**
     * Prepare to start the camera. Once the camera is ready, [onCameraReady] must be called.
     */
    protected abstract fun prepareCamera(onCameraReady: () -> Unit)

    protected open fun onCameraReady() {
        cameraAdapter.bindToLifecycle(this)

        val torchStat = Stats.trackTask("torch_supported")
        cameraAdapter.withFlashSupport {
            runBlocking { torchStat.trackResult(if (it) "supported" else "unsupported") }
            setFlashlightState(cameraAdapter.isTorchOn())
            onFlashSupported(it)
        }

        val cameraStat = Stats.trackTask("multiple_cameras_supported")
        cameraAdapter.withSupportsMultipleCameras {
            runBlocking { cameraStat.trackResult(if (it) "supported" else "unsupported") }
            onSupportsMultipleCameras(it)
        }

        onCameraStreamAvailable(cameraAdapter.getImageStream())
    }

    /**
     * Perform an action when the flash is supported
     */
    protected abstract fun onFlashSupported(supported: Boolean)

    /**
     * Perform an action when the camera support is determined
     */
    protected abstract fun onSupportsMultipleCameras(supported: Boolean)

    protected open fun setFocus(point: PointF) {
        cameraAdapter.setFocus(point)
    }

    /**
     * Cancel the scan when the user presses back.
     */
    override fun onBackPressed() {
        runBlocking { scanStat.trackResult("user_canceled") }
        resultListener.userCanceled(CancellationReason.Back)
        closeScanner()
    }

    /**
     * Generate a camera adapter
     */
    protected open fun buildCameraAdapter(): CameraAdapter<CameraPreviewImage<Bitmap>> =
        getCameraAdapter(
            activity = this,
            previewView = previewFrame,
            minimumResolution = minimumAnalysisResolution,
            cameraErrorListener = cameraErrorListener,
        )

    protected abstract val previewFrame: ViewGroup

    protected abstract val minimumAnalysisResolution: Size

    /**
     * A stream of images from the camera is available to be processed.
     */
    protected abstract fun onCameraStreamAvailable(cameraStream: Flow<CameraPreviewImage<Bitmap>>)

    /**
     * The API key was invalid.
     */
    protected abstract fun onInvalidApiKey()
}
