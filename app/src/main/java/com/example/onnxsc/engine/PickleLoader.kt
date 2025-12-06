package com.example.onnxsc.engine

import android.content.Context
import net.razorvine.pickle.Unpickler
import java.io.File
import java.io.FileInputStream
import java.io.IOException

sealed class PickleValue {
    data class PInt(val value: Long) : PickleValue()
    data class PFloat(val value: Double) : PickleValue()
    data class PString(val value: String) : PickleValue()
    data class PBool(val value: Boolean) : PickleValue()
    data class PList(val value: List<PickleValue>) : PickleValue()
    data class PMap(val value: Map<String, PickleValue>) : PickleValue()
    data class PFloatArray(val value: FloatArray) : PickleValue()
    data class PIntArray(val value: IntArray) : PickleValue()
    data class PBytes(val value: ByteArray) : PickleValue()
    object PNone : PickleValue()
    data class PUnknown(val rawType: String, val raw: Any?) : PickleValue()
}

sealed class PickleResult {
    data class Success(val data: PickleValue, val filePath: String) : PickleResult()
    data class Error(val message: String, val exception: Exception? = null) : PickleResult()
}

object PickleLoader {
    
    private const val TAG = "PickleLoader"
    private const val PICKLE_DIR = "pickle_data"
    
    private val loadedFiles = mutableMapOf<String, PickleValue>()
    private val fileTimestamps = mutableMapOf<String, Long>()
    private val listeners = mutableListOf<(String, PickleValue) -> Unit>()
    
    private var appContext: Context? = null
    
    fun init(context: Context) {
        appContext = context.applicationContext
        val pickleDir = File(context.filesDir, PICKLE_DIR)
        if (!pickleDir.exists()) {
            pickleDir.mkdirs()
        }
    }
    
    fun loadFromPath(filePath: String): PickleResult {
        val file = File(filePath)
        
        if (!file.exists()) {
            return PickleResult.Error("Archivo no encontrado: $filePath")
        }
        
        if (!file.canRead()) {
            return PickleResult.Error("No se puede leer el archivo: $filePath")
        }
        
        if (!filePath.endsWith(".pkl") && !filePath.endsWith(".pickle")) {
            return PickleResult.Error("Extension no valida. Use .pkl o .pickle")
        }
        
        return try {
            FileInputStream(file).use { inputStream ->
                val unpickler = Unpickler()
                val rawData = unpickler.load(inputStream)
                
                val converted = convertToPickleValue(rawData)
                
                loadedFiles[filePath] = converted
                fileTimestamps[filePath] = file.lastModified()
                
                notifyListeners(filePath, converted)
                
                PickleResult.Success(converted, filePath)
            }
        } catch (e: IOException) {
            PickleResult.Error("Error de IO leyendo pickle: ${e.message}", e)
        } catch (e: Exception) {
            PickleResult.Error("Error deserializando pickle: ${e.message}", e)
        }
    }
    
    fun loadFromAssets(context: Context, assetName: String): PickleResult {
        return try {
            val tempFile = File(context.cacheDir, "temp_${assetName}")
            
            context.assets.open(assetName).use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            
            val result = loadFromPath(tempFile.absolutePath)
            
            tempFile.delete()
            
            result
        } catch (e: IOException) {
            PickleResult.Error("Error cargando asset: ${e.message}", e)
        }
    }
    
    fun loadFromInternalStorage(fileName: String): PickleResult {
        val context = appContext ?: return PickleResult.Error("PickleLoader no inicializado")
        val file = File(File(context.filesDir, PICKLE_DIR), fileName)
        return loadFromPath(file.absolutePath)
    }
    
    fun copyToInternalStorage(sourcePath: String, destFileName: String): Boolean {
        val context = appContext ?: return false
        
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) return false
            
            val destDir = File(context.filesDir, PICKLE_DIR)
            if (!destDir.exists()) destDir.mkdirs()
            
