package ai.openclaw.app.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * System voice interaction service that allows OpenClaw to act as the default
 * digital assistant on Android. Register in AndroidManifest with
 * BIND_VOICE_INTERACTION permission so it appears in
 * Settings > Apps > Default apps > Digital assistant.
 */
class OpenClawVoiceInteractionService : VoiceInteractionService() {
  companion object {
    private const val TAG = "OpenClawVIS"
  }

  override fun onReady() {
    super.onReady()
    Log.d(TAG, "VoiceInteractionService ready")
  }
}

/**
 * Session service that creates [OpenClawVoiceInteractionSession] instances.
 * Referenced from `interaction_service.xml` via `android:sessionService`.
 */
class OpenClawVoiceInteractionSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession {
    return OpenClawVoiceInteractionSession(this)
  }
}
