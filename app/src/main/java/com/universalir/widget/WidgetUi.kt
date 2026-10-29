package com.universalir.widget

import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.universalir.R

/** Rounded, tinted background that also works below Android 12 (where cornerRadius is ignored). */
fun GlanceModifier.roundedBackground(color: ColorProvider, @DrawableRes shape: Int = R.drawable.widget_btn): GlanceModifier =
    background(ImageProvider(shape), colorFilter = ColorFilter.tint(color))

/** Widget root: system corner radius on Android 12+, theme background, comfortable padding. */
@Composable
fun WidgetContainer(padding: Dp = 12.dp, content: @Composable () -> Unit) {
    var modifier = GlanceModifier.fillMaxSize().appWidgetBackground()
    modifier = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        modifier.cornerRadius(android.R.dimen.system_app_widget_background_radius).background(GlanceTheme.colors.widgetBackground)
    } else {
        modifier.roundedBackground(GlanceTheme.colors.widgetBackground, R.drawable.widget_bg)
    }
    Box(modifier.padding(padding)) { content() }
}

/** Gradient device badge with a white icon (mirrors DeviceAvatar). */
@Composable
fun AvatarBadge(device: WidgetDevice, size: Dp) {
    Box(GlanceModifier.size(size).background(ImageProvider(device.gradient)), contentAlignment = Alignment.Center) {
        Image(ImageProvider(device.icon), contentDescription = null, modifier = GlanceModifier.size(size * 0.56f),
            colorFilter = ColorFilter.tint(ColorProvider(WidgetColors.White)))
    }
}

/** Avatar + name; tapping it opens the app on this device. [trailing] sits at the end (e.g. AC temperature). */
@Composable
fun DeviceHeader(device: WidgetDevice, onClick: Action, avatar: Dp = 30.dp, trailing: @Composable () -> Unit = {}) {
    Row(GlanceModifier.fillMaxWidth().clickable(onClick), verticalAlignment = Alignment.CenterVertically) {
        AvatarBadge(device, avatar)
        Spacer(GlanceModifier.width(10.dp))
        Text(device.name, maxLines = 1, modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold))
        trailing()
    }
}

/** A remote key: rounded tile with an icon and optional tiny caption. Disabled keys have no action. */
@Composable
fun KeyTile(
    @DrawableRes icon: Int,
    description: String,
    onClick: Action?,
    modifier: GlanceModifier = GlanceModifier,
    caption: String? = null,
    emphasized: Boolean = false,
    height: Dp = 48.dp,
) {
    val colors = GlanceTheme.colors
    val bg = if (emphasized) colors.primary else colors.secondaryContainer
    val fg = when {
        onClick == null -> colors.outline
        emphasized -> colors.onPrimary
        else -> colors.onSecondaryContainer
    }
    var m = modifier.height(height).roundedBackground(bg)
    if (onClick != null) m = m.clickable(onClick)
    Box(m, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            if (icon != 0) {
                Image(ImageProvider(icon), contentDescription = description,
                    modifier = GlanceModifier.size(if (caption != null) 18.dp else 22.dp), colorFilter = ColorFilter.tint(fg))
            }
            if (caption != null) {
                Text(caption, maxLines = 1, style = TextStyle(color = fg, fontSize = if (icon == 0) 14.sp else 10.sp,
                    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
            }
        }
    }
}

@Composable
fun CenteredMessage(text: String, actionLabel: String?, onClick: Action?) {
    val colors = GlanceTheme.colors
    Column(GlanceModifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = TextStyle(color = colors.onSurface, fontSize = 14.sp, textAlign = TextAlign.Center))
        if (actionLabel != null && onClick != null) {
            Spacer(GlanceModifier.height(10.dp))
            PillButton(actionLabel, onClick)
        }
    }
}

@Composable
fun PillButton(label: String, onClick: Action) {
    val colors = GlanceTheme.colors
    Box(GlanceModifier.height(40.dp).roundedBackground(colors.primary).clickable(onClick).padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center) {
        Text(label, maxLines = 1, style = TextStyle(color = colors.onPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
fun widgetString(id: Int, vararg args: Any): String = LocalContext.current.getString(id, *args)

@Composable
fun SmallText(text: String, size: TextUnit = 12.sp, modifier: GlanceModifier = GlanceModifier) {
    Text(text, maxLines = 1, modifier = modifier, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = size))
}
