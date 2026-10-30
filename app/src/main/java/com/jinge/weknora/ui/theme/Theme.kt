package com.jinge.weknora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================================================
 * 设计语言
 *
 * 三条规则，全 App 一致遵守：
 *
 *   1. 三层表面
 *      background（页面底，浅紫灰） → surface（卡片，纯白）
 *      → surfaceContainerHigh（内嵌块，如思考过程 / 代码块）
 *      浅色模式下用「描边 + 底色差」表达层次，不靠阴影 ——
 *      tonalElevation 在浅色下几乎看不见，靠它分层等于没分。
 *
 *   2. 颜色即语义
 *      可交互 = primary 系（紫）｜纯展示 = 中性灰｜状态 = 绿/蓝/红/灰
 *      同一屏不再出现四五种深浅不一的紫，谁也说不清哪个能点。
 *
 *   3. 圆角只有两档
 *      容器 16、小件（chip / 气泡 / 输入框内元素）10。
 *      之前 8/10/12/16/18/22/24/28 混着用，一屏能数出五种。
 * ============================================================ */

/** 品牌色：与启动器图标同源（图标底 #342E67 → #642894） */
object Brand {
    /** 顶栏底色。图标是深紫，顶栏也必须是深紫 —— 否则打开 App 和桌面图标像两个东西 */
    val TopBarLight = Color(0xFF26215C)
    val TopBarDark = Color(0xFF171334)
    /** 顶栏上的文字与图标 */
    val OnTopBar = Color(0xFFFFFFFF)
    /** 副标题：白字 72% 在深紫底上约 5.4:1，过 WCAG AA */
    const val ON_TOP_BAR_SECONDARY_ALPHA = 0.72f
    /** 强调色（机器人图标高亮、进度条等） */
    val Accent = Color(0xFF7F77DD)
    val AccentDark = Color(0xFFCBB8FF)
}

@Composable
fun brandTopBarColor(): Color =
    if (isSystemInDarkTheme()) Brand.TopBarDark else Brand.TopBarLight

/**
 * 品牌标识方块的圆角。
 *
 * 顶栏 28dp、配置页 44dp —— 两个都是「和桌面图标同款的小紫方块」，
 * 但**不能吃 shapes token**：token 是绝对尺寸，同一档套在 28dp 和 44dp 上
 * 出来的比例完全不同（8dp 在 28dp 上是 29%，在 44dp 上只有 18%，看着像两种东西）。
 * 按边长等比给（≈28%）才是一致的 —— 与 Android 自适应图标的圆角比例同量级。
 */
fun brandMarkShape(size: Dp): RoundedCornerShape = RoundedCornerShape(size * 0.28f)

private val BrandPrimary = Color(0xFF5B40C4)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8DEFF),
    onPrimaryContainer = Color(0xFF1E0B4D),
    secondary = Color(0xFF6B4FA8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE7FB),
    onSecondaryContainer = Color(0xFF241352),
    tertiary = Color(0xFF2E6BB8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6F1FB),
    onTertiaryContainer = Color(0xFF0C447C),
    // 页面底比卡片深半档，卡片才浮得起来
    background = Color(0xFFF7F5FB),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1922),
    onSurfaceVariant = Color(0xFF5A5570),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F5FB),
    surfaceContainer = Color(0xFFF2EFF9),
    surfaceContainerHigh = Color(0xFFECE8F6),
    surfaceContainerHighest = Color(0xFFE5E0F0),
    outline = Color(0xFF7C7690),
    outlineVariant = Color(0xFFDDD8EA),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFCEBEB),
    onErrorContainer = Color(0xFF7A1F1F)
)

private val DarkColors = darkColorScheme(
    primary = Brand.AccentDark,
    onPrimary = Color(0xFF2C0A6B),
    primaryContainer = Color(0xFF453080),
    onPrimaryContainer = Color(0xFFE8DEFF),
    secondary = Color(0xFFD4B8F0),
    onSecondary = Color(0xFF34174E),
    secondaryContainer = Color(0xFF3A2C58),
    onSecondaryContainer = Color(0xFFEDE7FB),
    tertiary = Color(0xFF9CC3FF),
    onTertiary = Color(0xFF0A2F5C),
    tertiaryContainer = Color(0xFF1C3D63),
    onTertiaryContainer = Color(0xFFD6E7FF),
    background = Color(0xFF100E16),
    surface = Color(0xFF17151F),
    onSurface = Color(0xFFE7E2F0),
    onSurfaceVariant = Color(0xFFB9B2C9),
    surfaceContainerLowest = Color(0xFF0F0D14),
    surfaceContainerLow = Color(0xFF1A1724),
    surfaceContainer = Color(0xFF1F1C29),
    surfaceContainerHigh = Color(0xFF26222F),
    surfaceContainerHighest = Color(0xFF2E2939),
    outline = Color(0xFF8E87A3),
    outlineVariant = Color(0xFF423C52),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF5C1A1A),
    onErrorContainer = Color(0xFFFFDAD6)
)

/**
 * 语义色补充：Material3 只有 error，缺「成功 / 中性」两个语义位，
 * 而文档页恰恰最需要它们（已就绪 / 失败 / 已取消 三态一眼分辨）。
 */
@Composable
fun successContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF1F3A22) else Color(0xFFEAF3DE)

@Composable
fun onSuccessContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFFC0DD97) else Color(0xFF3B6D11)

@Composable
fun neutralContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2A2720) else Color(0xFFF1EFE8)

@Composable
fun onNeutralContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFFC9C6BA) else Color(0xFF55534B)

/**
 * 圆角四档，全 App 只允许这四个值。
 *
 * **页面里不要再裸写 `RoundedCornerShape(12.dp)` 这类数字** ——
 * 之前一屏能数出 6 / 8 / 10 / 12 / 16 / 24 / 28 七种圆角，
 * 看着「哪里不对劲但说不上来」就是它造成的。一律从这里取：
 *
 *   extraSmall   8   类型角标 / 状态 pill
 *                    （只有 30dp 高的小块，用 10 会显得像糖果）
 *   small       10   chip / 气泡里的内嵌块（工具 chip、思考过程、附件条）
 *   medium      14   列表项卡片 / 输入框
 *   large       16   面板 / 气泡本体（容器档）
 *
 * 特例只有两处，都在注释里写明了：消息气泡贴着自己那侧的尖角用 4dp
 * （方向指示），以及品牌方块（与桌面图标同比例的圆角方块）。
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp)
)

/**
 * 字体：默认 Roboto 渲中文行高偏紧、字重偏虚。
 * 只调行高与字重，不换字体族 —— 中文要换字体得打包字体文件，
 * 体积代价不值得（系统自带的思源/苹方已经够好）。
 */
private val baseType = Typography()

private val AppTypography = Typography(
    headlineMedium = baseType.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 36.sp
    ),
    titleLarge = baseType.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 28.sp
    ),
    titleMedium = baseType.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp
    ),
    titleSmall = baseType.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = baseType.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = baseType.bodyMedium.copy(lineHeight = 21.sp),
    bodySmall = baseType.bodySmall.copy(lineHeight = 18.sp),
    labelLarge = baseType.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = baseType.labelMedium.copy(letterSpacing = 0.3.sp),
    labelSmall = baseType.labelSmall.copy(letterSpacing = 0.3.sp)
)

@Composable
fun WeKnoraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
