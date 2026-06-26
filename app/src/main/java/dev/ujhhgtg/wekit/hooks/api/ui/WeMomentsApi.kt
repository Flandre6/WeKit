package dev.ujhhgtg.wekit.hooks.api.ui

import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.reflection.ClassLoaders
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier

@HookItem(
    name = "Moments API",
    categories = ["API"],
    description = "Provide real Moments server-side actions"
)
object WeMomentsApi : ApiHookItem(), IResolveDex {

    private val TAG = "WeMomentsApi"

    data class ActionResult(
        val success: Boolean,
        val sent: Boolean,
        val message: String,
        val error: Throwable? = null
    )

    private const val SNS_INFO_CLASS = "com.tencent.mm.plugin.sns.storage.SnsInfo"
    private const val LIKE_COMMENT_TYPE = 1

    private val classSnsService by dexClass()
    private val methodSendLike by dexMethod()
    private val methodCancelLike by dexMethod()
    private val methodGetSnsInfoByLocalId by dexMethod()
    private val methodGetSnsInfoStorage by dexMethod()
    private val methodGetSnsInfoBySnsId by dexMethod()

    private val snsInfoClass: Class<*> by lazy {
        ClassLoaders.HOST.loadClass(SNS_INFO_CLASS)
    }

    private val reflectiveSendLikeMethod: Method by lazy {
        classSnsService.clazz.declaredMethods.firstOrNull { method ->
            Modifier.isStatic(method.modifiers) &&
                method.parameterCount == 4 &&
                method.parameterTypes[0] == snsInfoClass &&
                method.parameterTypes[1] == Integer.TYPE &&
                method.parameterTypes[3] == Integer.TYPE &&
                method.returnType != Void.TYPE
        }?.apply { isAccessible = true }
            ?: error("Moments send-like method not found")
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classSnsService.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.sns.model")
            matcher {
                usingEqStrings(
                    "MicroMsg.SnsService",
                    "can not add Comment"
                )
            }
        }

