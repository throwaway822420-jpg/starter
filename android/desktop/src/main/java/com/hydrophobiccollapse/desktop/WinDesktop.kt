package com.hydrophobiccollapse.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import java.awt.Component
import java.awt.Rectangle

/**
 * The Windows-only parts: running behind the desktop icons as a live wallpaper, 1 ms timers for even frame
 * pacing, noticing when the desktop is hidden (so the wallpaper can pause), and starting with Windows.
 * Everything fails soft: off Windows, or if a call is refused, the app carries on without it.
 */
object WinDesktop {
    val isWindows = System.getProperty("os.name", "").startsWith("Windows")

    @Suppress("FunctionName")
    private interface Winmm : Library { fun timeBeginPeriod(period: Int): Int; fun timeEndPeriod(period: Int): Int }
    @Suppress("FunctionName")
    private interface User32Extra : Library { fun SystemParametersInfoW(action: Int, param: Int, buffer: CharArray?, winIni: Int): Boolean }
    private val winmm by lazy { if (isWindows) runCatching { Native.load("winmm", Winmm::class.java) }.getOrNull() else null }
    private val user32x by lazy { if (isWindows) runCatching { Native.load("user32", User32Extra::class.java) }.getOrNull() else null }
    private val u: User32? by lazy { if (isWindows) runCatching { User32.INSTANCE }.getOrNull() else null }

    /** Windows wakes sleeping threads every 15.6 ms by default, which makes frames uneven; ask for 1 ms. */
    fun fineTimers(on: Boolean) {
        runCatching { if (on) winmm?.timeBeginPeriod(1) else winmm?.timeEndPeriod(1) }
    }

    fun hwnd(c: Component): HWND? = if (!isWindows) null else runCatching { HWND(Native.getComponentPointer(c)) }.getOrNull()

    /** The primary monitor in physical pixels, virtual-screen coordinates. */
    fun primaryMonitor(): Rectangle? {
        val u = u ?: return null
        return runCatching {
            val mon = u.MonitorFromPoint(WinDef.POINT.ByValue(0, 0), WinUser.MONITOR_DEFAULTTOPRIMARY)
            val mi = WinUser.MONITORINFO(); u.GetMonitorInfo(mon, mi)
            val r = mi.rcMonitor
            Rectangle(r.left, r.top, r.right - r.left, r.bottom - r.top)
        }.getOrNull()
    }

    private fun className(h: HWND): String {
        val buf = CharArray(128); u?.GetClassName(h, buf, buf.size)
        return Native.toString(buf)
    }

    /**
     * Moves [window] behind the desktop icons, covering the primary monitor.
     *
     * Explorer draws the wallpaper in a "WorkerW" window, created on request by message 0x052C to Progman.
     * Up to Windows 11 23H2 it is a top-level window just after the one holding the icons (SHELLDLL_DefView);
     * from 24H2 the icons and the WorkerW are both children of Progman, and the wallpaper window goes
     * between them.
     */
    fun attachToDesktop(window: Component): Boolean {
        val u = u ?: return false
        val me = hwnd(window) ?: return false
        return runCatching {
            val progman = u.FindWindow("Progman", null) ?: return false
            val result = WinDef.DWORDByReference()
            u.SendMessageTimeout(progman, 0x052C, WinDef.WPARAM(0xD), WinDef.LPARAM(0x1), WinUser.SMTO_NORMAL, 1000, result)
            u.SendMessageTimeout(progman, 0x052C, WinDef.WPARAM(0), WinDef.LPARAM(0), WinUser.SMTO_NORMAL, 1000, result)
            val mon = primaryMonitor() ?: return false
            val iconsInProgman = u.FindWindowEx(progman, null, "SHELLDLL_DefView", null)
            if (iconsInProgman != null) {
                // Windows 11 24H2 and later
                u.SetParent(me, progman)
                val origin = WinDef.RECT(); u.GetWindowRect(progman, origin)
                u.SetWindowPos(me, iconsInProgman, mon.x - origin.left, mon.y - origin.top, mon.width, mon.height, SWP_NOACTIVATE or SWP_SHOWWINDOW)
            } else {
                var worker: HWND? = null
                u.EnumWindows({ top, _ ->
                    if (u.FindWindowEx(top, null, "SHELLDLL_DefView", null) != null) worker = u.FindWindowEx(null, top, "WorkerW", null)
                    true
                }, null)
                val w = worker ?: u.FindWindowEx(progman, null, "WorkerW", null) ?: return false
                u.SetParent(me, w)
                val origin = WinDef.RECT(); u.GetWindowRect(w, origin)
                u.SetWindowPos(me, null, mon.x - origin.left, mon.y - origin.top, mon.width, mon.height, SWP_NOZORDER or SWP_NOACTIVATE or SWP_SHOWWINDOW)
            }
            true
        }.getOrDefault(false)
    }

