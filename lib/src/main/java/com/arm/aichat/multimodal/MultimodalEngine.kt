package com.arm.aichat.multimodal

import android.content.Context
import com.arm.aichat.multimodal.MultimodalEngine.State
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Interface for multimodal (vision) LLM inference.
 *
 * Supports text + image input and text output.
 * Completely independent from [com.arm.aichat.InferenceEngine].
 */
interface MultimodalEngine {

    companion object {
        fun create(context: Context): MultimodalEngine {
            return MultimodalEngineImpl.getInstance(context)
        }
    }

    /** Current state of the multimodal engine */
    val state: StateFlow<State>

    /**
     * Load a multimodal model.
     *
     * @param modelPath  Path to the text model GGUF file
     * @param mmprojPath Path to the vision encoder (mmproj) GGUF file
     */
    suspend fun loadModel(modelPath: String, mmprojPath: String)

    /**
     * Send a user prompt with image attachments.
     * Returns a Flow of generated tokens.
     *
     * @param message    Text message from the user
     * @param imagePaths List of absolute paths to image files
     */
    fun sendMultimodalPrompt(message: String, imagePaths: List<String>): Flow<String>

    /**
     * Stop the current generation.
     */
    fun stopGeneration()

    /**
     * Reset conversation history and KV cache without unloading the model.
     * Called when switching sessions to ensure history isolation.
     */
    fun resetConversation()

    /**
     * Update inference parameters. Takes effect on next model reload.
     *
     * @param temperature Sampling temperature (0.0 - 2.0)
     * @param contextSize Context window size in tokens
     * @param nThreads Number of inference threads (0 = auto)
     */
    fun setParams(temperature: Float, contextSize: Int, nThreads: Int)

    /**
     * Unload the model and free all resources.
     */
    fun cleanUp()

    /**
     * Release all resources including the dispatcher.
     * After calling this, the engine instance is no longer usable.
     */
    fun destroy()

    /**
     * Engine lifecycle states.
     */
    sealed class State {
        data object Uninitialized : State()
        data object Initialized : State()   // native 库已加载，等待加载模型
        data object LoadingModel : State()
        data object Ready : State()          // 模型已加载，可以推理
        data object ProcessingPrompt : State()
        data object Generating : State()
        data class Error(val message: String) : State()
    }
}
