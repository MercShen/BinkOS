package com.github.gezimos.inkos.helper

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

/**
 * PageKeyRouter 的判定全是纯函数，所以自定义翻页键的安全规则都能在 JVM 里钉住，
 * 不需要上真机试。
 *
 * 注意：这里只引用 `KeyEvent.KEYCODE_*` / `ACTION_*` 这类编译期内联常量，
 * 不构造 `android.view.KeyEvent`——项目没有 Robolectric，android.jar 里的方法是桩。
 */
class PageKeyRouterTest {

    private fun resolve(
        code: Int,
        up: Set<Int> = emptySet(),
        down: Set<Int> = emptySet(),
        volume: Boolean = false,
        playing: Boolean = false
    ) = PageKeyRouter.resolve(code, up, down, volume, playing)

    private fun target(
        code: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        up: Set<Int> = emptySet(),
        down: Set<Int> = emptySet(),
        volume: Boolean = false,
        playing: Boolean = false
    ) = PageKeyRouter.rewriteTarget(code, action, up, down, volume, playing)

    @Test fun pageKeysAlwaysPage() {
        // 自定义列表为空也必须翻页：Page 键没有别的消费者，不该藏在开关后面
        assertEquals(PageDirection.Up, resolve(KeyEvent.KEYCODE_PAGE_UP))
        assertEquals(PageDirection.Down, resolve(KeyEvent.KEYCODE_PAGE_DOWN))
    }

    @Test fun customKeysAreAdditiveAndDirectional() {
        assertEquals(PageDirection.Up, resolve(KeyEvent.KEYCODE_BOOKMARK, up = setOf(KeyEvent.KEYCODE_BOOKMARK)))
        assertEquals(PageDirection.Down, resolve(KeyEvent.KEYCODE_9, down = setOf(KeyEvent.KEYCODE_9)))
        // 绑到下一页的键不会被当成上一页
        assertNull(resolve(KeyEvent.KEYCODE_9, up = emptySet(), down = emptySet()))
        assertEquals(PageDirection.Down, resolve(KeyEvent.KEYCODE_9, down = setOf(KeyEvent.KEYCODE_9)))
    }

    @Test fun volumeKeysStillNeedTheSwitchAndYieldToAudio() {
        assertNull(resolve(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(PageDirection.Up, resolve(KeyEvent.KEYCODE_VOLUME_UP, volume = true))
        assertEquals(PageDirection.Down, resolve(KeyEvent.KEYCODE_VOLUME_DOWN, volume = true))
        // 正在放音频：音量键属于系统音量，不属于翻页
        assertNull(resolve(KeyEvent.KEYCODE_VOLUME_UP, volume = true, playing = true))
        assertNull(resolve(KeyEvent.KEYCODE_VOLUME_DOWN, volume = true, playing = true))
    }

    @Test fun activationAndBackKeysAreBlocked() {
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER,
            KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL
        )) {
            assertTrue("KEYCODE $code must be blocked", PageKeyRouter.blockedKeys.contains(code))
        }
    }

    @Test fun keysWithAnotherJobWarnButStayBindable() {
        for (code in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN
        )) {
            assertTrue("KEYCODE $code should warn, not block", PageKeyRouter.sharedKeys.contains(code))
            assertFalse(PageKeyRouter.blockedKeys.contains(code))
        }
        // 两份名单不能互相矛盾
        assertTrue(PageKeyRouter.blockedKeys.intersect(PageKeyRouter.sharedKeys).isEmpty())
    }

    @Test fun defaultsAreJustThePageKeys() {
        assertEquals(setOf(KeyEvent.KEYCODE_PAGE_UP), PageKeyRouter.defaultUpKeys)
        assertEquals(setOf(KeyEvent.KEYCODE_PAGE_DOWN), PageKeyRouter.defaultDownKeys)
    }

    @Test fun rewriteTargetsTheMatchingPageKey() {
        assertEquals(KeyEvent.KEYCODE_PAGE_UP, target(KeyEvent.KEYCODE_BOOKMARK, up = setOf(KeyEvent.KEYCODE_BOOKMARK)))
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, target(KeyEvent.KEYCODE_9, down = setOf(KeyEvent.KEYCODE_9)))
    }

    @Test fun rewriteCoversBothDownAndUpActions() {
        // 只改写 ACTION_DOWN 的话，Compose 的 KeyUp 分支会看到原始键码
        assertEquals(
            KeyEvent.KEYCODE_PAGE_UP,
            target(KeyEvent.KEYCODE_BOOKMARK, action = KeyEvent.ACTION_UP, up = setOf(KeyEvent.KEYCODE_BOOKMARK))
        )
        assertNull(target(KeyEvent.KEYCODE_BOOKMARK, action = KeyEvent.ACTION_MULTIPLE, up = setOf(KeyEvent.KEYCODE_BOOKMARK)))
    }

    @Test fun rewriteLeavesUnboundAndNativePageKeysAlone() {
        assertNull(target(KeyEvent.KEYCODE_A, up = setOf(KeyEvent.KEYCODE_BOOKMARK)))
        // 本来就是 Page 键：不要重复造事件
        assertNull(target(KeyEvent.KEYCODE_PAGE_DOWN, down = setOf(KeyEvent.KEYCODE_PAGE_DOWN)))
        assertNull(target(KeyEvent.KEYCODE_PAGE_UP))
    }

    @Test fun rewriteRespectsVolumeSwitchAndAudio() {
        assertEquals(KeyEvent.KEYCODE_PAGE_DOWN, target(KeyEvent.KEYCODE_VOLUME_DOWN, volume = true))
        assertNull(target(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(target(KeyEvent.KEYCODE_VOLUME_DOWN, volume = true, playing = true))
    }
}
