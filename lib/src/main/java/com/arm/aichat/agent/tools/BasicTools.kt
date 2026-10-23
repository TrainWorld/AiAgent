package com.arm.aichat.agent.tools

import android.content.Context
import android.os.BatteryManager
import com.arm.aichat.agent.Tool
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.*

/**
 * Calculator tool: performs mathematical calculations
 */
class CalculatorTool : Tool {
    override val name = "calculator"
    override val description = "Performs mathematical calculations. Supports basic arithmetic (+, -, *, /), powers (^), and square root (sqrt)."
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "expression": {
                    "type": "string",
                    "description": "Mathematical expression to evaluate"
                }
            },
            "required": ["expression"]
        }
    """)
    
    override suspend fun execute(params: JSONObject): String {
        val expression = params.getString("expression")
        return try {
            val result = evaluateExpression(expression)
            "Calculation result: $expression = $result"
        } catch (e: Exception) {
            "Calculation error: ${e.message}"
        }
    }
    
    private fun evaluateExpression(expr: String): Double {
        // Remove spaces
        val cleanExpr = expr.replace(" ", "")
        
        return when {
            cleanExpr.contains("+") -> {
                val parts = cleanExpr.split("+", limit = 2)
                evaluateExpression(parts[0]) + evaluateExpression(parts[1])
            }
            cleanExpr.contains("-") && cleanExpr.lastIndexOf("-") > 0 -> {
                val idx = cleanExpr.lastIndexOf("-")
                evaluateExpression(cleanExpr.substring(0, idx)) - 
                evaluateExpression(cleanExpr.substring(idx + 1))
            }
            cleanExpr.contains("*") -> {
                val parts = cleanExpr.split("*", limit = 2)
                evaluateExpression(parts[0]) * evaluateExpression(parts[1])
            }
            cleanExpr.contains("/") -> {
                val parts = cleanExpr.split("/", limit = 2)
                val divisor = evaluateExpression(parts[1])
                if (divisor == 0.0) throw ArithmeticException("Division by zero")
                evaluateExpression(parts[0]) / divisor
            }
            cleanExpr.startsWith("sqrt(") && cleanExpr.endsWith(")") -> {
                sqrt(evaluateExpression(cleanExpr.substring(5, cleanExpr.length - 1)))
            }
            cleanExpr.contains("^") -> {
                val parts = cleanExpr.split("^", limit = 2)
                evaluateExpression(parts[0]).pow(evaluateExpression(parts[1]))
            }
            else -> cleanExpr.toDouble()
        }
    }
}

/**
 * DateTime tool: provides current date and time information
 */
class DateTimeTool : Tool {
    override val name = "datetime"
    override val description = "Gets the current date and time"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "format": {
                    "type": "string",
                    "description": "Optional format pattern (e.g., 'yyyy-MM-dd HH:mm:ss')"
                }
            }
        }
    """)
    
    override suspend fun execute(params: JSONObject): String {
        val now = LocalDateTime.now()
        val format = if (params.has("format")) {
            params.getString("format")
        } else {
            "yyyy-MM-dd HH:mm:ss"
        }
        
        val formatter = DateTimeFormatter.ofPattern(format)
        return "Current datetime: ${now.format(formatter)}"
    }
}

/**
 * DeviceInfo tool: provides device information
 */
class DeviceInfoTool(private val context: Context) : Tool {
    override val name = "device_info"
    override val description = "Gets information about the device (battery level, model, OS version, etc.)"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "info_type": {
                    "type": "string",
                    "description": "Type of info: 'battery', 'model', 'os', or 'all'"
                }
            }
        }
    """)
    
    override suspend fun execute(params: JSONObject): String {
        val infoType = if (params.has("info_type")) {
            params.getString("info_type")
        } else {
            "all"
        }
        
        return when (infoType.lowercase()) {
            "battery" -> getBatteryInfo()
            "model" -> "Device model: ${android.os.Build.MODEL}"
            "os" -> "Android version: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
            "all" -> buildString {
                appendLine("Device Information:")
                appendLine("- Model: ${android.os.Build.MODEL}")
                appendLine("- Manufacturer: ${android.os.Build.MANUFACTURER}")
                appendLine("- Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})")
                append(getBatteryInfo())
            }
            else -> "Unknown info type: $infoType. Use: battery, model, os, or all"
        }
    }
    
    private fun getBatteryInfo(): String {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val isCharging = batteryManager.isCharging
        return "Battery: $level% ${if (isCharging) "(Charging)" else "(Not charging)"}"
    }
}

/**
 * NoteTaking tool: simple note storage
 */
class NoteTakingTool : Tool {
    override val name = "note"
    override val description = "Save and retrieve notes"
    override val parametersSchema = JSONObject("""
        {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "description": "Action to perform: 'save' or 'list'"
                },
                "content": {
                    "type": "string",
                    "description": "Note content (for save action)"
                }
            },
            "required": ["action"]
        }
    """)
    
    private val notes = mutableListOf<String>()
    
    override suspend fun execute(params: JSONObject): String {
        val action = params.getString("action")
        
        return when (action.lowercase()) {
            "save" -> {
                val content = if (params.has("content")) {
                    params.getString("content")
                } else {
                    return "Error: 'content' parameter required for save action"
                }
                notes.add(content)
                "Note saved successfully! Total notes: ${notes.size}"
            }
            "list" -> {
                if (notes.isEmpty()) {
                    "No notes saved yet."
                } else {
                    buildString {
                        appendLine("Saved notes:")
                        notes.forEachIndexed { index, note ->
                            appendLine("${index + 1}. $note")
                        }
                    }
                }
            }
            else -> "Unknown action: $action. Use 'save' or 'list'"
        }
    }
}
