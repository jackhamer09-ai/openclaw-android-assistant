package ai.openclaw.app.assistant

import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.Locale
import java.util.UUID

/**
 * Voice interaction session that bridges the Android assistant surface with
 * the OpenClaw gateway. Flow:
 *   1. System triggers `onShow` (long-press home / "Hey OpenClaw")
 *   2. Start speech recognition via [SpeechRecognizer]
 *   3. Send transcript to Gateway via `chat.send` RPC
 *   4. Receive assistant response via gateway chat events
 *   5. Speak response via system TTS (ElevenLabs used from main app voice screen)
 *   6. Hide session
 */
class OpenClawVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {
  companion object {
    private const val TAG = "OpenClawVISession"
    private const val RESPONSE_TIMEOUT_MS = 30_000L
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val json = Json { ignoreUnknownKeys = true }

  private var recognizer: SpeechRecognizer? = null
  private var tts: TextToSpeech? = null
  private var ttsReady = false

  private var pendingRunId: String? = null
  private var responseTimeoutJob: Job? = null
  private var assistantResponseText: StringBuilder = StringBuilder()
  private var listening = false

  private val runtime: NodeRuntime
    get() = (context.applicationContext as NodeApp).runtime

  private val gatewayEventListener: (String, String?) -> Unit = { event, payload ->
    handleGatewayEvent(event, payload)
  }

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    Log.d(TAG, "assistant session shown")
    assistantResponseText.clear()
    pendingRunId = null
    runtime.addGatewayEventListener(gatewayEventListener)
    initTts()
    startListening()
  }

  override fun onHide() {
    super.onHide()
    Log.d(TAG, "assistant session hidden")
    cleanup()
  }

