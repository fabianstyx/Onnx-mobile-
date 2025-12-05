package com.example.onnxsc

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class ModelSwitcher(private val activity: ComponentActivity) {

    private var onSelected: ((Uri) -> Unit)? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let {
            activity.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            onSelected?.invoke(it)
        }
    }

    fun pick(newOnSelected: (Uri) -> Unit) {
        onSelected = newOnSelected
        launcher.launch(arrayOf("*/*"))
    }
}
