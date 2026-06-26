package dev.ujhhgtg.wekit.hooks.api.core

import android.content.ContentValues
import dev.ujhhgtg.reflekt.utils.createInstance
import dev.ujhhgtg.comptime.nameOf
import dev.ujhhgtg.wekit.dexkit.abc.IResolveDex
import dev.ujhhgtg.wekit.dexkit.dsl.dexClass
import dev.ujhhgtg.wekit.dexkit.dsl.dexMethod
import dev.ujhhgtg.wekit.hooks.core.ApiHookItem
import dev.ujhhgtg.wekit.hooks.core.HookItem
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.reflekt.reflekt
import org.luckypray.dexkit.DexKitBridge
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.LinkedHashSet

@HookItem(name = "对话服务", categories = ["API"], description = "提供对话管理能力")
object WeConversationApi : ApiHookItem(), IResolveDex {

    private val TAG = nameOf(WeConversationApi)
    // Clears 4096, 1048576, 16777216 and 33554432, which drive 8071/8072 red prefixes.
    private const val ATTR_FLAG_COMMON_RED_BITS = 51384320
    private const val ATTR_FLAG_8071_8072_RED_PACKET_BITS = 33280
    private const val TABLE_RCONVERSATION = "rconversation"
    private const val TABLE_ECS_CONVERSATION_RECORD = "EcsConversationRecord"
    private val classConversationStorage by dexClass()
    private val methodUpdateUnreadByTalker by dexMethod()
    private val methodClearConvRedHintsOnMarkRead by dexMethod()
    private val methodClearEcsGiftRedLabel by dexMethod()
    private val methodHiddenConvParent by dexMethod()
    private val methodGetConvByName by dexMethod()
    private val methodUpdateConversationByObject by dexMethod()
    private val methodChatroomStorageGetMemberCount by dexMethod()
    private val classChatroomMember by dexClass()
    private val methodSetDnd by dexMethod()
    private val methodSetNoDnd by dexMethod()
    private val methodNotifyConversationChanged by dexMethod()
    private var ecsGiftMsgService: Any? = null

    val conversationStorage by lazy {
        WeServiceApi.storageFeatureService.reflekt()
            .firstMethod {
                returnType = classConversationStorage.clazz
            }
            .invoke()!!
    }

    val chatroomStorage by lazy {
        WeServiceApi.chatroomService.reflekt()
            .firstMethod {
                returnType = methodChatroomStorageGetMemberCount.method.declaringClass
            }
            .invoke()!!
    }

    // this is NOT group 'member'
    // this is the group itself
    fun getGroup(groupId: String): Any {
        return chatroomStorage.reflekt()
            .firstMethod {
                parameters(String::class)
                returnType = classChatroomMember.clazz
            }
            .invoke(groupId)!!
    }

    fun markAllAsRead() {
        val talkers = LinkedHashSet<String>()
        val cursor = WeDatabaseApi.rawQuery(
            """
                SELECT username, parentRef FROM rconversation
            """.trimIndent()
        )
        try {
            while (cursor.moveToNext()) {
                val talker = cursor.getString(0)
                if (!talker.isNullOrEmpty()) {
                    talkers += talker
                }
                val parentRef = cursor.getString(1)
                if (!parentRef.isNullOrEmpty()) {
                    talkers += parentRef
                }
            }
        } finally {
            cursor.close()
        }

        for (talker in talkers) {
            try {
                clearConversationUnreadState(talker)
            } catch (ex: Exception) {
                WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
            }
        }
        clearAllConversationRedPacketMarkFields()
        notifyConversationListReload()
    }

    fun markAsRead(talker: String) {
        try {
            clearConversationUnreadState(talker)
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while updating unread count for $talker", ex)
        }
    }

    private fun clearConversationUnreadState(talker: String) {
        try {
            methodUpdateUnreadByTalker.method.invoke(conversationStorage, talker)
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while invoking native mark read for $talker", ex)
        }
        clearEcsGiftRedLabelViaOfficialService(talker)
        clearExternalConversationRedHints(talker)
        notifyConversationChanged(talker)
        WeLogger.d(TAG, "marked $talker as read")
    }