        methodSendLike.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classSnsService.clazz)
                modifiers = Modifier.STATIC
                paramTypes(SNS_INFO_CLASS, "int", null, "int")
            }
        }

        methodCancelLike.find(dexKit) {
            matcher {
                declaredClass(classSnsService.clazz)
                modifiers = Modifier.STATIC
                paramTypes(String::class.java)
                returnType(Void.TYPE)
            }
        }

        methodGetSnsInfoByLocalId.find(dexKit) {
            matcher {
                paramTypes("int")
                returnType(SNS_INFO_CLASS)
                usingStrings(
                    "getByLocalId",
                    "select *,rowid from SnsInfo  where SnsInfo.rowid="
                )
            }
        }

        methodGetSnsInfoStorage.find(dexKit) {
            searchPackages("com.tencent.mm.plugin.sns.model")
            matcher {
                modifiers = Modifier.STATIC
                paramCount(0)
                returnType(methodGetSnsInfoByLocalId.method.declaringClass)
                usingStrings(
                    "com.tencent.mm.plugin.sns.model.SnsCore",
                    "getSnsInfoStorage"
                )
            }
        }

        methodGetSnsInfoBySnsId.find(dexKit) {
            matcher {
                declaredClass(methodGetSnsInfoByLocalId.method.declaringClass)
                paramTypes("long")
                returnType(SNS_INFO_CLASS)
                usingStrings("select *,rowid from SnsInfo  where SnsInfo.snsId=")
            }
        }
    }

    fun like(snsInfo: Any?, sourceScene: Int = 0): ActionResult =
        sendLike(snsInfo, sourceScene, skipIfAlreadyLiked = true)

    fun like(context: WeMomentsContextMenuApi.MomentsContext, sourceScene: Int = 0): ActionResult =
        like(context.snsInfo, sourceScene)

    fun forceLike(snsInfo: Any?, sourceScene: Int = 0): ActionResult =
        sendLike(snsInfo, sourceScene, skipIfAlreadyLiked = false)

    fun forceLike(context: WeMomentsContextMenuApi.MomentsContext, sourceScene: Int = 0): ActionResult =
        forceLike(context.snsInfo, sourceScene)

    fun unlike(snsInfo: Any?): ActionResult {
        val normalized = normalizeSnsInfo(snsInfo)
            ?: return ActionResult(false, false, "snsInfo is null or unsupported")

        val snsTableId = getSnsTableId(normalized)
            ?: return ActionResult(false, false, "sns table id is unavailable")

        return runCatching {
            methodCancelLike.method.invoke(null, snsTableId)
            ActionResult(true, true, "cancel like request sent")
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to send Moments unlike request", error)
            ActionResult(false, false, error.message ?: "failed to send cancel like request", error)
        }
    }

    fun unlike(context: WeMomentsContextMenuApi.MomentsContext): ActionResult =
        unlike(context.snsInfo)

    fun isLiked(snsInfo: Any?): Boolean {
        val normalized = normalizeSnsInfo(snsInfo) ?: return false
        return readLikeFlag(normalized) != 0
    }

    fun isDeleted(snsInfo: Any?): Boolean {
        val normalized = normalizeSnsInfo(snsInfo) ?: return false
        return (callNoArg(normalized, "isDeadSource") as? Boolean) == true
    }

    fun getContent(snsInfo: Any?): String? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return callNoArg(normalized, "getContent") as? String
            ?: readField(normalized, "field_content") as? String
    }

    fun isLiked(context: WeMomentsContextMenuApi.MomentsContext): Boolean =
        isLiked(context.snsInfo)

    fun getSnsTableId(snsInfo: Any?): String? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return callNoArg(normalized, "getSnsId") as? String
            ?: buildSnsTableId(normalized)
    }

    fun getSnsTableId(context: WeMomentsContextMenuApi.MomentsContext): String? =
        getSnsTableId(context.snsInfo)

    fun getOwnerWxId(snsInfo: Any?): String? {
        val normalized = normalizeSnsInfo(snsInfo) ?: return null
        return callNoArg(normalized, "getUserName") as? String
            ?: readField(normalized, "field_userName") as? String
    }

    fun getOwnerWxId(context: WeMomentsContextMenuApi.MomentsContext): String? =
        getOwnerWxId(context.snsInfo)

    fun getSnsInfoBySnsId(snsId: Long): Any? {
        if (snsId == 0L) return null
        return runCatching {
            val storage = methodGetSnsInfoStorage.method.invoke(null)
            methodGetSnsInfoBySnsId.method.invoke(storage, snsId)
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to get Moments snsInfo by snsId=$snsId", error)
            null
        }
    }

    private fun sendLike(
        snsInfo: Any?,
        sourceScene: Int,
        skipIfAlreadyLiked: Boolean
    ): ActionResult {
        val normalized = normalizeSnsInfo(snsInfo)
            ?: return ActionResult(false, false, "snsInfo is null or unsupported")

        if (!isValidSnsInfo(normalized)) {
            return ActionResult(false, false, "snsInfo is invalid")
        }
        if (skipIfAlreadyLiked && readLikeFlag(normalized) != 0) {
            return ActionResult(true, false, "already liked")
        }

        return runCatching {
            sendLikeMethod().invoke(null, normalized, LIKE_COMMENT_TYPE, null, sourceScene)
            ActionResult(true, true, "like request sent")
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to send Moments like request", error)
            ActionResult(false, false, error.message ?: "failed to send like request", error)
        }
    }

    private fun sendLikeMethod(): Method =
        runCatching { methodSendLike.method }.getOrElse { reflectiveSendLikeMethod }

    private fun normalizeSnsInfo(snsInfo: Any?): Any? {
        if (snsInfo == null) return null
        return runCatching {
            if (snsInfoClass.isInstance(snsInfo)) return snsInfo

            snsInfo.javaClass.methods
                .firstOrNull { method ->
                    method.parameterCount == 0 && snsInfoClass.isAssignableFrom(method.returnType)
                }
                ?.runCatchingInvoke(snsInfo)
        }.getOrElse { error ->
            WeLogger.e(TAG, "failed to normalize Moments snsInfo", error)
            null
        }
    }

    private fun isValidSnsInfo(snsInfo: Any): Boolean {
        (callNoArg(snsInfo, "isValid") as? Boolean)?.let { return it }
        (readLongField(snsInfo, "field_snsId"))?.let { return it != 0L }
        return true
    }

    private fun readLikeFlag(snsInfo: Any): Int {
        return (callNoArg(snsInfo, "getLikeFlag") as? Number)?.toInt()
            ?: readIntField(snsInfo, "field_likeFlag")
            ?: 0
    }

    private fun buildSnsTableId(snsInfo: Any): String? {
        val snsId = readLongField(snsInfo, "field_snsId") ?: return null
        if (snsId == 0L) return null

        val isAd = (callNoArg(snsInfo, "isAd") as? Boolean) == true
        findStaticSnsIdMethod()?.let { method ->
            return runCatching { method.invoke(null, isAd, snsId) as? String }.getOrNull()
        }
        return if (isAd) "ad_table_$snsId" else "sns_table_$snsId"
    }

    private fun findStaticSnsIdMethod(): Method? =
        runCatching {
            snsInfoClass.getDeclaredMethod(
                "getSnsId",
                java.lang.Boolean.TYPE,
                java.lang.Long.TYPE
            ).apply { isAccessible = true }
        }.getOrNull()

    private fun callNoArg(receiver: Any, name: String): Any? {
        var clazz: Class<*>? = receiver.javaClass
        while (clazz != null) {
            clazz.declaredMethods.firstOrNull {
                it.name == name && it.parameterCount == 0
            }?.let { return it.runCatchingInvoke(receiver) }
            clazz = clazz.superclass
        }
        return null
    }

    private fun readIntField(receiver: Any, name: String): Int? =
        readField(receiver, name)?.let { it as? Number }?.toInt()

    private fun readLongField(receiver: Any, name: String): Long? =
        readField(receiver, name)?.let { it as? Number }?.toLong()

    private fun readField(receiver: Any, name: String): Any? {
        var clazz: Class<*>? = receiver.javaClass
        while (clazz != null) {
            clazz.declaredFields.firstOrNull { it.name == name }?.let { field ->
                return runCatching {
                    field.isAccessible = true
                    field.get(receiver)
                }.getOrNull()
            }
            clazz = clazz.superclass
        }
        return null
    }

    private fun Method.runCatchingInvoke(receiver: Any): Any? =
        runCatching {
            isAccessible = true
            invoke(receiver)
        }.getOrNull()
}
