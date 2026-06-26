package dev.ujhhgtg.wekit.hooks.items.moments

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.tencent.mm.plugin.sns.ui.SnsUserUI
import com.tencent.mm.plugin.sns.ui.improve.ImproveSnsTimelineUI
import com.tencent.mm.view.recyclerview.WxRecyclerView
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexField
import dev.ujhhgtg.wekit.hooks.api.core.WeApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.hooks.api.core.WeDatabaseListenerApi
import dev.ujhhgtg.wekit.hooks.api.ui.WeMomentsApi
import dev.ujhhgtg.wekit.hooks.core.ClickableHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.preferences.WePrefs
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.content.ContactsSelector
import dev.ujhhgtg.wekit.ui.content.DefaultColumn
import dev.ujhhgtg.wekit.ui.content.TextButton
import dev.ujhhgtg.wekit.ui.utils.findViewWhich
import dev.ujhhgtg.wekit.ui.utils.rootView
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.reflekt.reflekt
import org.luckypray.dexkit.DexKitBridge
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

@HookItem(
    name = "自动点赞指定好友",
    categories = ["朋友圈"],
    description = "仅对指定好友发送真实朋友圈点赞请求"
)
object AutoLikeMoments : ClickableHookItem(),
    IResolveDex,
    WeDatabaseListenerApi.IInsertListener,
    WeDatabaseListenerApi.IUpdateListener {

    private val TAG = "AutoLikeMoments"

    private const val KEY_TARGETS = "moments_auto_like_targets"
    private const val KEY_MODE = "moments_auto_like_mode"
    private const val KEY_ACTION = "moments_auto_like_action"
    private const val KEY_ACTION_DELAY_MS = "moments_auto_like_action_delay_ms"
    private const val MODE_WHEN_SEEN = 0
    private const val MODE_ALL_LOADED = 1
    private const val ACTION_LIKE = 0
    private const val ACTION_UNLIKE = 1
    private const val RETRY_INTERVAL_MS = 30_000L
    private const val MAX_ACTION_DELAY_MS = 300_000L

    private val handledSnsIds = ConcurrentHashMap.newKeySet<String>()
    private val lastAttemptAt = ConcurrentHashMap<String, Long>()
    private val attachedRoots = Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())
    private val actionLock = Any()

    @Volatile
    private var lastActionSentAt = 0L

    @Volatile
    private var timelineHooksInstalled = false

    override fun onEnable() {
        WeDatabaseListenerApi.addListener(this)
        installTimelineHooks()
        if (currentMode() == MODE_ALL_LOADED) {
            scanCachedTargetMoments()
        }
    }

    override fun onDisable() {
        WeDatabaseListenerApi.removeListener(this)
    }

    override fun onClick(context: Context) {
        showComposeDialog(context) {
            var selectedTargets by remember { mutableStateOf(targetWxIds()) }
            var mode by remember { mutableStateOf(WePrefs.getIntOrDef(KEY_MODE, MODE_WHEN_SEEN)) }
            var action by remember { mutableStateOf(WePrefs.getIntOrDef(KEY_ACTION, ACTION_LIKE)) }
            var delayInput by remember { mutableStateOf(actionDelayMs().toString()) }

            AlertDialogContent(
                title = { Text("自动点赞朋友圈") },
                text = {
                    DefaultColumn(Modifier.verticalScroll(rememberScrollState())) {
                        ListItem(
                            headlineContent = { Text("指定好友") },
                            supportingContent = { Text("已选择 ${selectedTargets.size} 个好友") },
                            modifier = Modifier.clickable {
                                val friends = WeDatabaseApi.getFriends()
                                showComposeDialog(context) {
                                    ContactsSelector(
                                        title = "选择指定好友",
                                        contacts = friends,
                                        initialSelectedWxIds = selectedTargets,
                                        onDismiss = onDismiss,
                                        onConfirm = { wxIds ->
                                            selectedTargets = normalizeWxIds(wxIds)
                                            onDismiss()
                                        }
                                    )
                                }
                            }
                        )
                        ModeRow(
                            title = "点赞模式",
                            summary = "只给指定好友的朋友圈点赞。",
                            checked = action == ACTION_LIKE,
                            onClick = { action = ACTION_LIKE }
                        )
                        if (action == ACTION_LIKE) {
                            ModeRow(
                                title = "刷到时点赞",
                                summary = "刷朋友圈时，只有刷到指定好友的内容才会点赞。",
                                checked = mode == MODE_WHEN_SEEN,
                                onClick = { mode = MODE_WHEN_SEEN }
                            )
                            ModeRow(
                                title = "缓存过内容点赞",
                                summary = "处理本地已缓存和后续拉取到的指定好友朋友圈。",
                                checked = mode == MODE_ALL_LOADED,
                                onClick = { mode = MODE_ALL_LOADED }
                            )
                        }
                        ModeRow(
                            title = "取消点赞模式",
                            summary = "只取消指定好友朋友圈里你已点过的赞。",
                            checked = action == ACTION_UNLIKE,
                            onClick = { action = ACTION_UNLIKE }
                        )
                        if (action == ACTION_UNLIKE) {
                            ModeRow(
                                title = "刷到时取消点赞",
                                summary = "刷朋友圈时，只有刷到指定好友的内容才会取消点赞。",
                                checked = mode == MODE_WHEN_SEEN,
                                onClick = { mode = MODE_WHEN_SEEN }
                            )
                            ModeRow(
                                title = "缓存过内容取消点赞",
                                summary = "处理本地已缓存和后续拉取到的指定好友朋友圈。",
                                checked = mode == MODE_ALL_LOADED,
                                onClick = { mode = MODE_ALL_LOADED }
                            )
                        }
                        TextField(
                            value = delayInput,
                            onValueChange = { delayInput = it.filter { c -> c.isDigit() }.take(6) },
                            label = { Text("操作间隔 (ms)") },
                            supportingText = { Text("默认 0，仅在实际发送点赞/取消点赞请求之间等待") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                dismissButton = {
                    TextButton(onDismiss) { Text("取消") }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            WePrefs.putStringSet(KEY_TARGETS, selectedTargets)
                            WePrefs.putInt(KEY_MODE, mode)
                            WePrefs.putInt(KEY_ACTION, action)
                            WePrefs.putLong(
                                KEY_ACTION_DELAY_MS,
                                (delayInput.toLongOrNull() ?: 0L).coerceIn(0L, MAX_ACTION_DELAY_MS)
                            )
                            handledSnsIds.clear()
                            lastAttemptAt.clear()
                            showToast("已保存 ${selectedTargets.size} 个指定好友")
                            if (_isEnabled && mode == MODE_ALL_LOADED) {
                                scanCachedTargetMoments(selectedTargets)
                            }
                            onDismiss()
                        }
                    ) {
                        Text("保存")
                    }
                }
            )
        }
    }

    override fun onInsert(table: String, values: ContentValues) {
        processSnsInfoValues(table, values)
    }

    override fun onUpdate(table: String, values: ContentValues, whereClause: String?, whereArgs: Array<String>?, conflictAlgorithm: Int) {
        processSnsInfoValues(table, values)
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classImproveSnsInfo.find(dexKit) {
            matcher {
                usingEqStrings("ImproveInfo(name=")
            }
        }

        classImproveInteractionLayout.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.Improve.InteractionLayout")
            }
        }

        fieldInteractionSnsInfo.find(dexKit) {
            matcher {
                declaredClass(classImproveInteractionLayout.clazz)
                type(classImproveSnsInfo.clazz)
            }
        }
    }

    private fun installTimelineHooks() {
        if (timelineHooksInstalled) return
        timelineHooksInstalled = true
        listOf(
            ImproveSnsTimelineUI::class.java,
            SnsUserUI::class.java
        ).forEach { clazz ->
            clazz.reflekt()
                .firstMethod {
                    name = "onCreate"
                }
                .hookAfter {
                    if (!_isEnabled) return@hookAfter
                    scheduleAttach(thisObject as Activity)
                }
            clazz.reflekt()
                .firstMethod {
                    name = "onResume"
                }
                .hookAfter {
                    if (!_isEnabled) return@hookAfter
                    scheduleAttach(thisObject as Activity)
                }
        }
    }

    private fun scheduleAttach(activity: Activity) {
        val root = activity.rootView
        intArrayOf(0, 200, 800, 2_000).forEach { delay ->
            root.postDelayed({
                if (!_isEnabled) return@postDelayed
                runCatching { attachToTimelineList(root) }
                    .onFailure { WeLogger.w(TAG, "failed to attach Moments auto-like list observer", it) }
            }, delay.toLong())
        }
    }

    private fun attachToTimelineList(root: ViewGroup) {
        val list = root.findViewWhich<ViewGroup> { it is WxRecyclerView } ?: return
        synchronized(attachedRoots) {
            if (!attachedRoots.add(root)) return
        }
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!_isEnabled) return@addOnLayoutChangeListener
            processVisibleItems(list)
        }
        list.viewTreeObserver.addOnGlobalLayoutListener {
            if (!_isEnabled) return@addOnGlobalLayoutListener
            processVisibleItems(list)
        }
        processVisibleItems(list)
    }

    private fun processVisibleItems(list: ViewGroup) {
        if (targetWxIds().isEmpty()) return
        for (i in 0 until list.childCount) {
            runCatching {
                locateSnsInfo(list.getChildAt(i))?.let { processSnsInfoAsync(it, "visible") }
            }.onFailure {
                WeLogger.w(TAG, "failed to process visible Moments item", it)
            }
        }
    }

    private fun processSnsInfoValues(table: String, values: ContentValues) {
        if (table != "SnsInfo") return
        if (currentMode() != MODE_ALL_LOADED) return

        val owner = values.getAsString("userName")?.trim().orEmpty()
        if (!isTarget(owner)) return

        // Skip deleted/recalled moments (sourceType != 0)
        val sourceType = values.getAsInteger("sourceType") ?: 0
        if (sourceType != 0) return

        val action = currentAction()
        val likeFlag = values.getAsInteger("likeFlag") ?: 0
        if (action == ACTION_LIKE && likeFlag != 0) return
        if (action == ACTION_UNLIKE && likeFlag == 0) return

        val snsId = values.getAsLong("snsId") ?: return
        val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: return
        processSnsInfoAsync(snsInfo, "database")
    }

    private fun scanCachedTargetMoments(targets: Set<String> = targetWxIds()) {
        if (targets.isEmpty()) return
        thread(name = "MomentsAutoLikeScan") {
            WeLogger.d(TAG, "scanCachedTargetMoments: scanning ${targets.size} targets")
            val snsIds = runCatching {
                queryCachedTargetSnsIds(targets)
            }.onFailure {
                if (it !is UninitializedPropertyAccessException) {
                    WeLogger.w(TAG, "failed to query cached target Moments", it)
                }
            }.getOrDefault(emptyList())

            WeLogger.d(TAG, "scanCachedTargetMoments: found ${snsIds.size} cached moments")
            for (snsId in snsIds) {
                if (!_isEnabled) return@thread
                val snsInfo = WeMomentsApi.getSnsInfoBySnsId(snsId) ?: run {
                    WeLogger.w(TAG, "scanCachedTargetMoments: failed to get snsInfo for snsId=$snsId")
                    continue
                }
                WeLogger.d(TAG, "scanCachedTargetMoments: processing snsId=$snsId")
                processSnsInfo(snsInfo, "cached")
            }
        }
    }

    private fun queryCachedTargetSnsIds(targets: Set<String>): List<Long> {
        val placeholders = targets.joinToString(",") { "?" }
        val args = targets.map { it as Any }.toTypedArray()
        val likePredicate = if (currentAction() == ACTION_UNLIKE) {
            "IFNULL(likeFlag, 0) != 0"
        } else {
            "IFNULL(likeFlag, 0) = 0"
        }
        val sql = """
            SELECT snsId
            FROM SnsInfo
            WHERE userName IN ($placeholders)
              AND $likePredicate
              AND snsId != 0
              AND (sourceType = 0)
            ORDER BY createTime DESC
        """.trimIndent()

        WeLogger.d(TAG, "queryCachedTargetSnsIds: sql=$sql, args=${args.joinToString(",")}")

        val result = mutableListOf<Long>()
        WeDatabaseApi.rawQuery(sql, args).use { cursor ->
            WeLogger.d(TAG, "queryCachedTargetSnsIds: cursor count=${cursor.count}")
            while (cursor.moveToNext()) {
                val snsId = cursor.getLong(0)
                WeLogger.d(TAG, "queryCachedTargetSnsIds: found snsId=$snsId")
                result += snsId
            }
        }
        WeLogger.d(TAG, "queryCachedTargetSnsIds: returning ${result.size} results")
        return result
    }

    private fun processSnsInfo(snsInfo: Any, source: String) {
        val owner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
        WeLogger.d(TAG, "processSnsInfo: source=$source, owner=$owner, isTarget=${isTarget(owner)}")
        if (!isTarget(owner)) return
        if (owner == WeApi.selfWxId) return

        if (WeMomentsApi.isDeleted(snsInfo)) {
            WeLogger.d(TAG, "processSnsInfo: skipping deleted moments for owner=$owner")
            return
        }

        val snsTableId = WeMomentsApi.getSnsTableId(snsInfo) ?: run {
            WeLogger.w(TAG, "processSnsInfo: failed to get snsTableId for owner=$owner")
            return
        }

        // Check if the moments has been intercepted by AntiMomentsDelete
        if (isIntercepted(snsInfo)) {
            WeLogger.d(TAG, "processSnsInfo: skipping intercepted moments for owner=$owner")
            return
        }

        WeLogger.d(TAG, "processSnsInfo: processing snsTableId=$snsTableId, owner=$owner, source=$source")

        if (snsTableId in handledSnsIds) return
        val action = currentAction()
        val liked = WeMomentsApi.isLiked(snsInfo)
        if (action == ACTION_LIKE && liked) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (action == ACTION_UNLIKE && !liked) {
            handledSnsIds.add(snsTableId)
            return
        }
        if (!canAttempt(snsTableId)) return

        val result = sendWithDelay {
            val latestOwner = WeMomentsApi.getOwnerWxId(snsInfo)?.trim().orEmpty()
            if (!isTarget(latestOwner) || latestOwner == WeApi.selfWxId) {
                WeMomentsApi.ActionResult(true, false, "target skipped")
            } else if (WeMomentsApi.isDeleted(snsInfo)) {
                WeMomentsApi.ActionResult(true, false, "deleted/recalled")
            } else {
                val latestLiked = WeMomentsApi.isLiked(snsInfo)
                when {
                    action == ACTION_LIKE && latestLiked ->
                        WeMomentsApi.ActionResult(true, false, "already liked")

                    action == ACTION_UNLIKE && !latestLiked ->
                        WeMomentsApi.ActionResult(true, false, "already unliked")

                    action == ACTION_UNLIKE ->
                        WeMomentsApi.unlike(snsInfo)

                    else ->
                        WeMomentsApi.like(snsInfo)
                }
            }
        }
        if (result.success) {
            handledSnsIds.add(snsTableId)
            WeLogger.i(TAG, "auto-${actionLabel(action)} $source sent=${result.sent}, owner=$owner, sns=$snsTableId")
        } else {
            val message = "auto-${actionLabel(action)} $source failed, owner=$owner, sns=$snsTableId, message=${result.message}"
            result.error?.let { WeLogger.w(TAG, message, it) } ?: WeLogger.w(TAG, message)
        }
    }

    private fun canAttempt(snsTableId: String): Boolean {
        synchronized(lastAttemptAt) {
            val now = System.currentTimeMillis()
            val last = lastAttemptAt[snsTableId] ?: 0L
            if (now - last < RETRY_INTERVAL_MS) return false
            lastAttemptAt[snsTableId] = now
            return true
        }
    }

    private fun isIntercepted(snsInfo: Any): Boolean {
        // Check if the moments content contains the interception marker
        val content = WeMomentsApi.getContent(snsInfo) ?: return false
        return content.contains("[已拦截]")
    }

    private fun processSnsInfoAsync(snsInfo: Any, source: String) {
        thread(name = "MomentsAutoLikeAction") {
            processSnsInfo(snsInfo, source)
        }
    }

    private fun sendWithDelay(block: () -> WeMomentsApi.ActionResult): WeMomentsApi.ActionResult =
        synchronized(actionLock) {
            val delay = actionDelayMs()
            if (delay > 0) {
                val wait = delay - (System.currentTimeMillis() - lastActionSentAt)
                if (wait > 0) Thread.sleep(wait)
            }

            val result = block()
            if (result.sent) {
                lastActionSentAt = System.currentTimeMillis()
            }
            result
        }

    private fun locateSnsInfo(itemView: View): Any? {
        extractImproveSnsInfo(itemView)?.let { return it }

        val interactionView = itemView.findViewWhich<View> {
            classImproveInteractionLayout.clazz.isInstance(it)
        } ?: return null

        return extractImproveSnsInfo(interactionView)
            ?: fieldInteractionSnsInfo.field.get(interactionView)
    }

    private fun extractImproveSnsInfo(receiver: Any): Any? {
        if (classImproveSnsInfo.clazz.isInstance(receiver)) return receiver

        callNoArgReturning(receiver, classImproveSnsInfo.clazz)?.let { return it }

        callNoArg(receiver, "getImproveListItem")?.let { listItem ->
            callNoArgReturning(listItem, classImproveSnsInfo.clazz)?.let { return it }
            readFieldAssignable(listItem, classImproveSnsInfo.clazz)?.let { return it }
        }

        return readFieldAssignable(receiver, classImproveSnsInfo.clazz)
    }

    private fun callNoArg(receiver: Any, name: String): Any? {
        var clazz: Class<*>? = receiver.javaClass
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    method.invoke(receiver)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun callNoArgReturning(receiver: Any, returnType: Class<*>): Any? {
        var clazz: Class<*>? = receiver.javaClass
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull {
                it.parameterCount == 0 && returnType.isAssignableFrom(it.returnType)
            }?.let { method ->
                return runCatching {
                    method.isAccessible = true
                    method.invoke(receiver)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun readFieldAssignable(receiver: Any, fieldType: Class<*>): Any? {
        var clazz: Class<*>? = receiver.javaClass
        while (clazz != null) {
            clazz.declaredFields.firstOrNull {
                fieldType.isAssignableFrom(it.type)
            }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(receiver)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun currentMode(): Int =
        WePrefs.getIntOrDef(KEY_MODE, MODE_WHEN_SEEN)

    private fun currentAction(): Int =
        WePrefs.getIntOrDef(KEY_ACTION, ACTION_LIKE)

    private fun actionDelayMs(): Long =
        WePrefs.getLongOrDef(KEY_ACTION_DELAY_MS, 0L).coerceIn(0L, MAX_ACTION_DELAY_MS)

    private fun actionLabel(action: Int): String =
        if (action == ACTION_UNLIKE) "unlike" else "like"

    private fun isTarget(wxId: String): Boolean =
        wxId.isNotBlank() && wxId in targetWxIds()

    private fun targetWxIds(): Set<String> =
        normalizeWxIds(WePrefs.getStringSetOrDef(KEY_TARGETS, emptySet()))

    private fun normalizeWxIds(wxIds: Set<String>): Set<String> =
        wxIds.mapNotNullTo(mutableSetOf()) { it.trim().takeIf { wxId -> wxId.isNotBlank() } }

    private val classImproveSnsInfo by dexClass()
    private val classImproveInteractionLayout by dexClass()
    private val fieldInteractionSnsInfo by dexField()
}

@Composable
private fun ModeRow(
    title: String,
    summary: String,
    checked: Boolean,
    onClick: () -> Unit
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = {
            RadioButton(
                selected = checked,
                onClick = onClick
            )
        },
        modifier = Modifier.clickable(onClick = onClick)
    )
}
