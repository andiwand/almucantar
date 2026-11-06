package at.stefl.almucantar.shared

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/** The privacy policy of all apps, on the page of the family (`web/home/privacy/`). */
const val PRIVACY_POLICY_URL = "https://andiwand.github.io/almucantar/privacy/"

/**
 * A link to the privacy policy. It opens the page in the browser. Google Play asks for this link in
 * each app.
 */
@Composable
fun PrivacyLink(modifier: Modifier = Modifier, color: Color = Color.Unspecified) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { uri.openUri(PRIVACY_POLICY_URL) }, modifier = modifier) {
        Text(
            "Privacy policy",
            color = color,
            fontSize = 12.sp,
            textDecoration = TextDecoration.Underline,
        )
    }
}
