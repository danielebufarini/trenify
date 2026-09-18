package it.danielebufarini.trenify.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object TrenifySpacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val section = 36.dp
    val screenHorizontal = 20.dp
}

object TrenifyShapes {
    val controlSmall = RoundedCornerShape(12.dp)
    val control = RoundedCornerShape(18.dp)
    val card = RoundedCornerShape(24.dp)
    val cardLarge = RoundedCornerShape(32.dp)
    val sheetContent = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)
    val statusPill = RoundedCornerShape(percent = 50)
    val material = Shapes(controlSmall, controlSmall, control, card, cardLarge)
}

object TrenifyElevation {
    val content = 0.dp
    val raised = 1.dp
    val floatingControl = 6.dp
}

private fun text(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight,
    fontSize = size.sp, lineHeight = line.sp,
)

@Immutable
class TrenifyTypography {
    val hero = text(36, 44, FontWeight.Bold)
    val screenTitle = text(28, 36, FontWeight.Bold)
    val sectionTitle = text(20, 28, FontWeight.SemiBold)
    val routeStation = text(20, 28, FontWeight.SemiBold)
    val routeTime = text(30, 38, FontWeight.Bold).copy(fontFeatureSettings = "tnum")
    val trainIdentity = text(16, 24, FontWeight.SemiBold)
    val status = text(14, 20, FontWeight.SemiBold)
    val body = text(16, 24)
    val bodyEmphasized = text(16, 24, FontWeight.SemiBold)
    val metadata = text(14, 20)
    val label = text(14, 20, FontWeight.SemiBold)
    val caption = text(12, 18)
    val material = Typography(
        displayLarge = hero, displayMedium = hero, displaySmall = screenTitle,
        headlineLarge = screenTitle, headlineMedium = screenTitle, headlineSmall = sectionTitle,
        titleLarge = sectionTitle, titleMedium = trainIdentity, titleSmall = label,
        bodyLarge = body, bodyMedium = metadata, bodySmall = caption,
        labelLarge = bodyEmphasized, labelMedium = label, labelSmall = caption,
    )
}
