package app.protocolvoice.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import android.app.Activity

/**
 * ProtocolVoice — премиум-тёмная тема в стиле Linear / Notion / Vercel.
 *
 * Дизайн-философия:
 * - **Always dark** — не подстраивается под системную тему. Premium-tools
 *   (Linear, Vercel) всегда тёмные, и это часть бренда.
 * - **Brand-colors** соответствуют иконке: blue #3B82F6 → purple #8B5CF6.
 * - **Surface hierarchy** через elevation overlay: surface < surfaceContainer
 *   < surfaceContainerHigh — даёт depth без shadow'ов.
 * - **Status bar transparent** — для edge-to-edge experience.
 */

// ────────────────────────────────────────────────────────────────────────
// Brand colors (соответствуют иконке)
// ────────────────────────────────────────────────────────────────────────

/** Brand blue — основной для primary actions, активных элементов. */
val BrandBlue = Color(0xFF3B82F6)
/** Brand purple — для accent / secondary. */
val BrandPurple = Color(0xFF8B5CF6)
/** Brand cyan — для подсветки live-элементов (recording dot, waveform). */
val BrandCyan = Color(0xFF06B6D4)
/** Recording red — только для recording state. */
val RecordingRed = Color(0xFFEF4444)

// ────────────────────────────────────────────────────────────────────────
// Surface palette (тёмная иерархия)
// ────────────────────────────────────────────────────────────────────────

/** Самый тёмный — background всего приложения. */
private val Surface0 = Color(0xFF0A0E1A)
/** Surface — контейнеры, карточки нижнего уровня. */
private val Surface1 = Color(0xFF111827)
/** SurfaceContainer — карточки сегментов. */
private val Surface2 = Color(0xFF1E293B)
/** SurfaceContainerHigh — поднятые элементы (modal sheets, dialogs). */
private val Surface3 = Color(0xFF334155)
/** SurfaceContainerHighest — кнопки на тёмном, вспомогательные. */
private val Surface4 = Color(0xFF475569)

// ────────────────────────────────────────────────────────────────────────
// Text palette
// ────────────────────────────────────────────────────────────────────────

/** Основной текст — почти-белый. */
private val TextPrimary = Color(0xFFF1F5F9)
/** Вторичный текст — для подсказок, меток. */
private val TextSecondary = Color(0xFF94A3B8)
/** Третичный — отключённое, edge cases. */
private val TextTertiary = Color(0xFF64748B)

// ────────────────────────────────────────────────────────────────────────
// Material3 ColorScheme
// ────────────────────────────────────────────────────────────────────────

private val ProtocolVoiceColorScheme = darkColorScheme(
    // Primary — brand blue для главных CTA
    primary             = BrandBlue,
    onPrimary           = Color.White,
    primaryContainer    = Color(0xFF1E40AF),    // тёмно-синий для container'ов
    onPrimaryContainer  = Color(0xFFDBEAFE),

    // Secondary — brand purple для accent
    secondary           = BrandPurple,
    onSecondary         = Color.White,
    secondaryContainer  = Color(0xFF5B21B6),
    onSecondaryContainer= Color(0xFFEDE9FE),

    // Tertiary — cyan для подсветки live-элементов
    tertiary            = BrandCyan,
    onTertiary          = Color.White,
    tertiaryContainer   = Color(0xFF0E7490),
    onTertiaryContainer = Color(0xFFCFFAFE),

    // Error — мягкий red, не агрессивный
    error               = RecordingRed,
    onError             = Color.White,
    errorContainer      = Color(0xFF991B1B),
    onErrorContainer    = Color(0xFFFEE2E2),

    // Surface hierarchy
    background          = Surface0,
    onBackground        = TextPrimary,
    surface             = Surface1,
    onSurface           = TextPrimary,
    surfaceVariant      = Surface2,
    onSurfaceVariant    = TextSecondary,
    surfaceContainer    = Surface2,
    surfaceContainerLow = Surface1,
    surfaceContainerHigh= Surface3,
    surfaceContainerHighest = Surface4,

    // Outline / borders
    outline             = Color(0xFF334155),
    outlineVariant      = Color(0xFF1E293B),

    // Inverse (для tooltips на тёмном)
    inverseSurface      = TextPrimary,
    inverseOnSurface    = Surface0,
    inversePrimary      = Color(0xFF1E40AF),

    // Scrim (для modal overlays)
    scrim               = Color(0xFF000000),
)

// ────────────────────────────────────────────────────────────────────────
// Typography (Material3 customised)
// ────────────────────────────────────────────────────────────────────────

/** Дефолтный шрифт Roboto — без custom-fonts (минимальный APK).
 *  Roboto на современных Android выглядит чисто и нейтрально. */
private val ProtocolVoiceTypography = Typography(
    // Display — для splash, очень крупные акценты
    displayLarge = TextStyle(
        fontSize = 48.sp,
        lineHeight = 56.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontSize = 36.sp,
        lineHeight = 44.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),

    // Headline — секционные заголовки
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 32.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    headlineSmall = TextStyle(
        fontSize = 20.sp,
        lineHeight = 28.sp,
        fontWeight = FontWeight.SemiBold,
    ),

    // Title — заголовки карточек
    titleLarge = TextStyle(
        fontSize = 18.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),
    titleSmall = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.1.sp,
    ),

    // Body — основной текст
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 24.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.15.sp,
    ),
    bodySmall = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.2.sp,
    ),

    // Label — кнопки, чипы
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontSize = 12.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontSize = 11.sp,
        lineHeight = 16.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.5.sp,
    ),
)

// ────────────────────────────────────────────────────────────────────────
// Shapes — все скругления через единый стиль
// ────────────────────────────────────────────────────────────────────────

private val ProtocolVoiceShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

// ────────────────────────────────────────────────────────────────────────
// Composable Theme
// ────────────────────────────────────────────────────────────────────────

@Composable
fun InterviewTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // Edge-to-edge: статус-бар прозрачный, иконки светлые (тёмная тема)
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
            // Светлые иконки в status bar (потому что фон тёмный)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }
    MaterialTheme(
        colorScheme = ProtocolVoiceColorScheme,
        typography  = ProtocolVoiceTypography,
        shapes      = ProtocolVoiceShapes,
        content     = content,
    )
}