            val destFile = File(destDir, destFileName)
            sourceFile.copyTo(destFile, overwrite = true)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    private fun convertToPickleValue(obj: Any?): PickleValue {
        return when (obj) {
            null -> PickleValue.PNone
            is Boolean -> PickleValue.PBool(obj)
            is Int -> PickleValue.PInt(obj.toLong())
            is Long -> PickleValue.PInt(obj)
            is Short -> PickleValue.PInt(obj.toLong())
            is Byte -> PickleValue.PInt(obj.toLong())
            is Float -> PickleValue.PFloat(obj.toDouble())
            is Double -> PickleValue.PFloat(obj)
            is String -> PickleValue.PString(obj)
            is ByteArray -> PickleValue.PBytes(obj)
            is FloatArray -> PickleValue.PFloatArray(obj)
            is DoubleArray -> PickleValue.PFloatArray(obj.map { it.toFloat() }.toFloatArray())
            is IntArray -> PickleValue.PIntArray(obj)
            is LongArray -> PickleValue.PIntArray(obj.map { it.toInt() }.toIntArray())
            is Array<*> -> {
                val list = obj.map { convertToPickleValue(it) }
                PickleValue.PList(list)
            }
            is List<*> -> {
                val list = obj.map { convertToPickleValue(it) }
                PickleValue.PList(list)
            }
            is Set<*> -> {
                val list = obj.map { convertToPickleValue(it) }
                PickleValue.PList(list)
            }
            is Map<*, *> -> {
                val map = mutableMapOf<String, PickleValue>()
                obj.forEach { (key, value) ->
                    val keyStr = key?.toString() ?: "null"
                    map[keyStr] = convertToPickleValue(value)
                }
                PickleValue.PMap(map)
            }
            else -> {
                PickleValue.PUnknown(obj::class.java.simpleName, obj)
            }
        }
    }
    
    fun get(filePath: String): PickleValue? = loadedFiles[filePath]
    
    fun getAsMap(filePath: String): Map<String, PickleValue>? {
        return when (val value = loadedFiles[filePath]) {
            is PickleValue.PMap -> value.value
            else -> null
        }
    }
    
    fun getAsList(filePath: String): List<PickleValue>? {
        return when (val value = loadedFiles[filePath]) {
            is PickleValue.PList -> value.value
            else -> null
        }
    }
    
    fun getAsFloatArray(filePath: String): FloatArray? {
        return when (val value = loadedFiles[filePath]) {
            is PickleValue.PFloatArray -> value.value
            is PickleValue.PList -> {
                value.value.mapNotNull { 
                    when (it) {
                        is PickleValue.PFloat -> it.value.toFloat()
                        is PickleValue.PInt -> it.value.toFloat()
                        else -> null
                    }
                }.toFloatArray()
            }
            else -> null
        }
    }
    
    fun getAsIntArray(filePath: String): IntArray? {
        return when (val value = loadedFiles[filePath]) {
            is PickleValue.PIntArray -> value.value
            is PickleValue.PList -> {
                value.value.mapNotNull { 
                    when (it) {
                        is PickleValue.PInt -> it.value.toInt()
                        is PickleValue.PFloat -> it.value.toInt()
                        else -> null
                    }
                }.toIntArray()
            }
            else -> null
        }
    }
    
    fun getAsBytes(filePath: String): ByteArray? {
        return when (val value = loadedFiles[filePath]) {
            is PickleValue.PBytes -> value.value
            is PickleValue.PString -> value.value.toByteArray(Charsets.UTF_8)
            else -> null
        }
    }
    
    fun getBytes(filePath: String, key: String): ByteArray? {
        val map = getAsMap(filePath) ?: return null
        return when (val value = map[key]) {
            is PickleValue.PBytes -> value.value
            is PickleValue.PString -> value.value.toByteArray(Charsets.UTF_8)
            else -> null
        }
    }
    
    fun checkAndReloadIfModified(): List<String> {
        val reloaded = mutableListOf<String>()
        
        fileTimestamps.forEach { (filePath, lastModified) ->
            val file = File(filePath)
            if (file.exists() && file.lastModified() > lastModified) {
                when (val result = loadFromPath(filePath)) {
                    is PickleResult.Success -> reloaded.add(filePath)
                    is PickleResult.Error -> {}
                }
            }
        }
        
        return reloaded
    }
    
