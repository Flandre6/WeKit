package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.utils.ForwardIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Feature(
    name = "转发语音",
    categories = ["聊天"],
    description = "在语音消息长按菜单添加转发按钮, 可向好友或群聊批量转发语音"
)
object ForwardVoices : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    private val TAG = This.Class.simpleName

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777010,
                "转发",
                ForwardIcon,
                shouldShow = { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code }
            ) { view, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

                    withContext(Dispatchers.Main) {
                        showComposeDialog(view.context) {
                            ContactsSelector(
                                title = "选择转发对象",
                                contacts = contacts,
                                initialSelectedWxIds = emptySet(),
                                onDismiss = onDismiss,
                                onConfirm = { selectedWxIds ->
                                    if (selectedWxIds.isEmpty()) {
                                        showToast("请选择至少一个联系人")
                                        return@ContactsSelector
                                    }

                                    onDismiss()
                                    forwardVoice(msgInfo, selectedWxIds)
                                }
                            )
                        }
                    }
                }
            }
        )
    }

    private fun forwardVoice(msgInfo: MessageInfo, wxIds: Set<String>) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend("正在转发 ${wxIds.size} 条语音...")

            val voicePath = WeMessageApi.getVoiceFullPath(msgInfo.imagePath!!)
            val durationMs = AudioUtils.getDurationMs(voicePath)
            WeLogger.d(TAG, "forwardVoice: durationMs=$durationMs, targetWxIds=$wxIds")

            wxIds.forEach { wxId ->
                WeLogger.d(TAG, "forwardVoice: sending to $wxId")
                val sent = WeMessageApi.sendVoice(wxId, voicePath, durationMs.toInt())
                WeLogger.d(TAG, "forwardVoice: sendVoice result for $wxId: $sent")
            }

            showToastSuspend("已转发 ${wxIds.size} 条语音")
        }
    }
}
