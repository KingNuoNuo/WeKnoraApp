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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ============================================================
 * 设计语言 · 「墨与信号」(Ink & Signal)
 *
 * 不做 AI 味的紫蓝渐变，也不做霓虹毛玻璃。这一版把界面压成
 * 三样东西：**墨黑的框、纸白的面、一种信号色**。
 *
 *   墨 (ink)     顶栏 / 主文字     —— 像技术手册的封面与正文
 *   纸 (paper)   页面底 / 卡片     —— 暖白，长时间看不刺眼
 *   信号 (signal) 青绿 #0F6B63     —— 只有「能点、选中、正在发生」才用
 *
 * 三条规则：
 *
 *   1. 颜色即语义
 *      可交互 = signal 青｜纯展示 = 中性墨灰｜状态 = 绿/蓝/红/灰
 *      同屏不再出现一窝深浅不一的紫，谁也说不清哪个能点。
 *
 *   2. 层次靠「纸的深浅 + 一根细线」，不靠阴影
 *      background（纸灰） → surface（纯白卡片）→ surfaceContainer（内嵌块）
 *      浅色模式下 tonalElevation 几乎看不见，用它在分层等于没分。
 *
 *   3. 圆角只有四档：8 / 10 / 12 / 16，从 shapes token 取，不裸写数字。
 *      数字、ID、扩展名一律走等宽字体（[Mono]），像仪表读数一样对齐。
 * ============================================================ */

/** 品牌：深墨绿的顶栏 + 一点信号青。与启动器图标同一套色。 */
object Brand {
    /** 顶栏底色。近黑带一点青绿，压得住白字，也不跟页面纸白打架 */
    val TopBarLight = Color(0xFF12201E)
    val TopBarDark = Color(0xFF16211E)
    /** 顶栏上的文字与图标 */
    val OnTopBar = Color(0xFFF4F7F5)
    /** 副标题：白字 68% 在墨底上约 6:1，过 WCAG AA */
    const val ON_TOP_BAR_SECONDARY_ALPHA = 0.68f
    /** 品牌标底色（顶栏小方块 / 配置页大方块） */
    val Accent = Color(0xFF2FA99B)
    val AccentDark = Color(0xFF5FD3C6)
}

@Composable
fun brandTopBarColor(): Color =
    if (isSystemInDarkTheme()) Brand.TopBarDark else Brand.TopBarLight

/**
 * 品牌标识方块的圆角。
 *
 * 顶栏 32dp、配置页 52dp —— 都是「和桌面图标同款的小方块」，
 * 但**不能吃 shapes token**：token 是绝对尺寸，同一档套在 32dp 和 52dp 上
 * 出来的比例完全不同。按边长等比给（≈28%）才是一致的。
 */
fun brandMarkShape(size: Dp): RoundedCornerShape = RoundedCornerShape(size * 0.28f)

/** 数字 / ID / 扩展名用的等宽字体：像仪表读数，天然对齐 */
val Mono = FontFamily.Monospace

/** 等宽 + 收紧字距的小号文字，给角标、计数、ID 用 */
fun monoStyle(base: TextStyle, weight: FontWeight = FontWeight.Medium): TextStyle =
    base.copy(fontFamily = Mono, fontWeight = weight, letterSpacing = 0.2.sp)

private val Signal = Color(0xFF0F6B63)

private val LightColors = lightColorScheme(
    primary = Signal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5EDE8),
    onPrimaryContainer = Color(0xFF073B36),
    secondary = Color(0xFF41615C),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2ECE8),
    onSecondaryContainer = Color(0xFF1D332F),
    tertiary = Color(0xFF1B5FA8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE2EDF9),
    onTertiaryContainer = Color(0xFF0B3766),
    // 页面底是暖纸灰，卡片才是纯白 —— 卡片由此「浮」起来，不需要阴影
    background = Color(0xFFF5F3EE),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191C1B),
    onSurfaceVariant = Color(0xFF5C635F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F3),
    surfaceContainer = Color(0xFFF1EEE7),
    surfaceContainerHigh = Color(0xFFE9E5DC),
    surfaceContainerHighest = Color(0xFFE1DCD1),
    outline = Color(0xFF7A827D),
    outlineVariant = Color(0xFFDED9CE),
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFFBE9E7),
    onErrorContainer = Color(0xFF6B1A16)
)

