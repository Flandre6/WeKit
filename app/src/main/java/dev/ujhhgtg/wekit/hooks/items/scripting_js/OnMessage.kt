package dev.ujhhgtg.wekit.hooks.items.scripting_js

import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem

@HookItem(name = "触发器：收到消息", categories = ["脚本 (JavaScript)"], description = "收到消息时是否执行 onMessage()")
object OnMessage : SwitchHookItem()