    private fun clearEcsGiftRedLabelViaOfficialService(talker: String): Boolean {
        return try {
            val method = resolvedClearEcsGiftRedLabelMethod() ?: return false
            val service = getEcsGiftMsgService(method) ?: return false
            method.invoke(service, talker)
            true
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while invoking official ecs gift red-label clear for $talker", ex)
            false
        }
    }

    private fun clearExternalConversationRedHints(talker: String) {
        try {
            WeDatabaseApi.execStatement(
                "UPDATE $TABLE_ECS_CONVERSATION_RECORD SET ecsUnhandledGiftCount=0, ecsGiftRedLabelType=0 WHERE talker=?",
                arrayOf<Any>(talker)
            )
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while clearing external red hints for $talker", ex)
        }
    }

    private fun clearAllConversationRedPacketMarkFields() {
        try {
            WeDatabaseApi.execStatement(
                "UPDATE $TABLE_RCONVERSATION SET hbMarkRed=0, remitMarkRed=0, attrflag=(attrflag & ${unreadClearAttrFlagMask()}) WHERE hbMarkRed<>0 OR remitMarkRed<>0"
            )
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while clearing all red-packet mark fields", ex)
        }
    }

    private fun unreadClearAttrFlagMask(): Int {
        return (ATTR_FLAG_COMMON_RED_BITS or ATTR_FLAG_8071_8072_RED_PACKET_BITS).inv()
    }

    private fun resolvedClearEcsGiftRedLabelMethod(): Method? {
        if (methodClearEcsGiftRedLabel.isPlaceholder) return null
        val method = methodClearEcsGiftRedLabel.method
        if (method.isStringVoidMethod()) return method
        WeLogger.w(
            TAG,
            "ignore invalid official ecs gift red-label clear method: ${method.declaringClass.name}.${method.name}, params=${method.parameterCount}"
        )
        return null
    }

    private fun Method.isStringVoidMethod(): Boolean {
        return returnType == Void.TYPE &&
            parameterCount == 1 &&
            parameterTypes[0] == String::class.java
    }

    private fun getEcsGiftMsgService(method: Method): Any? {
        ecsGiftMsgService?.let { return it }
        val concreteClass = method.declaringClass
        for (serviceInterface in concreteClass.interfaces) {
            val service = runCatching {
                WeServiceApi.getServiceImplByClass(serviceInterface)
            }.getOrNull()
            if (service != null && concreteClass.isInstance(service)) {
                return service.also { ecsGiftMsgService = it }
            }
        }
        return null
    }

    private fun notifyConversationListReload() {
        try {
            notifyConversationChanged("", 5)
        } catch (ex: Exception) {
            WeLogger.w(TAG, "exception while notifying conversation list reload", ex)
        }
    }

    fun reloadConversationList() {
        notifyConversationListReload()
    }

    private fun notifyConversationChanged(talker: String, eventType: Int = 3) {
        if (methodNotifyConversationChanged.isPlaceholder) return
        methodNotifyConversationChanged.method.invoke(conversationStorage, eventType, conversationStorage, talker)
    }

    fun setConversationsVisibility(visible: Boolean, talkers: Array<String>) {
        val operation = if (visible) "" else "hidden_conv_parent"
        if (methodHiddenConvParent.method.parameterCount == 4) {
            methodHiddenConvParent.method.invoke(
                conversationStorage,
                talkers,
                operation,
                true,
                false
            )
        } else {
            methodHiddenConvParent.method.invoke(
                conversationStorage,
                talkers,
                operation
            )
        }
    }

    fun setAllConversationVisibility(visible: Boolean) {
        val cursor = WeDatabaseApi.rawQuery("SELECT username FROM rconversation")
        val talkers = mutableListOf<String>()
        while (cursor.moveToNext()) {
            talkers += cursor.getString(0)
        }
        cursor.close()
        setConversationsVisibility(visible, talkers.toTypedArray())
    }

