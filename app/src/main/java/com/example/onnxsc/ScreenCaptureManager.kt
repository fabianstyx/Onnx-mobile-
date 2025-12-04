package com.example.onnxsc

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import android.Manifest
import android.util.Log

object ScreenCaptureManager {

    private const val TAG = "ScreenCaptureManager"
    private const val REQUEST_CODE_CAPTURE = 1001

    fun startCapture(context: Context, onPermissionGranted: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "Permission not granted")
                return
            }
            val intent = mediaProjectionManager.createScreenCaptureIntent()
            context.startActivityForResult(intent, REQUEST_CODE_CAPTURE)
        } else {
            Log.e(TAG, "Screen capture not supported on this device")
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_CAPTURE) {
            if (resultCode == Activity.RESULT_OK) {
                // Handle the result here
                Log.d(TAG, "Screen capture permission granted")
            } else {
                Log.e(TAG, "Screen capture permission denied")
            }
        }
    }
}
