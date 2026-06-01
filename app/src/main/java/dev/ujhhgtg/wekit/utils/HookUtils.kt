package dev.ujhhgtg.wekit.utils

import com.highcapable.kavaref.resolver.MethodResolver
import dev.ujhhgtg.wekit.loader.abc.IHookBridge
import dev.ujhhgtg.wekit.loader.startup.StartupInfo
import java.lang.reflect.Constructor
import java.lang.reflect.Executable
import java.lang.reflect.Method

typealias HookAction = IHookBridge.IMemberHookParam.() -> Unit

// most extension methods are inside BaseHookItem for enabled state checking

inline fun MethodResolver<*>.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
) = this.self.hookBeforeDirectly(priority, action)

inline fun Executable.hookBeforeDirectly(
    priority: Int = 50,
    crossinline action: HookAction
) = StartupInfo.hookBridge!!.hookMethod(
    this, object : IHookBridge.IMemberHookCallback {
        override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {
            action(param)
        }

        override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {}
    }, priority
)

inline fun MethodResolver<*>.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
) = this.self.hookAfterDirectly(priority, action)

inline fun Executable.hookAfterDirectly(
    priority: Int = 50,
    crossinline action: HookAction
) = StartupInfo.hookBridge!!.hookMethod(
    this, object : IHookBridge.IMemberHookCallback {
        override fun beforeHookedMember(param: IHookBridge.IMemberHookParam) {}

        override fun afterHookedMember(param: IHookBridge.IMemberHookParam) {
            action(param)
        }
    }, priority
)

@Suppress("NOTHING_TO_INLINE", "ARGUMENT_TYPE_MISMATCH") // type is erased at compile-time anyways
fun IHookBridge.IMemberHookParam.invokeOriginal(thisObject: Any? = null, args: Array<Any?>? = null): Any? = when (member) {
    is Method -> StartupInfo.hookBridge!!.invokeOriginalMethod(member as Method, thisObject ?: this.thisObject, args ?: this.args)
    is Constructor<*> -> StartupInfo.hookBridge!!.invokeOriginalConstructor(member as Constructor<*>, thisObject ?: this.thisObject, args ?: this.args)
    else -> unreachable()
}
