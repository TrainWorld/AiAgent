package com.arm.aichat.agent.tools

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import android.os.Environment
import android.provider.MediaStore
import com.arm.aichat.agent.Tool
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.core.graphics.createBitmap
import androidx.core.graphics.toColorInt

/**
 * Camera tool for taking photos and managing images
 * Note: This tool provides photo management capabilities.
 * For actual camera capture, the activity needs to handle the camera intent.
 */
class CameraTool(private val context: Context) : Tool {
    override val name = "camera"
    override val description = "Take photos, save images, or view recent photos"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'take', 'list', 'delete', or 'info'"
                },
                "filename": {
                    "type": "string",
                    "description": "Image filename (for delete or info action)"
                },
                "count": {
                    "type": "integer",
                    "description": "Number of recent photos to list (default: 10)"
                },
                "description": {
                    "type": "string",
                    "description": "Description or note for the photo (optional)"
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

    override suspend fun execute(params: JSONObject): String {
        val action = params.getString("action")

        return when (action.lowercase()) {
            "take" -> takePhoto(params)
            "list" -> listPhotos(params)
            "delete" -> deletePhoto(params)
            "info" -> getPhotoInfo(params)
            else -> "Unknown action: $action. Use: take, list, delete, or info"
        }
    }

    /**
     * Take a photo - creates a new photo entry
     * Note: Actual camera capture requires Activity integration
     */
    @SuppressLint("QueryPermissionsNeeded")
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

            // Check if camera app is available
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            if (takePictureIntent.resolveActivity(context.packageManager) != null) {
                // Return instructions for the AI to launch camera
                return "CAMERA_INTENT_REQUIRED\n" +
                       "To capture a photo, the app needs to launch the camera.\n" +
                       "Filename: $filename\n" +
                       "Path: ${imageFile.absolutePath}\n" +
                       if (description.isNotEmpty()) "Description: $description\n" else "" +
                       "Note: Camera integration requires Activity-level handling."
            }

            // Fallback: Create a simple test image
            val bitmap = createTestImage(description, timestamp)

            // Save image
            FileOutputStream(imageFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }

            // Add to media store
            addToMediaStore(imageFile, description)

            bitmap.recycle()

            return "Photo created successfully!\n" +
                   "Filename: $filename\n" +
                   "Path: ${imageFile.absolutePath}\n" +
                   "Size: ${imageFile.length() / 1024} KB\n" +
                   if (description.isNotEmpty()) "Description: $description\n" else "" +
                   "Time: ${dateFormat.format(Date(timestamp))}\n" +
                   "Note: This is a test image. For real camera capture, camera intent integration is needed."

        } catch (e: Exception) {
            return "Error creating photo: ${e.message}"
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

            // Delete from media store
            removeFromMediaStore(imageFile)

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

            // Get EXIF data
            val exif = ExifInterface(imageFile.absolutePath)
            val make = exif.getAttribute(ExifInterface.TAG_MAKE) ?: "Unknown"
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: "Unknown"
            val dateTime = exif.getAttribute(ExifInterface.TAG_DATETIME) ?: "Unknown"

            return "Photo Information:\n" +
                   "Filename: ${imageFile.name}\n" +
                   "Path: ${imageFile.absolutePath}\n" +
                   "Size: $size KB\n" +
                   "Dimensions: ${width}x${height}\n" +
                   "Format: ${imageFile.extension.lowercase()}\n" +
                   "Camera: $make $model\n" +
                   "Taken: $dateTime\n" +
                   "Last Modified: ${dateFormat.format(Date(imageFile.lastModified()))}"

        } catch (e: Exception) {
            return "Error getting photo info: ${e.message}"
        }
    }

    /**
     * Create a simple test image (for demo purposes)
     */
    private fun createTestImage(description: String, timestamp: Long): Bitmap {
        val width = 1920
        val height = 1080

        val bitmap = createBitmap(width, height)
        val canvas = android.graphics.Canvas(bitmap)

        // Background gradient
        val paint = android.graphics.Paint()
        paint.color = "#6750A4".toColorInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)

        // Text
        paint.color = android.graphics.Color.WHITE
        paint.textSize = 48f
        paint.isAntiAlias = true

        val text = if (description.isNotEmpty()) {
            "AI Chat Photo\n$description"
        } else {
            "AI Chat Photo"
        }

        val lines = text.split("\n")
        var y = height / 2f - (lines.size * 60f)

        for (line in lines) {
            val textWidth = paint.measureText(line)
            canvas.drawText(line, (width - textWidth) / 2f, y, paint)
            y += 60f
        }

        // Timestamp
        paint.textSize = 32f
        val timeText = dateFormat.format(Date(timestamp))
        val timeWidth = paint.measureText(timeText)
        canvas.drawText(timeText, (width - timeWidth) / 2f, height - 100f, paint)

        return bitmap
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
     * Remove image from media store
     */
    private fun removeFromMediaStore(imageFile: File) {
        try {
            context.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.Images.Media.DISPLAY_NAME} = ?",
                arrayOf(imageFile.name)
            )
        } catch (e: Exception) {
            // Ignore errors
        }
    }
}
