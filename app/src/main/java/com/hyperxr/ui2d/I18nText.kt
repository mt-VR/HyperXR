package com.hyperxr.ui2d

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Material's Text with the text translated into the chosen language (com.hyperxr.i18n.I18n). The
 * 2D screens use this one: their texts are written in Portuguese in the code.
 */
@Composable
fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) = androidx.compose.material3.Text(
    text = com.hyperxr.i18n.I18n.t(text), modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle, fontWeight = fontWeight,
    fontFamily = fontFamily, letterSpacing = letterSpacing, textDecoration = textDecoration, textAlign = textAlign, lineHeight = lineHeight,
    overflow = overflow, softWrap = softWrap, maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout ?: {}, style = style,
)
