package com.pdfpaper.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/** Walks the context chain to the Activity that hosts a Compose view. */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