    fun onlyShowFilteredConversations(queryFilter: String, selectedColumns: String = "username") {
        setAllConversationVisibility(false)
        setFilteredConversationsVisibility(true, queryFilter, selectedColumns)
    }

    fun setFilteredConversationsVisibility(visible: Boolean, queryFilter: String, selectedColumns: String = "username") {
        val cursor = WeDatabaseApi.rawQuery("SELECT $selectedColumns FROM rconversation $queryFilter")
        val talkers = mutableListOf<String>()
        while (cursor.moveToNext()) {
            talkers += cursor.getString(0)
        }
        cursor.close()
        setConversationsVisibility(visible, talkers.toTypedArray())
    }

    private lateinit var contactType: Class<*>

    // TODO: this only updates local DB without syncing to server
    fun setIfNotifyNewMessages(convId: String, shouldNotify: Boolean) {
        if (!::contactType.isInitialized) {
            contactType = methodSetDnd.method.parameterTypes[0]
        }

        val contact = contactType.createInstance(convId)
        if (!shouldNotify) {
            methodSetDnd.method.invoke(null, contact, true)
        } else {
            methodSetNoDnd.method.invoke(null, contact, true)
        }
    }

    override fun resolveDex(dexKit: DexKitBridge) {
        classConversationStorage.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("rconversation", "PRAGMA table_info( rconversation)")
            }
        }

        methodUpdateUnreadByTalker.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                usingEqStrings("MicroMsg.ConversationStorage", "updateUnreadByTalker %s")
            }
        }

        methodClearConvRedHintsOnMarkRead.find(dexKit, allowFailure = true) {
            matcher {
                modifiers = Modifier.PUBLIC or Modifier.STATIC or Modifier.FINAL
                returnType(Void.TYPE)
                paramCount = 1
                paramTypes(String::class.java)
                usingStrings(
                    "MicroMsg.ConvRedHintStorage",
                    "markReadRemoveRedHint remove red hints"
                )
            }
        }

        methodClearEcsGiftRedLabel.find(dexKit, allowFailure = true) {
            matcher {
                returnType(Void.TYPE)
                paramCount = 1
                paramTypes(String::class.java)
                usingEqStrings(
                    "MicroMsg.EcsGiftMsgService",
                    "clearEcsGiftRedLabel, talker is empty",
                    "clearEcsGiftRedLabel error"
                )
            }
        }

        methodHiddenConvParent.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                usingEqStrings("Update rconversation set parentRef = '", "' where 1 != 1 ")
            }
        }

        methodGetConvByName.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                usingEqStrings("MicroMsg.ConversationStorage", "get null with username:")
            }
        }

        methodUpdateConversationByObject.find(dexKit) {
            matcher {
                declaredClass(classConversationStorage.clazz)
                returnType(Int::class.java)
                paramTypes(methodGetConvByName.method.returnType, String::class.java)
            }
        }

        methodNotifyConversationChanged.find(dexKit, allowFailure = true) {
            matcher {
                declaredClass(classConversationStorage.clazz.superclass)
                paramCount = 3
                paramTypes("int", classConversationStorage.clazz.superclass.name, "java.lang.Object")
                returnType(Void.TYPE)
            }
        }

        methodChatroomStorageGetMemberCount.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.ChatroomStorage", "[getMemberCount] cost:%sms")
            }
        }

        classChatroomMember.find(dexKit) {
            searchPackages("com.tencent.mm.storage")
            matcher {
                usingEqStrings("MicroMsg.ChatRoomMember", "service is null")
            }
        }

        methodSetDnd.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.OpenImOpLogLogic", "OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch add")
            }
        }

        methodSetNoDnd.find(dexKit) {
            matcher {
                usingEqStrings("MicroMsg.OpenImOpLogLogic", "OpenImOpLogLogic OpenIMModContactMuteOplog username:%s switch cancel")
            }
        }
    }
}
