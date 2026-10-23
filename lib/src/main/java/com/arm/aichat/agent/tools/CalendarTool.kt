package com.arm.aichat.agent.tools

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import com.arm.aichat.agent.Tool
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Calendar management tool for scheduling events and reminders
 */
class CalendarTool(private val context: Context) : Tool {
    override val name = "calendar"
    override val description = "Manage calendar events: add new events, view upcoming events, or search events"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'add', 'list', 'search', or 'delete'"
                },
                "title": {
                    "type": "string",
                    "description": "Event title (for add action)"
                },
                "description": {
                    "type": "string",
                    "description": "Event description (optional, for add action)"
                },
                "start_time": {
                    "type": "string",
                    "description": "Start time in format 'yyyy-MM-dd HH:mm' (for add action)"
                },
                "end_time": {
                    "type": "string",
                    "description": "End time in format 'yyyy-MM-dd HH:mm' (optional, for add action)"
                },
                "location": {
                    "type": "string",
                    "description": "Event location (optional, for add action)"
                },
                "days_ahead": {
                    "type": "integer",
                    "description": "Number of days to look ahead (for list action, default: 7)"
                },
                "query": {
                    "type": "string",
                    "description": "Search query (for search action)"
                },
                "event_id": {
                    "type": "integer",
                    "description": "Event ID to delete (for delete action)"
                }
            },
            "required": ["action"]
        }
    """)
    
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    private val displayFormat = SimpleDateFormat("EEE, MMM d, yyyy 'at' HH:mm", Locale.getDefault())
    
    override suspend fun execute(params: JSONObject): String {
        val action = params.getString("action")
        
        return when (action.lowercase()) {
            "add" -> addEvent(params)
            "list" -> listEvents(params)
            "search" -> searchEvents(params)
            "delete" -> deleteEvent(params)
            else -> "Unknown action: $action. Use: add, list, search, or delete"
        }
    }
    
    /**
     * Add a new calendar event
     */
    private fun addEvent(params: JSONObject): String {
        try {
            val title = params.optString("title")
            if (title.isEmpty()) {
                return "Error: 'title' is required for add action"
            }
            
            val startTimeStr = params.optString("start_time")
            if (startTimeStr.isEmpty()) {
                return "Error: 'start_time' is required for add action"
            }
            
            val startTime = dateFormat.parse(startTimeStr)?.time 
                ?: return "Error: Invalid start_time format. Use 'yyyy-MM-dd HH:mm'"
            
            val endTimeStr = params.optString("end_time")
            val endTime = if (endTimeStr.isNotEmpty()) {
                dateFormat.parse(endTimeStr)?.time ?: (startTime + 3600000) // Default 1 hour
            } else {
                startTime + 3600000 // Default 1 hour duration
            }
            
            val description = params.optString("description", "")
            val location = params.optString("location", "")
            
            // Insert event into calendar
            val values = ContentValues().apply {
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startTime)
                put(CalendarContract.Events.DTEND, endTime)
                put(CalendarContract.Events.DESCRIPTION, description)
                put(CalendarContract.Events.EVENT_LOCATION, location)
                put(CalendarContract.Events.CALENDAR_ID, getPrimaryCalendarId())
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            
            val uri = context.contentResolver.insert(
                CalendarContract.Events.CONTENT_URI,
                values
            )
            
            val eventId = uri?.lastPathSegment
            return "Event added successfully!\n" +
                   "Title: $title\n" +
                   "Time: ${displayFormat.format(Date(startTime))}\n" +
                   if (location.isNotEmpty()) "Location: $location\n" else "" +
                   if (description.isNotEmpty()) "Description: $description\n" else "" +
                   "Event ID: $eventId"
                   
        } catch (e: Exception) {
            return "Error adding event: ${e.message}"
        }
    }
    
    /**
     * List upcoming events
     */
    private fun listEvents(params: JSONObject): String {
        try {
            val daysAhead = params.optInt("days_ahead", 7)
            val now = System.currentTimeMillis()
            val endTime = now + daysAhead * 24 * 60 * 60 * 1000L
            
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            )
            
            val selection = "${CalendarContract.Events.DTSTART} >= ? AND " +
                           "${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(now.toString(), endTime.toString())
            
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )
            
            val events = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1)
                    val startTime = Date(it.getLong(2))
                    val location = it.getString(4) ?: ""
                    
                    events.add(
                        "[$id] $title\n" +
                        "    Time: ${displayFormat.format(startTime)}\n" +
                        if (location.isNotEmpty()) "    Location: $location\n" else ""
                    )
                }
            }
            
            return if (events.isEmpty()) {
                "No events found in the next $daysAhead days."
            } else {
                "Upcoming events (${events.size}):\n\n" + events.joinToString("\n")
            }
            
        } catch (e: Exception) {
            return "Error listing events: ${e.message}"
        }
    }
    
    /**
     * Search events by title
     */
    private fun searchEvents(params: JSONObject): String {
        try {
            val query = params.optString("query")
            if (query.isEmpty()) {
                return "Error: 'query' is required for search action"
            }
            
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION
            )
            
            val selection = "${CalendarContract.Events.TITLE} LIKE ?"
            val selectionArgs = arrayOf("%$query%")
            
            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} DESC"
            )
            
            val events = mutableListOf<String>()
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val title = it.getString(1)
                    val startTime = Date(it.getLong(2))
                    val location = it.getString(3) ?: ""
                    
                    events.add(
                        "[$id] $title\n" +
                        "    Time: ${displayFormat.format(startTime)}\n" +
                        if (location.isNotEmpty()) "    Location: $location" else ""
                    )
                }
            }
            
            return if (events.isEmpty()) {
                "No events found matching '$query'."
            } else {
                "Found ${events.size} event(s) matching '$query':\n\n" + events.joinToString("\n")
            }
            
        } catch (e: Exception) {
            return "Error searching events: ${e.message}"
        }
    }
    
    /**
     * Delete a calendar event
     */
    private fun deleteEvent(params: JSONObject): String {
        try {
            val eventId = params.optInt("event_id")
            if (eventId == 0) {
                return "Error: 'event_id' is required for delete action"
            }
            
            val uri = CalendarContract.Events.CONTENT_URI.buildUpon()
                .appendPath(eventId.toString())
                .build()
            
            val deleted = context.contentResolver.delete(uri, null, null)
            
            return if (deleted > 0) {
                "Event #$eventId deleted successfully."
            } else {
                "Event #$eventId not found or could not be deleted."
            }
            
        } catch (e: Exception) {
            return "Error deleting event: ${e.message}"
        }
    }
    
    /**
     * Get the primary calendar ID
     */
    private fun getPrimaryCalendarId(): Long {
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = "${CalendarContract.Calendars.IS_PRIMARY} = 1"
        
        val cursor = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            null,
            null
        )
        
        return cursor?.use {
            if (it.moveToFirst()) {
                it.getLong(0)
            } else {
                // Fallback: get first available calendar
                val fallbackCursor = context.contentResolver.query(
                    CalendarContract.Calendars.CONTENT_URI,
                    projection,
                    null,
                    null,
                    null
                )
                fallbackCursor?.use { fallback ->
                    if (fallback.moveToFirst()) fallback.getLong(0) else 1L
                } ?: 1L
            }
        } ?: 1L
    }
}
