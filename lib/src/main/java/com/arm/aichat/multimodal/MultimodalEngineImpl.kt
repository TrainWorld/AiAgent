package com.arm.aichat.multimodal

import android.content.Context
import android.util.Log
import com.arm.aichat.multimodal.MultimodalEngine.State
import dalvik.annotation.optimization.FastNative
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

/**
 * JNI-backed implementation of [MultimodalEngine].
 *
 * Uses libmtmd (llama.cpp multimodal library) for vision encoding.
 * All native operations run on a dedicated single-threaded dispatcher.
 *
 * @see multimodal.cpp for the native implementation details
 */
internal class MultimodalEngineImpl private constructor(
    private val nativeLibDir: String
) : MultimodalEngine {

    companion object {
        private val TAG = "MultimodalEngine"

        @Volatile
        private var instance: MultimodalEngine? = null

        fun getInstance(context: Context): MultimodalEngine =
            instance ?: synchronized(this) {
                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                require(nativeLibDir.isNotBlank()) { "Expected a valid native library path!" }
                MultimodalEngineImpl(nativeLibDir).also { instance = it }
            }
    }

    // ──────────────────── JNI Methods ────────────────────

    @FastNative
    private external fun loadMultimodal(nativeLibDir: String, modelPath: String, mmprojPath: String): Int

    @FastNative
    private external fun processMultimodalPrompt(text: String, imagePaths: Array<String>): Int

    @FastNative
    private external fun generateNextMultimodalToken(): String?

    @FastNative
    private external fun stopMultimodalGeneration()

    @FastNative
    private external fun resetMultimodalConversation()

    @FastNative
    private external fun setMultimodalParams(temperature: Float, contextSize: Int, nThreads: Int)

    @FastNative
    private external fun cleanUpMultimodal()

    // ──────────────────── State ────────────────────

    private val _state = MutableStateFlow<State>(State.Uninitialized)
    override val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var _cancelGeneration = false

    @OptIn(ExperimentalCoroutinesApi::class)
    private val llamaDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val llamaScope = CoroutineScope(llamaDispatcher + SupervisorJob())

    init {
        llamaScope.launch {
            try {
                Log.i(TAG, "Loading native library...")
                System.loadLibrary("ai-chat")
                _state.value = State.Initialized
                Log.i(TAG, "Multimodal engine initialized (waiting for model)")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                _state.value = State.Error("Failed to load native library: ${e.message}")
            }
        }
    }

    // ──────────────────── Public API ────────────────────

    override suspend fun loadModel(modelPath: String, mmprojPath: String): Unit =
        withContext(llamaDispatcher) {
            if (_state.value is State.Initialized || _state.value is State.Ready || _state.value is State.Error) {
                Log.i(TAG, "Cleaning up previous model...")
                cleanUpMultimodal()
            }

            try {
                Log.i(TAG, "Loading multimodal model...")
                Log.i(TAG, "  Model:  $modelPath")
                Log.i(TAG, "  Mmproj: $mmprojPath")

                require(File(modelPath).exists())  { "Model file not found: $modelPath" }
                require(File(mmprojPath).exists()) { "Mmproj file not found: $mmprojPath" }

                _state.value = State.LoadingModel
                val result = loadMultimodal(nativeLibDir, modelPath, mmprojPath)
                if (result != 0) {
                    throw RuntimeException("Failed to load multimodal model (code=$result)")
                }

                _cancelGeneration = false
                _state.value = State.Ready
                Log.i(TAG, "Multimodal model loaded successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load multimodal model", e)
                _state.value = State.Error(e.message ?: "Unknown error")
                throw e
            }
        }

    override fun sendMultimodalPrompt(
        message: String,
        imagePaths: List<String>
    ): Flow<String> = flow {
        require(message.isNotEmpty() || imagePaths.isNotEmpty()) {
            "User prompt must contain text or images!"
        }
        check(_state.value is State.Ready) {
            "Cannot send prompt in state: ${_state.value}"
        }

        try {
            Log.i(TAG, "Processing multimodal prompt (images=${imagePaths.size})...")
            _state.value = State.ProcessingPrompt

            val result = processMultimodalPrompt(message, imagePaths.toTypedArray())
            if (result != 0) {
                Log.e(TAG, "Failed to process prompt: $result")
                _state.value = State.Error("Prompt processing failed: $result")
                throw RuntimeException("Prompt processing failed (code=$result)")
            }

            Log.i(TAG, "Prompt processed, starting generation...")
            _cancelGeneration = false
            _state.value = State.Generating

            while (!_cancelGeneration) {
                generateNextMultimodalToken()?.let { token ->
                    if (token.isNotEmpty()) emit(token)
                } ?: break
            }

            if (_cancelGeneration) {
                Log.i(TAG, "Generation stopped by user")
            } else {
                Log.i(TAG, "Generation complete")
            }
            _state.value = State.Ready

        } catch (e: CancellationException) {
            Log.i(TAG, "Generation cancelled")
            _state.value = State.Ready
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error during generation", e)
            _state.value = State.Error(e.message ?: "Generation failed")
            throw e
        }
    }.flowOn(llamaDispatcher)

    override fun stopGeneration() {
        Log.i(TAG, "Stop generation requested")
        _cancelGeneration = true
        stopMultimodalGeneration()
    }

    override fun resetConversation() {
        llamaScope.launch {
            if (_state.value is State.Ready) {
                Log.i(TAG, "Resetting multimodal conversation")
                resetMultimodalConversation()
                _state.value = State.Ready
            }
        }
    }

    override fun setParams(temperature: Float, contextSize: Int, nThreads: Int) {
        setMultimodalParams(temperature, contextSize, nThreads)
    }

    override fun cleanUp() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            Log.i(TAG, "Cleaning up multimodal resources...")
            cleanUpMultimodal()
            _state.value = State.Initialized
        }
    }

    /**
     * Release all resources including the dispatcher.
     */
    override fun destroy() {
        _cancelGeneration = true
        runBlocking(llamaDispatcher) {
            cleanUpMultimodal()
        }
        llamaScope.cancel()
        instance = null
    }
}
