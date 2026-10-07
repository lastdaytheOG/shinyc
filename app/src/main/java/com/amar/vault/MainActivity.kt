package com.amar.vault

import android.Manifest
import android.content.ComponentCallbacks2
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var vectorSearchManager: VectorSearchManager

    private var screenshotObserver: ScreenshotObserver? = null
    private var folderSyncObserver: FolderSyncObserver? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) startObservers()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent { VaultApp() }

        requestPermissions()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.READ_MEDIA_IMAGES)

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            startObservers()
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startObservers() {
        // Original screenshot observer (always active)
        screenshotObserver = ScreenshotObserver(applicationContext, scope)
        screenshotObserver?.register()

        // Folder sync observer (watches all selected folders for new photos)
        folderSyncObserver = FolderSyncObserver(applicationContext, scope)
        folderSyncObserver?.register()
    }

    override fun onStop() {
        super.onStop()
        scope.launch {
            vectorSearchManager.saveState()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE) {
            AppEmbeddingEngine.release()
            android.util.Log.d("MemoryManager", "Released ONNX engine — level $level")
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        screenshotObserver?.unregister()
        folderSyncObserver?.unregister()

        vectorSearchManager.destroy()
    }
}