  override fun onDestroy() {
    cleanup()
    scope.cancel()
    super.onDestroy()
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onHandleAssist(
    data: Bundle?,
    structure: AssistStructure?,
    content: AssistContent?,
  ) {
    // No special assist data handling needed; voice capture drives the interaction.
  }

  private fun initTts() {
    if (tts != null) return
    tts = TextToSpeech(context) { status ->
      ttsReady = status == TextToSpeech.SUCCESS
      if (ttsReady) {
        tts?.language = Locale.getDefault()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {}
          override fun onDone(utteranceId: String?) {
            // Finished speaking — hide the assistant overlay
            scope.launch(Dispatchers.Main) { hide() }
          }
          @Suppress("OVERRIDE_DEPRECATION")
          override fun onError(utteranceId: String?) {
            scope.launch(Dispatchers.Main) { hide() }
          }
        })
      } else {
        Log.w(TAG, "TTS init failed with status $status")
      }
    }
  }

  private fun startListening() {
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      Log.w(TAG, "speech recognition unavailable")
      speakAndFinish("Speech recognition is not available on this device.")
      return
    }

    listening = true
    recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
      setRecognitionListener(speechListener)
      val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1_500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 900L)
      }
      startListening(intent)
    }
  }

  private fun sendToGateway(transcript: String) {
    if (!runtime.isConnected.value) {
      Log.w(TAG, "gateway not connected")
      speakAndFinish("Gateway is not connected. Please open OpenClaw and connect first.")
      return
    }

    val idempotencyKey = UUID.randomUUID().toString()
    pendingRunId = idempotencyKey

    scope.launch {
      try {
        val params = buildJsonObject {
          put("sessionKey", JsonPrimitive("main"))
          put("message", JsonPrimitive(transcript))
          put("thinking", JsonPrimitive("low"))
          put("timeoutMs", JsonPrimitive(RESPONSE_TIMEOUT_MS))
          put("idempotencyKey", JsonPrimitive(idempotencyKey))
        }
        val response = runtime.operatorRequest("chat.send", params.toString())
        val runId = parseChatSendRunId(response)
        if (runId != null && runId != pendingRunId) {
          pendingRunId = runId
        }
        armResponseTimeout()
      } catch (err: Throwable) {
        Log.e(TAG, "chat.send failed: ${err.message}")
        speakAndFinish("Failed to send message to the assistant.")
      }
    }
  }

  /**
   * Called by [OpenClawAssistantActivity] or internally when a gateway chat
   * event arrives for our pending run.
   */
  fun handleGatewayEvent(event: String, payloadJson: String?) {
    if (event != "chat") return
    if (payloadJson.isNullOrBlank()) return
    val payload = try {
      json.parseToJsonElement(payloadJson) as? JsonObject
    } catch (_: Throwable) {
      null
    } ?: return

    val eventRunId = (payload["runId"] as? JsonPrimitive)?.content ?: return
    if (eventRunId != pendingRunId) return

    when ((payload["state"] as? JsonPrimitive)?.content) {
      "delta" -> {
        val text = parseAssistantText(payload)
        if (!text.isNullOrBlank()) {
          assistantResponseText.clear()
          assistantResponseText.append(text.trim())
        }
      }
      "final" -> {
        responseTimeoutJob?.cancel()
        val finalText = parseAssistantText(payload)?.trim().orEmpty()
        if (finalText.isNotEmpty()) {
          speakAndFinish(finalText)
        } else if (assistantResponseText.isNotEmpty()) {
          speakAndFinish(assistantResponseText.toString())
        } else {
          speakAndFinish("No response from the assistant.")
        }
        pendingRunId = null
      }
      "error" -> {
        responseTimeoutJob?.cancel()
        val errorMsg = (payload["errorMessage"] as? JsonPrimitive)?.content?.trim()
          ?: "An error occurred."
        speakAndFinish(errorMsg)
        pendingRunId = null
      }
      "aborted" -> {
        responseTimeoutJob?.cancel()
        speakAndFinish("Response was cancelled.")
        pendingRunId = null
      }
    }
  }

  private fun armResponseTimeout() {
    responseTimeoutJob?.cancel()
    responseTimeoutJob = scope.launch {
      delay(RESPONSE_TIMEOUT_MS + 5_000)
      if (pendingRunId != null) {
        pendingRunId = null
        if (assistantResponseText.isNotEmpty()) {
          speakAndFinish(assistantResponseText.toString())
        } else {
          speakAndFinish("The assistant did not respond in time.")
        }
      }
    }
  }

  private fun speakAndFinish(text: String) {
    Log.d(TAG, "speaking: ${text.take(80)}")
    if (ttsReady && tts != null) {
      val params = Bundle().apply {
        putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, UUID.randomUUID().toString())
      }
      tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, params.getString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID))
    } else {
      // TTS not available — just hide after a brief delay so the user can read
      scope.launch(Dispatchers.Main) {
        delay(2_000)
        hide()
      }
    }
  }

  private fun cleanup() {
    listening = false
    responseTimeoutJob?.cancel()
    responseTimeoutJob = null
    pendingRunId = null
    runtime.removeGatewayEventListener(gatewayEventListener)
    try {
      recognizer?.cancel()
      recognizer?.destroy()
    } catch (_: Throwable) {}
    recognizer = null
    try {
      tts?.stop()
      tts?.shutdown()
    } catch (_: Throwable) {}
    tts = null
    ttsReady = false
  }

  private fun parseAssistantText(payload: JsonObject): String? {
    val message = payload["message"] as? JsonObject ?: return null
    if ((message["role"] as? JsonPrimitive)?.content != "assistant") return null
    val content = message["content"] as? JsonArray ?: return null
    val parts = content.mapNotNull { item ->
      val obj = item as? JsonObject ?: return@mapNotNull null
      if ((obj["type"] as? JsonPrimitive)?.content != "text") return@mapNotNull null
      (obj["text"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (parts.isEmpty()) return null
    return parts.joinToString("\n")
  }

  private fun parseChatSendRunId(response: String): String? {
    return try {
      val root = json.parseToJsonElement(response) as? JsonObject ?: return null
      (root["runId"] as? JsonPrimitive)?.content
    } catch (_: Throwable) {
      null
    }
  }

  private val speechListener = object : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
      Log.d(TAG, "ready for speech")
    }
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
      listening = false
    }
    override fun onError(error: Int) {
      listening = false
      val msg = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        SpeechRecognizer.ERROR_AUDIO -> "Audio capture error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
        else -> "Speech recognition error ($error)."
      }
      Log.w(TAG, "speech error: $msg")
      speakAndFinish(msg)
    }
    override fun onResults(results: Bundle?) {
      val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
        ?.firstOrNull()?.trim().orEmpty()
      if (text.isNotEmpty()) {
        Log.d(TAG, "transcript: ${text.take(80)}")
        sendToGateway(text)
      } else {
        speakAndFinish("No speech detected.")
      }
    }
    override fun onPartialResults(partialResults: Bundle?) {
      // Could update UI with partial transcript if overlay is shown
    }
    override fun onEvent(eventType: Int, params: Bundle?) {}
  }
}
