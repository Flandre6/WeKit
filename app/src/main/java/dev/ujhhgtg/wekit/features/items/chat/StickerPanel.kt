package dev.ujhhgtg.wekit.features.items.chat

import android.content.ContentResolver
import android.view.View
import android.view.ViewGroup
import dev.ujhhgtg.reflekt.utils.makeAccessible
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.ui.WeCurrentConversationApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.features.items.chat.panel.PanelPaths
import dev.ujhhgtg.wekit.features.items.chat.panel.PickedPanelFile
import dev.ujhhgtg.wekit.features.items.chat.panel.StickerItem
import dev.ujhhgtg.wekit.features.items.chat.panel.listPanelTreeFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelDirectory
import dev.ujhhgtg.wekit.features.items.chat.panel.pickPanelFiles
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxServiceClient
import dev.ujhhgtg.wekit.features.items.chat.panel.service.FunBoxStickerRepository
import dev.ujhhgtg.wekit.features.items.chat.panel.sticker.StickerPanelRepository
import dev.ujhhgtg.wekit.ui.panel.StickerImportMode
import dev.ujhhgtg.wekit.ui.panel.StickerPanelActions
import dev.ujhhgtg.wekit.ui.panel.showStickerPanelSheet
import dev.ujhhgtg.wekit.ui.utils.findViewByChildIndexes
import dev.ujhhgtg.wekit.utils.fs.asPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.absolutePathString
import kotlin.io.path.deleteIfExists
import kotlin.io.path.div
import kotlin.io.path.writeBytes

@Feature(
    name = "表情面板",
    categories = ["聊天"],
    description = "长按表情按钮打开自定义表情面板，可浏览并发送本地 GIF/PNG 表情包\n" +
            "将图片放入 sticker_panel/<包名>/ 目录即可"
)
object StickerPanel : SwitchFeature(), IResolveDex {

    // ChatFooter.l0() is the deobfuscated initSmileyBtn — found by the string reference
    // WeChat emits in its internal tracing framework at ChatFooter.java:4198.
    private val methodInitSmileyBtn by dexMethod {
        searchPackages("com.tencent.mm.pluginsdk.ui.chat")
        matcher {
            usingEqStrings("initSmileyBtn")
        }
    }

    override fun onEnable() {
        methodInitSmileyBtn.hookAfter {
            val chatFooter = thisObject as ViewGroup
            // l0() assigns this.C = the emoji face button (WeImageButton) from R.id.bqr,
            // which lives in the sibling container f207215e — NOT in child[0].
            // Voice and menu buttons both live in child[0], so the emoji button is the
            // WeImageButton whose parent differs from child[0].
            val child0 = chatFooter.findViewByChildIndexes<ViewGroup>(0) ?: return@hookAfter
            val emojiButton = chatFooter.javaClass.declaredFields
                .filter { it.type.simpleName == "WeImageButton" }
                .firstNotNullOfOrNull { f ->
                    f.makeAccessible()
                    (f.get(chatFooter) as? View)?.takeIf { it.parent !== child0 }
                } ?: return@hookAfter

            emojiButton.setOnLongClickListener { v ->
                openPanel(v)
                true
            }
        }
    }

