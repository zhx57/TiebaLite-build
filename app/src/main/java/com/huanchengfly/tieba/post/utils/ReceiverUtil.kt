package com.huanchengfly.tieba.post.utils

import android.content.IntentFilter

fun newIntentFilter(vararg actions: String) =
    IntentFilter().apply {
        actions.forEach(::addAction)
    }