package app.fuckpo0jixian.desktop

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer

/**
 * The Dock icon on macOS, as NSApplication's activation policy: regular (in the Dock) or accessory (menu bar only).
 * At launch the choice is applied through apple.awt.UIElement, before AppKit starts, so a hidden icon never flashes
 * in the Dock; while running it is switched here. AppKit belongs to the main thread, so calls are queued there.
 */
internal object MacDock {
    private interface ObjC : Library {
        fun objc_getClass(name: String): Pointer?
        fun sel_registerName(name: String): Pointer?
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?): Pointer?
    }
    /** The same function with one integer argument. A fixed-arity declaration, as arm64 requires for objc_msgSend. */
    private interface ObjCWithArgument : Library {
        fun objc_msgSend(receiver: Pointer?, selector: Pointer?, argument: Long): Long
    }
    private interface Dispatch : Library { fun dispatch_async_f(queue: Pointer, context: Pointer?, work: Work) }
    fun interface Work : Callback { fun callback(context: Pointer?) }

    private val objc by lazy { Native.load("objc", ObjC::class.java) }
    private val objcWithArgument by lazy { Native.load("objc", ObjCWithArgument::class.java) }
    private val dispatch by lazy { Native.load("System", Dispatch::class.java) }
    private val mainQueue by lazy { NativeLibrary.getInstance("System").getGlobalVariableAddress("_dispatch_main_q") }
    /** Queued callbacks, kept reachable until AppKit has run them. */
    private val queued = java.util.Collections.synchronizedSet(mutableSetOf<Work>())

    private const val REGULAR = 0L
    private const val ACCESSORY = 1L

    /** Call before any window or tray icon exists. */
    fun applyAtLaunch(hidden: Boolean) { if (hidden) System.setProperty("apple.awt.UIElement", "true") }

    fun show(visible: Boolean) = onMain {
        send("setActivationPolicy:", if (visible) REGULAR else ACCESSORY)
        // Switching policy can drop the app to the background; keep its window in front.
        send("activateIgnoringOtherApps:", 1)
    }

    /** Brings the app forward, which an app without a Dock icon is not when its window opens. */
    fun activate() = onMain { send("activateIgnoringOtherApps:", 1) }

    private fun send(selector: String, argument: Long) {
        val app = objc.objc_msgSend(objc.objc_getClass("NSApplication"), objc.sel_registerName("sharedApplication"))
        objcWithArgument.objc_msgSend(app, objc.sel_registerName(selector), argument)
    }

    private fun onMain(block: () -> Unit) {
        if (os != Os.MAC) return
        runCatching {
            lateinit var work: Work
            work = Work { runCatching(block); queued.remove(work) }
            queued.add(work)
            dispatch.dispatch_async_f(mainQueue, null, work)
        }
    }
}