    private fun openPanel(anchor: View) {
        val talker = WeCurrentConversationApi.value
        CoroutineScope(Dispatchers.IO).launch {
            PanelPaths.cleanupStalePanelCache()
            val packs = loadLocalPacks()
            withContext(Dispatchers.Main) {
                showStickerPanelSheet(
                    context = anchor.context,
                    packs = packs,
                    actions = StickerPanelActions(
                        reloadLocal = ::loadLocalPacks,
                        importSticker = { packId, mode, onStarted, onComplete ->
                            when (mode) {
                                StickerImportMode.MULTIPLE_FILES -> pickPanelFiles(
                                    anchor.context,
                                    STICKER_MIME_TYPES,
                                ) { files, activity ->
                                    onStarted()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = importStickerBatch(
                                            packId,
                                            files,
                                            activity.contentResolver,
                                        )
                                        withContext(Dispatchers.Main) {
                                            onComplete(result)
                                            activity.finish()
                                        }
                                    }
                                }

                                StickerImportMode.DIRECTORY -> pickPanelDirectory(anchor.context) { treeUri, activity ->
                                    onStarted()
                                    CoroutineScope(Dispatchers.IO).launch {
                                        val result = runCatching {
                                            listPanelTreeFiles(activity.contentResolver, treeUri)
                                        }.mapCatching { files ->
                                            importStickerBatch(
                                                packId,
                                                files,
                                                activity.contentResolver,
                                            ).getOrThrow()
                                        }
                                        withContext(Dispatchers.Main) {
                                            onComplete(result)
                                            activity.finish()
                                        }
                                    }
                                }
                            }
                        },
                        createPack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.createPack(name) } },
                        renamePack = StickerPanelRepository::renamePack,
                        deletePack = StickerPanelRepository::deletePack,
                        loadOnlinePacks = FunBoxStickerRepository::loadCatalog,
                        loadMyUploads = FunBoxStickerRepository::loadMyUploads,
                        loadOnlineItems = FunBoxStickerRepository::loadPack,
                        searchOnline = FunBoxStickerRepository::searchText,
                        uploadPack = FunBoxStickerRepository::uploadPack,
                        setCustomTitle = StickerPanelRepository::setCustomTitle,
                        deleteSticker = StickerPanelRepository::deleteSticker,
                        ensurePack = { name -> withContext(Dispatchers.IO) { StickerPanelRepository.ensurePack(name) } },
                        saveOnlineSticker = { packId, item -> saveOnlineSticker(packId, item) },
                    ),
                ) { item ->
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val path = resolveStickerPath(item).getOrThrow()
                            val temporary = item.localPath == null
                            try {
                                check(WeMessageApi.sendSticker(talker, path)) { "表情发送失败" }
                                if (temporary) StickerPanelRepository.recordOnlineRecent(item)
                                else StickerPanelRepository.recordRecent(path)
                            } finally {
                                if (temporary) path.asPath.deleteIfExists()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadLocalPacks() = buildList {
        val recents = StickerPanelRepository.getRecents()
        if (recents.items.isNotEmpty()) add(recents)
        addAll(StickerPanelRepository.loadPacks())
    }

    private suspend fun resolveStickerPath(item: StickerItem): Result<String> = withContext(Dispatchers.IO) {
        cancellableResult {
            item.localPath?.let { return@cancellableResult it }
            val objectId = item.remoteObjectId ?: error("没有可用表情对象")
            val bytes = FunBoxServiceClient.downloadObject("image", objectId).getOrThrow()
            require(bytes.isNotEmpty()) { "服务器未返回表情数据" }
            val extension = StickerPanelRepository.detectImageExtension(bytes)
                ?: error("服务器返回了不支持的图片格式")
            val path = PanelPaths.panelCacheDir / "sticker-${UUID.randomUUID()}.$extension"
            path.writeBytes(bytes)
            path.absolutePathString()
        }
    }

    private suspend fun saveOnlineSticker(packId: String, item: StickerItem): Result<Unit> =
        withContext(Dispatchers.IO) {
            cancellableResult {
                if (StickerPanelRepository.hasOnlineSticker(packId, item)) return@cancellableResult
                val path = resolveStickerPath(item).getOrThrow()
                val temporary = item.localPath == null
                try {
                    Files.newInputStream(path.asPath).use { input ->
                        StickerPanelRepository.importOnlineSticker(item, packId, input).getOrThrow()
                    }
                } finally {
                    if (temporary) path.asPath.deleteIfExists()
                }
            }.map { }
        }

    private suspend inline fun <T> cancellableResult(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    private suspend fun importStickerBatch(
        packId: String,
        files: List<PickedPanelFile>,
        resolver: ContentResolver,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val supported = files.filter { StickerPanelRepository.supportsFileName(it.name) }
        if (supported.isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("所选内容中没有支持的图片文件"))
        }

        var imported = 0
        val failures = mutableListOf<Pair<String, Throwable>>()
        supported.forEach { file ->
            runCatching {
                val input = resolver.openInputStream(file.uri) ?: error("无法读取文件")
                input.use {
                    StickerPanelRepository.importSticker(packId, file.name, it).getOrThrow()
                }
            }.onSuccess {
                imported++
            }.onFailure {
                failures += file.name to it
            }
        }

        if (failures.isEmpty()) {
            Result.success(Unit)
        } else {
            val first = failures.first()
            Result.failure(
                IllegalStateException(
                    "已导入 $imported 个，${failures.size} 个失败；${first.first}: " +
                            (first.second.message ?: "未知错误"),
                    first.second,
                ),
            )
        }
    }

    private val STICKER_MIME_TYPES = arrayOf(
        "image/gif",
        "image/png",
        "image/webp",
        "image/jpeg",
        "image/*",
    )
}