    fun reloadAll(): Map<String, PickleResult> {
        val results = mutableMapOf<String, PickleResult>()
        
        loadedFiles.keys.toList().forEach { filePath ->
            results[filePath] = loadFromPath(filePath)
        }
        
        return results
    }
    
    fun reload(filePath: String): PickleResult {
        return loadFromPath(filePath)
    }
    
    fun addListener(listener: (String, PickleValue) -> Unit) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: (String, PickleValue) -> Unit) {
        listeners.remove(listener)
    }
    
    private fun notifyListeners(filePath: String, value: PickleValue) {
        listeners.forEach { it(filePath, value) }
    }
    
    fun unload(filePath: String) {
        loadedFiles.remove(filePath)
        fileTimestamps.remove(filePath)
    }
    
    fun unloadAll() {
        loadedFiles.clear()
        fileTimestamps.clear()
    }
    
    fun isLoaded(filePath: String): Boolean = loadedFiles.containsKey(filePath)
    
    fun getLoadedFiles(): Set<String> = loadedFiles.keys.toSet()
    
    fun getPickleDirectory(): String {
        val context = appContext ?: return ""
        return File(context.filesDir, PICKLE_DIR).absolutePath
    }
    
    fun getInt(filePath: String, key: String, default: Int = 0): Int {
        val map = getAsMap(filePath) ?: return default
        return when (val value = map[key]) {
            is PickleValue.PInt -> value.value.toInt()
            is PickleValue.PFloat -> value.value.toInt()
            else -> default
        }
    }
    
    fun getFloat(filePath: String, key: String, default: Float = 0f): Float {
        val map = getAsMap(filePath) ?: return default
        return when (val value = map[key]) {
            is PickleValue.PFloat -> value.value.toFloat()
            is PickleValue.PInt -> value.value.toFloat()
            else -> default
        }
    }
    
    fun getDouble(filePath: String, key: String, default: Double = 0.0): Double {
        val map = getAsMap(filePath) ?: return default
        return when (val value = map[key]) {
            is PickleValue.PFloat -> value.value
            is PickleValue.PInt -> value.value.toDouble()
            else -> default
        }
    }
    
    fun getString(filePath: String, key: String, default: String = ""): String {
        val map = getAsMap(filePath) ?: return default
        return when (val value = map[key]) {
            is PickleValue.PString -> value.value
            else -> default
        }
    }
    
    fun getBool(filePath: String, key: String, default: Boolean = false): Boolean {
        val map = getAsMap(filePath) ?: return default
        return when (val value = map[key]) {
            is PickleValue.PBool -> value.value
            is PickleValue.PInt -> value.value != 0L
            else -> default
        }
    }
    
    fun getList(filePath: String, key: String): List<PickleValue>? {
        val map = getAsMap(filePath) ?: return null
        return when (val value = map[key]) {
            is PickleValue.PList -> value.value
            else -> null
        }
    }
    
    fun getNestedMap(filePath: String, key: String): Map<String, PickleValue>? {
        val map = getAsMap(filePath) ?: return null
        return when (val value = map[key]) {
            is PickleValue.PMap -> value.value
            else -> null
        }
    }
    
    fun getFloatList(filePath: String, key: String): List<Float>? {
        val list = getList(filePath, key) ?: return null
        return list.mapNotNull { 
            when (it) {
                is PickleValue.PFloat -> it.value.toFloat()
                is PickleValue.PInt -> it.value.toFloat()
                else -> null
            }
        }
    }
    
    fun getIntList(filePath: String, key: String): List<Int>? {
        val list = getList(filePath, key) ?: return null
        return list.mapNotNull { 
            when (it) {
                is PickleValue.PInt -> it.value.toInt()
                is PickleValue.PFloat -> it.value.toInt()
                else -> null
            }
        }
    }
    
    fun getStringList(filePath: String, key: String): List<String>? {
        val list = getList(filePath, key) ?: return null
        return list.mapNotNull { 
            when (it) {
                is PickleValue.PString -> it.value
                else -> null
            }
        }
    }
    
    fun toNativeMap(value: PickleValue): Any? {
        return when (value) {
            is PickleValue.PNone -> null
            is PickleValue.PBool -> value.value
            is PickleValue.PInt -> value.value
            is PickleValue.PFloat -> value.value
            is PickleValue.PString -> value.value
            is PickleValue.PFloatArray -> value.value
            is PickleValue.PIntArray -> value.value
            is PickleValue.PBytes -> value.value
            is PickleValue.PList -> value.value.map { toNativeMap(it) }
            is PickleValue.PMap -> value.value.mapValues { toNativeMap(it.value) }
            is PickleValue.PUnknown -> value.raw
        }
    }
    
    fun getSummary(): String {
        return buildString {
            appendLine("=== PickleLoader Summary ===")
            appendLine("Archivos cargados: ${loadedFiles.size}")
            loadedFiles.forEach { (path, value) ->
                val typeName = when (value) {
                    is PickleValue.PMap -> "Map(${value.value.size} keys)"
                    is PickleValue.PList -> "List(${value.value.size} items)"
                    is PickleValue.PFloatArray -> "FloatArray(${value.value.size})"
                    is PickleValue.PIntArray -> "IntArray(${value.value.size})"
                    is PickleValue.PBytes -> "Bytes(${value.value.size})"
                    is PickleValue.PString -> "String"
                    is PickleValue.PInt -> "Int"
                    is PickleValue.PFloat -> "Float"
                    is PickleValue.PBool -> "Bool"
                    is PickleValue.PNone -> "None"
                    is PickleValue.PUnknown -> "Unknown(${value.rawType})"
                }
                appendLine("  ${File(path).name}: $typeName")
            }
        }
    }
    
    fun getValueSummary(filePath: String): String {
        val value = loadedFiles[filePath] ?: return "Archivo no cargado: $filePath"
        return describeValue(value, 0)
    }
    
    private fun describeValue(value: PickleValue, indent: Int): String {
        val prefix = "  ".repeat(indent)
        return when (value) {
            is PickleValue.PNone -> "${prefix}None"
            is PickleValue.PBool -> "${prefix}Bool: ${value.value}"
            is PickleValue.PInt -> "${prefix}Int: ${value.value}"
            is PickleValue.PFloat -> "${prefix}Float: ${value.value}"
            is PickleValue.PString -> "${prefix}String: \"${value.value.take(50)}${if (value.value.length > 50) "..." else ""}\""
            is PickleValue.PFloatArray -> "${prefix}FloatArray[${value.value.size}]: ${value.value.take(5).joinToString()}${if (value.value.size > 5) "..." else ""}"
            is PickleValue.PIntArray -> "${prefix}IntArray[${value.value.size}]: ${value.value.take(5).joinToString()}${if (value.value.size > 5) "..." else ""}"
            is PickleValue.PBytes -> "${prefix}Bytes[${value.value.size}]: ${value.value.take(8).joinToString { String.format("%02X", it) }}${if (value.value.size > 8) "..." else ""}"
            is PickleValue.PList -> {
                buildString {
                    appendLine("${prefix}List[${value.value.size}]:")
                    value.value.take(5).forEach { item ->
                        appendLine(describeValue(item, indent + 1))
                    }
                    if (value.value.size > 5) {
                        appendLine("${prefix}  ... y ${value.value.size - 5} mas")
                    }
                }
            }
            is PickleValue.PMap -> {
                buildString {
                    appendLine("${prefix}Map[${value.value.size} keys]:")
                    value.value.entries.take(10).forEach { (key, v) ->
                        appendLine("${prefix}  $key:")
                        appendLine(describeValue(v, indent + 2))
                    }
                    if (value.value.size > 10) {
                        appendLine("${prefix}  ... y ${value.value.size - 10} mas")
                    }
                }
            }
            is PickleValue.PUnknown -> "${prefix}Unknown(${value.rawType}): ${value.raw}"
        }
    }
}
