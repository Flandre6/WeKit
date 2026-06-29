package dev.ujhhgtg.wekit.features.items.debug

import android.content.Context
import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.core.ClickableFeature
import dev.ujhhgtg.wekit.features.core.Feature

@Feature(name = "测试", categories = ["调试"], description = "???")
object Experiments : ClickableFeature() {

    @Suppress("unused")
    private val TAG = This.Class.simpleName

    override val noSwitchWidget = true

    override fun onClick(context: Context) {
    }
}
