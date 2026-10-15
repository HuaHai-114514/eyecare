package com.java.myapplication.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 应用内自绘矢量图标集（v2.3.15 新增）。
 *
 * 起因：底部导航栏原先拿 emoji（⏱️ 💡 📊 ⚙️）当图标。emoji 走系统彩色字体，
 * 大小、基线、配色全凭系统，四个图标视觉重量不齐，观感偏"跳"，也压不住
 * 「原野 + 呼吸」那套低饱和调性。换成单色矢量图标后：
 *
 *  - 颜色交给 Icon 的 tint，随主题走，选中 / 未选中态由 Material 自动过渡；
 *  - 统一 24x24 网格，四个图标视觉重量一致，界面更"简约大气"；
 *  - **不引入 material-icons-extended**：本地 Gradle 缓存没有该依赖，
 *    且它会让包体膨胀几十 MB。这里手工内联所需 path，合计仅几 KB。
 *
 * 所有图标均为 fill 风格、24dp 视口，几何比例沿用 Material Icons 规范。
 */
object EyeIcons {

    /**
     * 计时 —— 秒表：顶部按压柄 + 右上按钮 + 圆表盘 + 12 点方向指针。
     * 对应底部导航「护眼计时」。
     */
    val Timer: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeTimer",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 顶部按压柄
            path(fill = SolidColor(Color.Black)) {
                moveTo(15f, 1f)
                horizontalLineTo(9f)
                verticalLineToRelative(2f)
                horizontalLineToRelative(6f)
                verticalLineTo(1f)
                close()
            }
            // 表盘指针（指向 12 点）
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 14f)
                horizontalLineToRelative(2f)
                verticalLineTo(8f)
                horizontalLineToRelative(-2f)
                verticalLineToRelative(6f)
                close()
            }
            // 表盘圆环 + 右上按钮
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.03f, 7.39f)
                lineToRelative(1.42f, -1.42f)
                curveToRelative(-0.43f, -0.51f, -0.9f, -0.99f, -1.41f, -1.41f)
                lineToRelative(-1.42f, 1.42f)
                curveTo(16.07f, 4.74f, 14.12f, 4f, 12f, 4f)
                curveToRelative(-4.97f, 0f, -9f, 4.03f, -9f, 9f)
                reflectiveCurveToRelative(4.02f, 9f, 9f, 9f)
                reflectiveCurveToRelative(9f, -4.03f, 9f, -9f)
                curveToRelative(0f, -2.12f, -0.74f, -4.07f, -1.97f, -5.61f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-3.87f, 0f, -7f, -3.13f, -7f, -7f)
                reflectiveCurveToRelative(3.13f, -7f, 7f, -7f)
                reflectiveCurveToRelative(7f, 3.13f, 7f, 7f)
                reflectiveCurveToRelative(-3.13f, 7f, -7f, 7f)
                close()
            }
        }.build()
    }

    /**
     * 护眼知识 —— 灯泡：球形灯罩 + 底部螺纹座。
     * 对应底部导航「护眼知识」。
     */
    val Tips: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeTips",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // 灯泡主体
                moveTo(12f, 2f)
                curveTo(8.14f, 2f, 5f, 5.14f, 5f, 9f)
                curveToRelative(0f, 2.38f, 1.19f, 4.47f, 3f, 5.74f)
                verticalLineTo(17f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(6f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-2.26f)
                curveToRelative(1.81f, -1.27f, 3f, -3.36f, 3f, -5.74f)
                curveToRelative(0f, -3.86f, -3.14f, -7f, -7f, -7f)
                close()
                // 底部螺纹座
                moveTo(9f, 21f)
                curveToRelative(0f, 0.55f, 0.45f, 1f, 1f, 1f)
                horizontalLineToRelative(4f)
                curveToRelative(0.55f, 0f, 1f, -0.45f, 1f, -1f)
                verticalLineToRelative(-1f)
                horizontalLineTo(9f)
                verticalLineToRelative(1f)
                close()
            }
        }.build()
    }

    /**
     * 数据报告 —— 三根高低错落的柱子。
     * 对应底部导航「数据报告」。
     */
    val Report: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeReport",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                // 左柱（矮）
                moveTo(5f, 13f)
                horizontalLineTo(8.2f)
                verticalLineTo(20f)
                horizontalLineTo(5f)
                close()
                // 中柱（高）
                moveTo(10.4f, 7f)
                horizontalLineTo(13.6f)
                verticalLineTo(20f)
                horizontalLineTo(10.4f)
                close()
                // 右柱（中）
                moveTo(15.8f, 10f)
                horizontalLineTo(19f)
                verticalLineTo(20f)
                horizontalLineTo(15.8f)
                close()
            }
        }.build()
    }

    /**
     * 设置 —— 齿轮：外齿圈 + 中心轴孔。
     * 用于底部导航「护眼设置」与计时页右上角入口。
     */
    val Settings: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeSettings",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(19.14f, 12.94f)
                curveToRelative(0.04f, -0.3f, 0.06f, -0.61f, 0.06f, -0.94f)
                curveToRelative(0f, -0.32f, -0.02f, -0.64f, -0.07f, -0.94f)
                lineToRelative(2.03f, -1.58f)
                curveToRelative(0.18f, -0.14f, 0.23f, -0.41f, 0.12f, -0.61f)
                lineToRelative(-1.92f, -3.32f)
                curveToRelative(-0.12f, -0.22f, -0.37f, -0.29f, -0.59f, -0.22f)
                lineToRelative(-2.39f, 0.96f)
                curveToRelative(-0.5f, -0.38f, -1.03f, -0.7f, -1.62f, -0.94f)
                lineToRelative(-0.36f, -2.54f)
                curveToRelative(-0.04f, -0.24f, -0.24f, -0.41f, -0.48f, -0.41f)
                horizontalLineToRelative(-3.84f)
                curveToRelative(-0.24f, 0f, -0.43f, 0.17f, -0.47f, 0.41f)
                lineToRelative(-0.36f, 2.54f)
                curveToRelative(-0.59f, 0.24f, -1.13f, 0.57f, -1.62f, 0.94f)
                lineToRelative(-2.39f, -0.96f)
                curveToRelative(-0.22f, -0.08f, -0.47f, 0f, -0.59f, 0.22f)
                lineTo(2.74f, 8.87f)
                curveToRelative(-0.12f, 0.21f, -0.08f, 0.47f, 0.12f, 0.61f)
                lineToRelative(2.03f, 1.58f)
                curveToRelative(-0.05f, 0.3f, -0.09f, 0.63f, -0.09f, 0.94f)
                reflectiveCurveToRelative(0.02f, 0.64f, 0.07f, 0.94f)
                lineToRelative(-2.03f, 1.58f)
                curveToRelative(-0.18f, 0.14f, -0.23f, 0.41f, -0.12f, 0.61f)
                lineToRelative(1.92f, 3.32f)
                curveToRelative(0.12f, 0.22f, 0.37f, 0.29f, 0.59f, 0.22f)
                lineToRelative(2.39f, -0.96f)
                curveToRelative(0.5f, 0.38f, 1.03f, 0.7f, 1.62f, 0.94f)
                lineToRelative(0.36f, 2.54f)
                curveToRelative(0.05f, 0.24f, 0.24f, 0.41f, 0.48f, 0.41f)
                horizontalLineToRelative(3.84f)
                curveToRelative(0.24f, 0f, 0.44f, -0.17f, 0.47f, -0.41f)
                lineToRelative(0.36f, -2.54f)
                curveToRelative(0.59f, -0.24f, 1.13f, -0.56f, 1.62f, -0.94f)
                lineToRelative(2.39f, 0.96f)
                curveToRelative(0.22f, 0.08f, 0.47f, 0f, 0.59f, -0.22f)
                lineToRelative(1.92f, -3.32f)
                curveToRelative(0.12f, -0.22f, 0.07f, -0.47f, -0.12f, -0.61f)
                lineToRelative(-2.01f, -1.58f)
                close()
                moveTo(12f, 15.6f)
                curveToRelative(-1.98f, 0f, -3.6f, -1.62f, -3.6f, -3.6f)
                reflectiveCurveToRelative(1.62f, -3.6f, 3.6f, -3.6f)
                reflectiveCurveToRelative(3.6f, 1.62f, 3.6f, 3.6f)
                reflectiveCurveToRelative(-1.62f, 3.6f, -3.6f, 3.6f)
                close()
            }
        }.build()
    }

    /**
     * 下拉箭头 —— 一个向下的实心三角。给设置页的音源下拉框做指示符。
     */
    val ArrowDropDown: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeArrowDropDown",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            path(fill = SolidColor(Color.Black)) {
                moveTo(7f, 10f)
                lineToRelative(5f, 5f)
                lineToRelative(5f, -5f)
                close()
            }
        }.build()
    }

    /**
     * 通过 —— 圆底 + 内嵌对勾（挖空）。
     *
     * v2.6.20：自检页原先用 emoji「✅」表示「这一项没问题」。emoji 走系统彩色
     * 字体，各处字形与基线不一致，与底部导航当初换掉 emoji 是同一个理由。
     * 改为此图标后颜色交给 tint，可随主题与状态切换。
     */
    val CheckCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeCheckCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 外圆
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveToRelative(0f, 5.52f, 4.48f, 10f, 10f, 10f)
                curveToRelative(5.52f, 0f, 10f, -4.48f, 10f, -10f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
                curveToRelative(0f, -4.41f, 3.59f, -8f, 8f, -8f)
                curveToRelative(4.41f, 0f, 8f, 3.59f, 8f, 8f)
                curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
                close()
            }
            // 内嵌对勾
            path(fill = SolidColor(Color.Black)) {
                moveTo(10.2f, 16.4f)
                lineTo(5.4f, 11.6f)
                lineTo(6.8f, 10.2f)
                lineTo(10.2f, 13.6f)
                lineTo(17.2f, 6.6f)
                lineTo(18.6f, 8f)
                close()
            }
        }.build()
    }

    /**
     * 需注意 —— 圆底 + 内嵌感叹号（挖空）。
     *
     * v2.6.20：替换自检页原用的 emoji「⚠️」。
     * 语义上刻意选圆形而非三角：这里表达的是「有一条待办」，
     * 不是「危险警告」，圆形与其它通过项保持同一视觉重量。
     */
    val AlertCircle: ImageVector by lazy {
        ImageVector.Builder(
            name = "EyeAlertCircle",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f
        ).apply {
            // 外圆
            path(fill = SolidColor(Color.Black)) {
                moveTo(12f, 2f)
                curveTo(6.48f, 2f, 2f, 6.48f, 2f, 12f)
                curveToRelative(0f, 5.52f, 4.48f, 10f, 10f, 10f)
                curveToRelative(5.52f, 0f, 10f, -4.48f, 10f, -10f)
                curveTo(22f, 6.48f, 17.52f, 2f, 12f, 2f)
                close()
                moveTo(12f, 20f)
                curveToRelative(-4.41f, 0f, -8f, -3.59f, -8f, -8f)
                curveToRelative(0f, -4.41f, 3.59f, -8f, 8f, -8f)
                curveToRelative(4.41f, 0f, 8f, 3.59f, 8f, 8f)
                curveTo(20f, 16.41f, 16.41f, 20f, 12f, 20f)
                close()
            }
            // 感叹号竖条
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 7f)
                lineTo(13f, 7f)
                lineTo(13f, 13f)
                lineTo(11f, 13f)
                close()
            }
            // 感叹号圆点
            path(fill = SolidColor(Color.Black)) {
                moveTo(11f, 15f)
                lineTo(13f, 15f)
                lineTo(13f, 17f)
                lineTo(11f, 17f)
                close()
            }
        }.build()
    }
}