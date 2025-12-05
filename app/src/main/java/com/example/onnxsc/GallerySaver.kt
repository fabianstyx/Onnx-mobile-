package com.example.onnxsc

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import java.io.IOException

object GallerySaver {

    fun save(context: Context, bitmap: Bitmap, name: String, onLog: (String) -> Unit) {
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ONNX-SC")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri == null) {
                onLog("Error al crear URI para imagen")
                return
            }
            
            resolver.openOutputStream(uri)?.use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    onLog("Error al comprimir imagen")
                    return
                }
            } ?: run {
                onLog("Error al abrir stream de salida")
                return
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            
            onLog("Imagen guardada: $name")
        } catch (e: IOException) {
            onLog("Error IO al guardar: ${e.message}")
        } catch (e: SecurityException) {
            onLog("Error de permisos: ${e.message}")
        } catch (e: Exception) {
            onLog("Error al guardar: ${e.message}")
        }
    }
}
