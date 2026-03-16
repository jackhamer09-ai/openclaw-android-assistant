package ai.openclaw.app.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ai.openclaw.app.NodeApp

/**
 * Optional fullscreen overlay activity shown when the assistant is triggered.
 * Displays listening/processing/response state. Can be launched from the
 * VoiceInteractionSession if a visual surface is desired.
 */
class OpenClawAssistantActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val runtime = (application as NodeApp).runtime

    setContent {
      MaterialTheme {
        AssistantOverlay(
          isConnected = runtime.isConnected.collectAsState().value,
          statusText = runtime.statusText.collectAsState().value,
        )
      }
    }
  }
}

@Composable
private fun AssistantOverlay(isConnected: Boolean, statusText: String) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black.copy(alpha = 0.85f)),
    contentAlignment = Alignment.Center,
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      PulsingOrb()
      Spacer(modifier = Modifier.height(24.dp))
      Text(
        text = "OpenClaw",
        style = MaterialTheme.typography.headlineMedium,
        color = Color.White,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = if (isConnected) "Listening..." else "Not connected",
        style = MaterialTheme.typography.bodyLarge,
        color = Color.White.copy(alpha = 0.7f),
        textAlign = TextAlign.Center,
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = statusText,
        style = MaterialTheme.typography.bodySmall,
        color = Color.White.copy(alpha = 0.4f),
        textAlign = TextAlign.Center,
      )
    }
  }
}

@Composable
private fun PulsingOrb() {
  val transition = rememberInfiniteTransition(label = "pulse")
  val scale by transition.animateFloat(
    initialValue = 0.8f,
    targetValue = 1.2f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "scale",
  )
  val alpha by transition.animateFloat(
    initialValue = 0.4f,
    targetValue = 1.0f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 1000, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse,
    ),
    label = "alpha",
  )
  Box(
    modifier = Modifier
      .size(80.dp)
      .scale(scale)
      .alpha(alpha)
      .background(Color(0xFF6366F1), shape = CircleShape),
  )
}
