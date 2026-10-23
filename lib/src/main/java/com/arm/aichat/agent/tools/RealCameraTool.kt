package com.arm.aichat.agent.tools

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.arm.aichat.agent.Tool
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Real camera tool using CameraX for actual photo capture
 */
class RealCameraTool(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: androidx.camera.view.PreviewView?
) : Tool {
    
    override val name = "real_camera"
    override val description = "Take real photos using device camera with CameraX"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'take', 'preview', 'stop', or 'info'"
                },
                "description": {
                    "type": "string",
                    "description": "Photo description (optional)"
                },
                "camera_facing": {
                    "type": "string",
                    "description": "Camera facing: 'front' or 'back' (default: 'back')"
                }
            },
            "required": ["action"]
        }
    """)
    
    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    // Callback for photo capture result
    var onPhotoCaptured: ((Boolean, String?) -> Unit)? = null
    
    override suspend fun execute(params: JSONObject): String {
        val action = params.getString("action")
        
        return when (action.lowercase()) {
            "take" -> takePhoto(params)
            "preview" -> startPreview(params)
            "stop" -> stopCamera()
            "info" -> getCameraInfo()
            else -> "Unknown action: $action. Use: take, preview, stop, or info"
        }
    }
    
    /**
     * Start camera preview
     */
    private fun startPreview(params: JSONObject): String {
        try {
            val cameraFacing = params.optString("camera_facing", "back")
            val cameraSelector = if (cameraFacing == "front") {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            // Get camera provider
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                
                // Preview use case
                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(previewView?.surfaceProvider)
                    }
                
                // Image capture use case
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // Unbind previous use cases
                cameraProvider.unbindAll()
                
                // Bind to lifecycle
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                
                onPhotoCaptured?.invoke(true, "Camera preview started")
                
            }, ContextCompat.getMainExecutor(context))
            
            return "Camera preview started. Use 'take' action to capture photo."
            
        } catch (e: Exception) {
            return "Error starting camera preview: ${e.message}"
        }
    }
    
    /**
     * Take a photo using CameraX
     */
    private fun takePhoto(params: JSONObject): String {
        try {
            val imageCapture = imageCapture ?: return "Error: Camera not initialized. Use 'preview' action first."
            
            val description = params.optString("description", "")
            val timestamp = System.currentTimeMillis()
            val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))}.jpg"
            
            // Create output directory
            val photoDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "AIChat"
            )
            if (!photoDir.exists()) {
                photoDir.mkdirs()
            }
            
            val photoFile = File(photoDir, filename)
            
            // Create output options
            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
            
            // Take picture
            imageCapture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onError(exc: ImageCaptureException) {
                        val errorMsg = "Photo capture failed: ${exc.message}"
                        onPhotoCaptured?.invoke(false, errorMsg)
                    }
                    
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val savedMsg = "Photo saved to: ${photoFile.absolutePath}"
                        
                        // Add to media store
                        addToMediaStore(photoFile, description)
                        
                        // Get photo info
                        val options = BitmapFactory.Options()
                        options.inJustDecodeBounds = true
                        BitmapFactory.decodeFile(photoFile.absolutePath, options)
                        
                        val result = "Photo captured successfully!\n" +
                                "Filename: $filename\n" +
                                "Path: ${photoFile.absolutePath}\n" +
                                "Size: ${photoFile.length() / 1024} KB\n" +
                                "Dimensions: ${options.outWidth}x${options.outHeight}\n" +
                                if (description.isNotEmpty()) "Description: $description\n" else "" +
                                "Time: ${dateFormat.format(Date(timestamp))}"
                        
                        onPhotoCaptured?.invoke(true, result)
                    }
                }
            )
            
            return "Taking photo..."
            
        } catch (e: Exception) {
            return "Error taking photo: ${e.message}"
        }
    }
    
    /**
     * Stop camera
     */
    private fun stopCamera(): String {
        try {
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                imageCapture = null
            }, ContextCompat.getMainExecutor(context))
            
            cameraExecutor.shutdown()
            
            return "Camera stopped"
            
        } catch (e: Exception) {
            return "Error stopping camera: ${e.message}"
        }
    }
    
    /**
     * Get camera information
     */
    private fun getCameraInfo(): String {
        val hasCamera = context.packageManager.hasSystemFeature("android.hardware.camera")
        val hasFrontCamera = context.packageManager.hasSystemFeature("android.hardware.camera.front")
        
        return "Camera Information:\n" +
                "Has Camera: $hasCamera\n" +
                "Has Front Camera: $hasFrontCamera\n" +
                "Camera Initialized: ${imageCapture != null}\n" +
                "Preview View: ${previewView != null}"
    }
    
    /**
     * Add image to media store
     */
    private fun addToMediaStore(imageFile: File, description: String) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, imageFile.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.Images.Media.DESCRIPTION, description)
        }
        
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        cameraExecutor.shutdown()
    }
}
