package com.example.onnxsc

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

class ModelSwitcher(activity: ComponentActivity) {

    private var onSelected: ((Uri) -> Unit)? = null
    private val launcher: ActivityResultLauncher<Array<String>>

    init {
        launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let {
                try {
                    activity.contentResolver.takePersistableUriPermission(
                        it, 
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (e: SecurityException) {
                    Logger.error("No se pudo obtener permiso persistente: ${e.message}")
                }
                onSelected?.invoke(it)
            } ?: run {
                Logger.info("Selección de modelo cancelada")
            }
        }
    }

    fun pick(newOnSelected: (Uri) -> Unit) {
        onSelected = newOnSelected
        try {
            launcher.launch(arrayOf("application/octet-stream", "*/*"))
        } catch (e: Exception) {
            Logger.error("Error al abrir selector: ${e.message}")
        }
    }
}