private val DarkColors = darkColorScheme(
    primary = Brand.AccentDark,
    onPrimary = Color(0xFF00312B),
    primaryContainer = Color(0xFF14524B),
    onPrimaryContainer = Color(0xFFBDEDE6),
    secondary = Color(0xFFA9C7C1),
    onSecondary = Color(0xFF13322D),
    secondaryContainer = Color(0xFF253B37),
    onSecondaryContainer = Color(0xFFDDEAE6),
    tertiary = Color(0xFF9CC7F5),
    onTertiary = Color(0xFF0A2F5C),
    tertiaryContainer = Color(0xFF1B3D60),
    onTertiaryContainer = Color(0xFFD6E7FF),
    background = Color(0xFF0E1211),
    surface = Color(0xFF161B19),
    onSurface = Color(0xFFE5E8E4),
    onSurfaceVariant = Color(0xFFA8B0AB),
    surfaceContainerLowest = Color(0xFF0B0F0E),
    surfaceContainerLow = Color(0xFF121716),
    surfaceContainer = Color(0xFF1B211F),
    surfaceContainerHigh = Color(0xFF232A27),
    surfaceContainerHighest = Color(0xFF2C332F),
    outline = Color(0xFF77807B),
    outlineVariant = Color(0xFF3A423E),
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
    if (isSystemInDarkTheme()) Color(0xFF1B3A26) else Color(0xFFE2F0E3)

@Composable
fun onSuccessContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFFA9D8B2) else Color(0xFF1E5B31)

@Composable
fun neutralContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFF2A2E2B) else Color(0xFFEDEAE3)

@Composable
fun onNeutralContainer(): Color =
    if (isSystemInDarkTheme()) Color(0xFFC2C7C1) else Color(0xFF565D57)

/**
 * 圆角四档，全 App 只允许这四个值。
 *
 *   extraSmall   8   类型角标 / 状态 pill
 *   small       10   chip / 气泡里的内嵌块（工具 chip、思考过程、附件条）
 *   medium      12   列表项卡片 / 输入框 / 搜索条
 *   large       16   面板 / 气泡本体（容器档）
 *
 * 特例只有两处：消息气泡贴着自己那侧的尖角用 4dp（方向指示），
 * 以及品牌方块（与桌面图标同比例的圆角方块）。
 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

/**
 * 字体：默认 Roboto 渲中文行高偏紧、字重偏虚。
 * 只调行高与字重，不换字体族 —— 中文要换字体得打包字体文件，
 * 体积代价不值得（系统自带的思源/苹方已经够好）。
 *
 * 标题刻意比 M3 默认再紧一档、字重再实一档：这一版的信息层级靠
 * 「粗细差」拉，不靠字号堆。
 */
private val baseType = Typography()

private val AppTypography = Typography(
    headlineSmall = baseType.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 30.sp
    ),
    headlineMedium = baseType.headlineMedium.copy(
        fontWeight = FontWeight.SemiBold,
        lineHeight = 34.sp
    ),
    titleLarge = baseType.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 28.sp
    ),
    titleMedium = baseType.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 23.sp
    ),
    titleSmall = baseType.titleSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = baseType.bodyLarge.copy(fontSize = 15.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = baseType.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = baseType.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
    labelLarge = baseType.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelMedium = baseType.labelMedium.copy(fontSize = 12.sp, letterSpacing = 0.3.sp),
    labelSmall = baseType.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.3.sp)
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
