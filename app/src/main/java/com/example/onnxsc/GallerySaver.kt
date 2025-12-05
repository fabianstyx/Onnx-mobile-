package com.example.onnxsc

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

object GallerySaver {

    fun save(bitmap: Bitmap, name: String, onLog: (String) -> Unit) {
        try {
            val resolver = Logger.console.context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ONNX-SC")
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                resolver.openOutputStream(it)?.use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                onLog("Imagen guardada: ${uri.path}")
            } ?: onLog("Error al crear URI")
        } catch (e: IOException) {
            onLog("Error al guardar: ${e.message}")
        }
    }
}
