package com.duophone.app.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import com.duophone.app.stage.TopScreenPhoneMasker

/**
 * 1:1 length-preserving visual transformation that masks digits 4..7 of phone numbers as '*'.
 * Underlying TextField state remains unmasked so dialing, SMS sending, and editing work normally.
 */
val PhoneMaskVisualTransformation = VisualTransformation { text ->
    TransformedText(
        text = AnnotatedString(TopScreenPhoneMasker.mask(text.text)),
        offsetMapping = OffsetMapping.Identity,
    )
}
