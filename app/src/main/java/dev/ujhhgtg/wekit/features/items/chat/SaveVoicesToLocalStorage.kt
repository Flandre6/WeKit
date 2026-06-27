package dev.ujhhgtg.wekit.features.items.chat

import dev.ujhhgtg.comptime.This
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.utils.DownloadIcon
import dev.ujhhgtg.wekit.utils.AudioUtils
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToastSuspend
import dev.ujhhgtg.wekit.utils.fs.KnownPaths
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension

@Feature(name = "语音保存到本地", categories = ["聊天"], description = "在语音消息菜单添加保存按钮, 允许将语音文件保存到本地")
object SaveVoicesToLocalStorage : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

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
                777003,
                "存本地",
                DownloadIcon,
                { msgInfo -> msgInfo.typeCode == MessageType.VOICE.code }
            ) { _, _, msgInfo ->
                CoroutineScope(Dispatchers.IO).launch {
                    val encPath = msgInfo.imagePath!!
                    val silkOriginalPath = WeMessageApi.getVoiceFullPath(encPath).asPath
                    val mp3Name = silkOriginalPath.nameWithoutExtension + ".mp3"
                    val silkPath = KnownPaths.downloads / silkOriginalPath.name
                    val pcmPath = KnownPaths.downloads / (silkOriginalPath.nameWithoutExtension + ".pcm")
                    val mp3Path = KnownPaths.downloads / mp3Name

                    runCatching {
                        silkPath.deleteIfExists()
                        silkOriginalPath.copyTo(silkPath, overwrite = true)
                        AudioUtils.silkToPcm(silkPath.absolutePathString(), pcmPath.absolutePathString())
                        AudioUtils.pcmToMp3(pcmPath.absolutePathString(), mp3Path.absolutePathString())
                        pcmPath.deleteIfExists()
                    }.onSuccess {
                        showToastSuspend("已将语音保存到 ${mp3Path.absolutePathString()}")
                    }.onFailure { e ->
                        WeLogger.e(TAG, "failed to save voice to ${mp3Path.absolutePathString()}", e)
                        showToastSuspend("语音保存失败! 查看日志以了解错误详情")
                    }
                }
            }
        )
    }
}
