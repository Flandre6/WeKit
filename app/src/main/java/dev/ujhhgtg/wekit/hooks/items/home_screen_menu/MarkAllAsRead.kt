package dev.ujhhgtg.wekit.hooks.items.home_screen_menu

import de.robv.android.xposed.XC_MethodHook
import dev.ujhhgtg.wekit.hooks.api.core.WeConversationApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeHomeScreenPopupMenuApi
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.hooks.core.SwitchHookItem
import dev.ujhhgtg.wekit.ui.utils.CheckCircleIcon
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast

@HookItem(name = "清空未读", categories = ["聊天", "首页右上角菜单"], description = "在首页右上角菜单添加「清空未读」选项，支持清理红点、@、红包等提示")
object MarkAllAsRead : SwitchHookItem(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    private const val TAG = "MarkAllAsRead"

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: XC_MethodHook.MethodHookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777012, "清空未读", CheckCircleIcon
            ) {
                WeConversationApi.markAllAsRead()
            }
        )
    }
}
