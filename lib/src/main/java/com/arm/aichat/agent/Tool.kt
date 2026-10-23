package com.arm.aichat.agent

import org.json.JSONObject

/**
 * Interface for tools that can be called by the AI agent
 */
interface Tool {
    /**
     * Unique name of the tool
     */
    val name: String
    
    /**
     * Description of what this tool does
     */
    val description: String
    
    /**
     * JSON schema for the tool's parameters
     */
    val parametersSchema: JSONObject
    
    /**
     * Execute the tool with given parameters
     * @param params JSON object containing parameters
     * @return Result string
     */
    suspend fun execute(params: JSONObject): String
}

/**
 * Registry for managing available tools
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    
    /**
     * Register a new tool
     */
    fun register(tool: Tool) {
        tools[tool.name] = tool
    }
    
    /**
     * Unregister a tool
     */
    fun unregister(name: String) {
        tools.remove(name)
    }
    
    /**
     * Get a tool by name
     */
    fun getTool(name: String): Tool? = tools[name]
    
    /**
     * Get all registered tools
     */
    fun getAllTools(): List<Tool> = tools.values.toList()
    
    /**
     * Generate tools description for LLM prompt
     */
    fun generateToolsPrompt(): String {
        if (tools.isEmpty()) return ""
        
        return buildString {
            appendLine("\n\n" + "=".repeat(50))
            appendLine("IMPORTANT: You have access to the following tools:")
            appendLine("=".repeat(50))
            
            tools.values.forEach { tool ->
                appendLine("\n### Tool: ${tool.name}")
                appendLine("Description: ${tool.description}")
                appendLine("Parameters: ${tool.parametersSchema}")
            }
            
            appendLine("\n\n" + "=".repeat(50))
            appendLine("CRITICAL: How to Use Tools")
            appendLine("=".repeat(50))
            appendLine("\nWhen the user asks you to perform an action that requires a tool,")
            appendLine("you MUST use the exact format below to call the tool:")
            appendLine("\n[TOOL_CALL] tool_name {\"param1\": \"value1\"}")
            appendLine("\nExamples:")
            appendLine("- User: \"Take a photo\" → [TOOL_CALL] camera {\"action\": \"take\"}")
            appendLine("- User: \"What time is it?\" → [TOOL_CALL] datetime {\"action\": \"current_time\"}")
            appendLine("- User: \"Add a calendar event\" → [TOOL_CALL] calendar {\"action\": \"add\", \"title\": \"Meeting\"}")
            appendLine("\nDO NOT ask for more information if a tool can handle the request.")
            appendLine("DO NOT explain what you will do - just call the tool immediately.")
            appendLine("\nAfter the tool executes, you will receive the result and can continue.")
            appendLine("=".repeat(50))
        }
    }
    
    /**
     * Parse tool call from AI response
     * @param response AI response text
     * @return ToolCall object if found, null otherwise
     */
    fun parseToolCall(response: String): ToolCall? {
        // Try multiple patterns to find tool call
        val patterns = listOf(
            // Pattern 1: [TOOL_CALL] tool_name {...}
            """\[TOOL_CALL]\s+(\w+)\s+(\{[^}]*\})""".toRegex(RegexOption.DOT_MATCHES_ALL),
            // Pattern 2: TOOL_CALL tool_name {...}
            """TOOL_CALL\s+(\w+)\s+(\{[^}]*\})""".toRegex(RegexOption.DOT_MATCHES_ALL),
            // Pattern 3: [tool_name] {...}
            """\[(\w+)]\s+(\{[^}]*\})""".toRegex(RegexOption.DOT_MATCHES_ALL)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(response)
            if (match != null) {
                val toolName = match.groupValues[1]
                val paramsJson = match.groupValues[2]
                
                // Verify it's a real tool
                if (tools.containsKey(toolName)) {
                    return try {
                        val params = JSONObject(paramsJson)
                        ToolCall(toolName, params)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        }
        
        return null
    }
    
    /**
     * Data class representing a tool call
     */
    data class ToolCall(
        val toolName: String,
        val params: JSONObject
    )
}
