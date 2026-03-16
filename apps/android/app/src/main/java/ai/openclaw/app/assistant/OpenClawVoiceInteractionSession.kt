package ai.openclaw.app.assistant

import android.content.Intent
import android.media.MediaRecorder
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.util.Base64
import android.util.Log
import ai.openclaw.app.MainActivity
import ai.openclaw.app.NodeApp
import ai.openclaw.app.NodeRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.util.UUID

/**
 * Voice interaction session that bridges the Android assistant surface with
 * the OpenClaw gateway. Records raw audio and sends it to the gateway as an
 * attachment so OpenClaw handles STT via Whisper (like Telegram voice notes).
 *
 * Flow:
 *   1. System triggers `onShow` (long-press home / "Hey OpenClaw")
 *   2. Launch [MainActivity] on the Chat tab for visual feedback
 *   3. Record audio via [MediaRecorder] (OGG Opus)
 *   4. On silence/stop, send the audio file as a base64 attachment via `chat.send`
 *   5. Hide the voice session (response appears in the chat UI)
 */
class OpenClawVoiceInteractionSession(context: android.content.Context) : VoiceInteractionSession(context) {
  companion object {
    private const val TAG = "OpenClawVISession"
    private const val MAX_RECORDING_MS = 30_000L
    private const val AUDIO_FILE_NAME = "assistant_voice.ogg"
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  private var recorder: MediaRecorder? = null
  private var audioFile: File? = null
  private var recordingTimeoutJob: Job? = null
  private var recording = false

  private val runtime: NodeRuntime
    get() = (context.applicationContext as NodeApp).runtime

  override fun onShow(args: Bundle?, showFlags: Int) {
    super.onShow(args, showFlags)
    Log.d(TAG, "assistant session shown")
    launchMainActivityChatTab()
    startRecording()
  }

  @Suppress("OVERRIDE_DEPRECATION")
  override fun onHandleAssist(
    data: Bundle?,
    structure: android.app.assist.AssistStructure?,
    content: android.app.assist.AssistContent?,
  ) {
    // No special assist data handling needed; audio capture drives the interaction.
  }

  override fun onHide() {
    super.onHide()
    Log.d(TAG, "assistant session hidden")
    stopRecordingAndSend()
  }

  override fun onDestroy() {
    cleanup()
    scope.cancel()
    super.onDestroy()
  }

  private fun launchMainActivityChatTab() {
    val intent = Intent(context, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
      putExtra(MainActivity.EXTRA_TAB, MainActivity.TAB_CHAT)
    }
    context.startActivity(intent)
  }

  private fun startRecording() {
    if (recording) return

    val file = File(context.cacheDir, AUDIO_FILE_NAME)
    audioFile = file

    try {
      val mr = MediaRecorder(context).apply {
        setAudioSource(MediaRecorder.AudioSource.MIC)
        setOutputFormat(MediaRecorder.OutputFormat.OGG)
        setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        setAudioChannels(1)
        setAudioSamplingRate(16_000)
        setAudioEncodingBitRate(24_000)
        setOutputFile(file.absolutePath)
        prepare()
        start()
      }
      recorder = mr
      recording = true
      Log.d(TAG, "recording started")

      // Auto-stop after max duration
      recordingTimeoutJob = scope.launch {
        delay(MAX_RECORDING_MS)
        if (recording) {
          Log.d(TAG, "max recording duration reached")
          scope.launch(Dispatchers.Main) { hide() }
        }
      }
    } catch (err: Throwable) {
      Log.e(TAG, "failed to start recording: ${err.message}")
      scope.launch(Dispatchers.Main) { hide() }
    }
  }

  private fun stopRecordingAndSend() {
    recordingTimeoutJob?.cancel()
    recordingTimeoutJob = null

    if (!recording) return
    recording = false

    try {
      recorder?.stop()
    } catch (_: Throwable) {
      // stop() can throw if no audio was captured
    }
    try {
      recorder?.release()
    } catch (_: Throwable) {}
    recorder = null

    val file = audioFile ?: return
    audioFile = null

    if (!file.exists() || file.length() == 0L) {
      Log.w(TAG, "no audio captured")
      return
    }

    sendAudioToGateway(file)
  }

  private fun sendAudioToGateway(file: File) {
    if (!runtime.isConnected.value) {
      Log.w(TAG, "gateway not connected; discarding audio")
      file.delete()
      return
    }

    scope.launch {
      try {
        val audioBytes = file.readBytes()
        file.delete()
        val base64Audio = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

        val idempotencyKey = UUID.randomUUID().toString()
        val params = buildJsonObject {
          put("sessionKey", JsonPrimitive("main"))
          put("message", JsonPrimitive(""))
          put("thinking", JsonPrimitive("low"))
          put("timeoutMs", JsonPrimitive(30_000))
          put("idempotencyKey", JsonPrimitive(idempotencyKey))
          put(
            "attachments",
            JsonArray(
              listOf(
                buildJsonObject {
                  put("type", JsonPrimitive("audio"))
                  put("mimeType", JsonPrimitive("audio/ogg"))
                  put("fileName", JsonPrimitive("voice_message.ogg"))
                  put("content", JsonPrimitive(base64Audio))
                },
              ),
            ),
          )
        }

        val response = runtime.operatorRequest("chat.send", params.toString())
        Log.d(TAG, "audio sent to gateway: ${response.take(120)}")
      } catch (err: Throwable) {
        Log.e(TAG, "failed to send audio: ${err.message}")
        file.delete()
      }
    }
  }

  private fun cleanup() {
    recordingTimeoutJob?.cancel()
    recordingTimeoutJob = null
    if (recording) {
      recording = false
      try {
        recorder?.stop()
      } catch (_: Throwable) {}
    }
    try {
      recorder?.release()
    } catch (_: Throwable) {}
    recorder = null
    audioFile?.delete()
    audioFile = null
  }
}
