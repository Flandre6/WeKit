package dev.ujhhgtg.wekit.hooks.items.scripting_js

import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem

@HookItem(name = "触发器：发起请求", categories = ["脚本 (JavaScript)"], description = "发起请求时是否执行 onRequest()")
object OnRequest : SwitchHookItem()