    /** After the wallpaper window goes, have Explorer redraw the picture that was there before. */
    fun restoreWallpaper() {
        val x = user32x ?: return
        runCatching {
            val buf = CharArray(1024)
            if (x.SystemParametersInfoW(SPI_GETDESKWALLPAPER, buf.size, buf, 0)) {
                val path = Native.toString(buf)
                x.SystemParametersInfoW(SPI_SETDESKWALLPAPER, 0, (path + '\u0000').toCharArray(), 0)
            }
        }
    }

    /**
     * True when the window in front fills the primary monitor or is maximized there, so nobody can see the
     * wallpaper and it can stop drawing. Our own windows never count.
     */
    fun desktopHidden(own: Collection<HWND>): Boolean {
        val u = u ?: return false
        return runCatching {
            val fg = u.GetForegroundWindow() ?: return false
            if (own.any { it.pointer == fg.pointer } || !u.IsWindowVisible(fg)) return false
            if (className(fg) in DESKTOP_CLASSES) return false
            val primary = u.MonitorFromPoint(WinDef.POINT.ByValue(0, 0), WinUser.MONITOR_DEFAULTTOPRIMARY)
            if (u.MonitorFromWindow(fg, WinUser.MONITOR_DEFAULTTONEAREST).pointer != primary.pointer) return false
            val wp = WinUser.WINDOWPLACEMENT(); u.GetWindowPlacement(fg, wp)
            if (wp.showCmd == WinUser.SW_SHOWMAXIMIZED) return true
            val r = WinDef.RECT(); u.GetWindowRect(fg, r)
            val m = primaryMonitor() ?: return false
            r.left <= m.x && r.top <= m.y && r.right >= m.x + m.width && r.bottom >= m.y + m.height
        }.getOrDefault(false)
    }

    // ---------- Start with Windows ----------
    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val RUN_NAME = "HydrophobicCollapse"
    /** The installed .exe (the jpackage launcher says where it is); null when run from a development build. */
    val appPath: String? get() = System.getProperty("jpackage.app-path")
        ?: ProcessHandle.current().info().command().orElse(null)?.takeIf { it.endsWith("HydrophobicCollapse.exe", ignoreCase = true) }
    val canStartWithWindows get() = isWindows && appPath != null
    fun startsWithWindows(): Boolean = isWindows && runCatching {
        Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME)
    }.getOrDefault(false)
    fun setStartWithWindows(on: Boolean) {
        val exe = appPath ?: return
        runCatching {
            if (on) Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME, "\"$exe\" --wallpaper")
            else if (startsWithWindows()) Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, RUN_NAME)
        }
    }

    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_SHOWWINDOW = 0x0040
    private const val SPI_SETDESKWALLPAPER = 0x0014
    private const val SPI_GETDESKWALLPAPER = 0x0073
    private val DESKTOP_CLASSES = setOf("WorkerW", "Progman", "Shell_TrayWnd", "Shell_SecondaryTrayWnd")
}
