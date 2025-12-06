package com.example.onnxsc.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.onnxsc.Logger
import com.example.onnxsc.R
import com.example.onnxsc.config.ModelConfigManager
import com.example.onnxsc.databinding.ActivityModelConfigBinding
import com.google.android.material.tabs.TabLayoutMediator

class ModelConfigActivity : AppCompatActivity() {

    private lateinit var binding: ActivityModelConfigBinding
    private var modelName: String = ""
    
    private val tabTitles = listOf("General", "Labels", "Instrucciones")
    
    companion object {
        private const val EXTRA_MODEL_NAME = "model_name"
        
        fun createIntent(context: Context, modelName: String): Intent {
            return Intent(context, ModelConfigActivity::class.java).apply {
                putExtra(EXTRA_MODEL_NAME, modelName)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityModelConfigBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        modelName = intent.getStringExtra(EXTRA_MODEL_NAME) ?: ""
        
        if (modelName.isEmpty()) {
            Toast.makeText(this, "No hay modelo seleccionado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        val hasExistingConfig = ModelConfigManager.loadConfig(this, modelName)
        if (!hasExistingConfig) {
            Logger.info("Creando configuracion por defecto para: $modelName")
        }
        
        setupToolbar()
        setupViewPager()
        setupButtons()
    }
    
    private fun setupToolbar() {
        binding.toolbar.title = "Config: $modelName"
        binding.toolbar.setNavigationOnClickListener {
            onBackPressedWithCheck()
        }
    }
    
    private fun setupViewPager() {
        binding.viewPager.adapter = ConfigPagerAdapter(this)
        
        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = tabTitles.getOrElse(position) { "" }
        }.attach()
    }
    
    private fun setupButtons() {
        binding.btnSave.setOnClickListener {
            saveConfiguration()
        }
        
        binding.btnRestore.setOnClickListener {
            restoreConfiguration()
        }
    }
    
    private fun saveConfiguration() {
        collectConfigFromFragments()
        
        if (ModelConfigManager.saveConfig(this, modelName)) {
            Toast.makeText(this, "Configuracion guardada", Toast.LENGTH_SHORT).show()
            Logger.success("Configuracion guardada: $modelName")
        } else {
            Toast.makeText(this, "Error al guardar configuracion", Toast.LENGTH_SHORT).show()
            Logger.error("Error guardando configuracion: $modelName")
        }
    }
    
    private fun restoreConfiguration() {
        AlertDialog.Builder(this)
            .setTitle("Restaurar Configuracion")
            .setMessage("Se restaurara la configuracion original. ¿Continuar?")
            .setPositiveButton("Restaurar") { _, _ ->
                if (ModelConfigManager.restoreOriginalConfig()) {
                    refreshFragments()
                    Toast.makeText(this, "Configuracion restaurada", Toast.LENGTH_SHORT).show()
                    Logger.info("Configuracion restaurada: $modelName")
                } else {
                    Toast.makeText(this, "Error al restaurar", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun collectConfigFromFragments() {
        supportFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is ConfigGeneralFragment -> fragment.collectConfig()
                is ConfigLabelsFragment -> fragment.collectConfig()
                is ConfigInstructionsFragment -> fragment.collectConfig()
            }
        }
    }
    
    private fun refreshFragments() {
        supportFragmentManager.fragments.forEach { fragment ->
            when (fragment) {
                is ConfigGeneralFragment -> fragment.loadConfig()
                is ConfigLabelsFragment -> fragment.loadConfig()
                is ConfigInstructionsFragment -> fragment.loadConfig()
            }
        }
    }
    
    private fun onBackPressedWithCheck() {
        collectConfigFromFragments()
        
        if (ModelConfigManager.hasUnsavedChanges()) {
            AlertDialog.Builder(this)
                .setTitle("Cambios sin guardar")
                .setMessage("Hay cambios sin guardar. ¿Desea guardarlos antes de salir?")
                .setPositiveButton("Guardar") { _, _ ->
                    saveConfiguration()
                    finish()
                }
                .setNegativeButton("Descartar") { _, _ ->
                    finish()
                }
                .setNeutralButton("Cancelar", null)
                .show()
        } else {
            finish()
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        onBackPressedWithCheck()
    }
    
    private inner class ConfigPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = tabTitles.size
        
        override fun createFragment(position: Int): Fragment {
            return when (position) {
                0 -> ConfigGeneralFragment()
                1 -> ConfigLabelsFragment()
                2 -> ConfigInstructionsFragment()
                else -> ConfigGeneralFragment()
            }
        }
    }
}
