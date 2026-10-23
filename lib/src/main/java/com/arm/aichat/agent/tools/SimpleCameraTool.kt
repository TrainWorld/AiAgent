package com.arm.aichat.agent.tools

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.arm.aichat.agent.Tool
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Simple camera tool using system camera app
 */
class SimpleCameraTool(private val context: Context) : Tool {
    
    override val name = "camera"
    override val description = "Take photos using system camera app"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'take', 'list', 'info', or 'delete'"
                },
                "filename": {
                    "type": "string",
                    "description": "Image filename (for info or delete action)"
                },
                "description": {
                    "type": "string",
                    "description": "Photo description (optional)"
                },
                "count": {
                    "type": "integer",
                    "description": "Number of photos to list (default: 10)"
                }
            },
            "required": ["action"]
        }
    """)
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    private val imageDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
        "AIChat"
    )
    
    // Callback to launch camera
    var onLaunchCamera: ((Uri) -> Unit)? = null
    var currentPhotoPath: String? = null
    
    override suspend fun execute(params: JSONObject): String {
        val action = params.getString("action")
        
        return when (action.lowercase()) {
            "take" -> takePhoto(params)
            "list" -> listPhotos(params)
            "info" -> getPhotoInfo(params)
            "delete" -> deletePhoto(params)
            else -> "Unknown action: $action. Use: take, list, info, or delete"
        }
    }
    
    /**
     * Launch system camera to take photo
     */
    private fun takePhoto(params: JSONObject): String {
        try {
            // Ensure directory exists
            if (!imageDir.exists()) {
                imageDir.mkdirs()
            }
            
            val description = params.optString("description", "")
            val timestamp = System.currentTimeMillis()
            val filename = "IMG_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))}.jpg"
            val imageFile = File(imageDir, filename)
            
            currentPhotoPath = imageFile.absolutePath
            
            // Create content URI
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                imageFile
            )
            
            // Launch system camera
            onLaunchCamera?.invoke(uri)
            
            return "LAUNCH_CAMERA\n" +
                   "Launching system camera...\n" +
                   "Filename: $filename\n" +
                   if (description.isNotEmpty()) "Description: $description\n" else "" +
                   "Please take a photo and save it."
            
        } catch (e: Exception) {
            return "Error launching camera: ${e.message}"
        }
    }
    
    /**
     * List recent photos
     */
    private fun listPhotos(params: JSONObject): String {
        try {
            val count = params.optInt("count", 10)
            
            if (!imageDir.exists()) {
                return "No photos found. The photo directory doesn't exist yet."
            }
            
            val files = imageDir.listFiles { file ->
                file.extension.lowercase() in listOf("jpg", "jpeg", "png")
            } ?: return "No photos found."
            
            val sortedFiles = files.sortedByDescending { it.lastModified() }
            val recentFiles = sortedFiles.take(count)
            
            if (recentFiles.isEmpty()) {
                return "No photos found."
            }
            
            val photoList = recentFiles.mapIndexed { index, file ->
                val size = file.length() / 1024
                val time = dateFormat.format(Date(file.lastModified()))
                "[${index + 1}] ${file.name}\n" +
                "    Size: $size KB\n" +
                "    Time: $time"
            }
            
            return "Recent photos (${recentFiles.size}/${files.size}):\n\n" + 
                   photoList.joinToString("\n\n")
            
        } catch (e: Exception) {
            return "Error listing photos: ${e.message}"
        }
    }
    
    /**
     * Get photo information
     */
    private fun getPhotoInfo(params: JSONObject): String {
        try {
            val filename = params.optString("filename")
            if (filename.isEmpty()) {
                return "Error: 'filename' is required for info action"
            }
            
            val imageFile = File(imageDir, filename)
            if (!imageFile.exists()) {
                return "Error: Photo '$filename' not found"
            }
            
            // Get image dimensions
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            BitmapFactory.decodeFile(imageFile.absolutePath, options)
            
            val width = options.outWidth
            val height = options.outHeight
            val size = imageFile.length() / 1024
            
            return "Photo Information:\n" +
                   "Filename: ${imageFile.name}\n" +
                   "Path: ${imageFile.absolutePath}\n" +
                   "Size: $size KB\n" +
                   "Dimensions: ${width}x${height}\n" +
                   "Format: ${imageFile.extension.uppercase()}\n" +
                   "Last Modified: ${dateFormat.format(Date(imageFile.lastModified()))}"
            
        } catch (e: Exception) {
            return "Error getting photo info: ${e.message}"
        }
    }
    
    /**
     * Delete a photo
     */
    private fun deletePhoto(params: JSONObject): String {
        try {
            val filename = params.optString("filename")
            if (filename.isEmpty()) {
                return "Error: 'filename' is required for delete action"
            }
            
            val imageFile = File(imageDir, filename)
            if (!imageFile.exists()) {
                return "Error: Photo '$filename' not found"
            }
            
            // Remove from media store
            try {
                context.contentResolver.delete(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                    arrayOf(imageFile.name)
                )
            } catch (e: Exception) {
                // Ignore errors
            }
            
            // Delete file
            val deleted = imageFile.delete()
            
            return if (deleted) {
                "Photo '$filename' deleted successfully."
            } else {
                "Error: Could not delete photo '$filename'"
            }
            
        } catch (e: Exception) {
            return "Error deleting photo: ${e.message}"
        }
    }
    
    /**
     * Called when photo is captured by system camera
     */
    fun onPhotoCaptured(uri: Uri) {
        // Add to media store
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, File(uri.path ?: "").name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        }
        
        context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            values
        )
    }
}
