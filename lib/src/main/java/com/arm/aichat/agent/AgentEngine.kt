package com.arm.aichat.agent

import android.content.Context
import android.util.Log
import com.arm.aichat.InferenceEngine
import com.arm.aichat.agent.tools.CalculatorTool
import com.arm.aichat.agent.tools.SimpleCameraTool
import com.arm.aichat.agent.tools.CalendarTool
import com.arm.aichat.agent.tools.DateTimeTool
import com.arm.aichat.agent.tools.DeviceInfoTool
import com.arm.aichat.agent.tools.NoteTakingTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * AI Agent with tool calling capabilities
 */
class AgentEngine(
    private val inferenceEngine: InferenceEngine,
    private val context: Context
) {
    companion object {
        private const val TAG = "AgentEngine"
        private const val MAX_TOOL_ITERATIONS = 5
    }
    
    private val toolRegistry = ToolRegistry()
    private var cameraTool: SimpleCameraTool? = null
    
    // Callback to launch system camera
    var onLaunchCamera: (() -> Unit)? = null
    
    // System prompt set flag
    private var systemPromptSet = false
    
    init {
        // Register available tools
        registerDefaultTools()
    }
    
    /**
     * Initialize the agent
     * System prompt is now prepended to user input directly
     */
    suspend fun initialize() {
        Log.i(TAG, "Agent initialized with ${toolRegistry.getAllTools().size} tools available")
        Log.d(TAG, "System prompt will be prepended to user input")
    }
    
    /**
     * Register default tools
     */
    private fun registerDefaultTools() {
        toolRegistry.register(CalculatorTool())
        toolRegistry.register(DateTimeTool())
        toolRegistry.register(DeviceInfoTool(context))
        toolRegistry.register(NoteTakingTool())
        toolRegistry.register(CalendarTool(context))
        
        // Register camera tool with callback
        cameraTool = SimpleCameraTool(context)
        cameraTool?.onLaunchCamera = { uri ->
            Log.i(TAG, "Camera tool callback triggered, launching system camera")
            // Launch system camera
            onLaunchCamera?.invoke()
        }
        toolRegistry.register(cameraTool!!)
        
        Log.i(TAG, "Registered ${toolRegistry.getAllTools().size} tools")
        Log.d(TAG, "Camera tool registered with callback")
    }
    
    /**
     * Get list of available tools
     */
    fun getAvailableTools(): List<Tool> = toolRegistry.getAllTools()
    
    /**
     * Process user input with tool calling support
     * 
     * @param userInput User's message
     * @return Flow of response tokens
     */
    fun processInput(userInput: String): Flow<String> = flow {
        Log.i(TAG, "Processing input: $userInput")
        
        var currentInput = userInput
        var iteration = 0
        
        while (iteration < MAX_TOOL_ITERATIONS) {
            iteration++
            Log.d(TAG, "Iteration $iteration")
            
            // Prepend system prompt to user input for tool awareness
            val fullInput = if (iteration == 1) {
                val systemPrompt = buildSystemPrompt()
                "$systemPrompt\n\nUser: $currentInput\nAssistant:"
            } else {
                currentInput
            }
            
            // Generate response
            val fullResponse = StringBuilder()
            inferenceEngine.sendUserPrompt(fullInput).collect { token ->
                fullResponse.append(token)
                emit(token)
            }
            
            val responseText = fullResponse.toString()
            Log.d(TAG, "Generated response length: ${responseText.length}")
            Log.d(TAG, "Generated response: $responseText")
            
            // Check if response contains tool call
            val toolCall = toolRegistry.parseToolCall(responseText)
            
            if (toolCall == null) {
                // No tool call, response is complete
                Log.d(TAG, "No tool call detected, response complete")
                Log.d(TAG, "Looking for pattern: [TOOL_CALL] tool_name {...}")
                Log.d(TAG, "Response preview: ${responseText.take(300)}")
                break
            }
            
            // Execute tool
            Log.i(TAG, "Tool call detected: ${toolCall.toolName}")
            Log.i(TAG, "Tool params: ${toolCall.params}")
            val tool = toolRegistry.getTool(toolCall.toolName)
            
            if (tool == null) {
                Log.w(TAG, "Tool not found: ${toolCall.toolName}")
                emit("\n\n[Error: Tool '${toolCall.toolName}' not found]")
                break
            }
            
            // Execute tool and get result
            emit("\n\n[Calling tool: ${tool.name}...]\n")
            val toolResult = try {
                tool.execute(toolCall.params)
            } catch (e: Exception) {
                Log.e(TAG, "Tool execution error", e)
                "[Tool execution error: ${e.message}]"
            }
            
            Log.i(TAG, "Tool result: $toolResult")
            emit("[Tool result: $toolResult]\n\n")
            
            // Prepare next iteration with tool result
            currentInput = buildToolResultPrompt(userInput, responseText, toolResult)
        }
        
        if (iteration >= MAX_TOOL_ITERATIONS) {
            Log.w(TAG, "Reached max tool iterations ($MAX_TOOL_ITERATIONS)")
            emit("\n\n[Warning: Reached maximum tool call limit]")
        }
    }
    
    /**
     * Build system prompt with tool descriptions
     */
    private fun buildSystemPrompt(): String {
        return buildString {
            appendLine("You are a helpful AI assistant with access to various tools.")
            appendLine("\n" + "=".repeat(60))
            appendLine("CRITICAL INSTRUCTION: Tool Usage")
            appendLine("=".repeat(60))
            appendLine("\nWhen the user asks you to perform ANY of the following actions:")
            appendLine("- Take a photo / picture / camera")
            appendLine("- Check time / date")
            appendLine("- Add / view / manage calendar events")
            appendLine("- Calculate math expressions")
            appendLine("- Get device information")
            appendLine("- Take notes")
            appendLine("\nYou MUST immediately call the appropriate tool using this EXACT format:")
            appendLine("[TOOL_CALL] tool_name {\"param1\": \"value1\"}")
            appendLine("\nDO NOT:")
            appendLine("- Ask for more details (just call the tool)")
            appendLine("- Explain what you will do (just do it)")
            appendLine("- Say you cannot do something (you have tools!)")
            appendLine("\nExamples:")
            appendLine("User: \"帮我拍一张照片\"")
            appendLine("You: [TOOL_CALL] camera {\"action\": \"take\"}")
            appendLine("\nUser: \"What time is it?\"")
            appendLine("You: [TOOL_CALL] datetime {\"action\": \"current_time\"}")
            appendLine("\nUser: \"Add meeting to calendar\"")
            appendLine("You: [TOOL_CALL] calendar {\"action\": \"add\", \"title\": \"Meeting\", ...}")
            appendLine("\n" + "=".repeat(60))
            append(toolRegistry.generateToolsPrompt())
        }
    }
    
    /**
     * Build prompt for next iteration after tool result
     */
    private fun buildToolResultPrompt(
        originalInput: String,
        aiResponse: String,
        toolResult: String
    ): String {
        return buildString {
            appendLine("Original question: $originalInput")
            appendLine()
            appendLine("Your previous response: $aiResponse")
            appendLine()
            appendLine("Tool result: $toolResult")
            appendLine()
            appendLine("Please use the tool result to complete your response to the original question.")
        }
    }
}
