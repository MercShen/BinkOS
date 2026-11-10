package com.github.gezimos.inkos.helper

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * 大我（Bigme）墨水屏刷新动画接口。
 *
 * 调用链（厂商系统内置，非公开接口，靠反射 + HiddenApiBypass 穿 hidden API 限制）：
 *
 * ```
 * xrz.framework.manager.XrzEinkManager(context)
 *   -> getDisplayPolicyManager()
 *      -> setEinkAnimationTimeout(int mode, long durationMs)
 * ```
 *
 * mode 是「面板坐标系」的擦除方向：1=从左到右 2=从右到左 3=从上到下 4=从下到上，
 * 屏幕旋转后需要按 [PANEL_MODES] 补偿。动画只在 App 刷新波形为 Regal(180) 或
 * HD256(0x800000B1) 时可见，因此 [init] 里会把本包的刷新模式拉到 Regal（与大我自家
 * 阅读器一致）。
 *
 * 只在「打开 / 关闭 App Drawer」两处调用；非大我设备 init() 失败后所有调用都是空操作。
 */
object BigmeEink {

    /** 手指滑动方向（屏幕坐标系），base 是 0° 旋转下的面板方向值。 */
    enum class Direction(val base: Int) {
        L2R(1), // 从左到右（手指向右）
        R2L(2), // 从右到左（手指向左）
        T2B(3), // 从上到下（手指向下）
        B2T(4); // 从下到上（手指向上）
    }

    private const val TAG = "BigmeEink"
    private const val CLASS_NAME = "xrz.framework.manager.XrzEinkManager"
    private const val ANIMATION_METHOD = "setEinkAnimationTimeout"
    private const val REGAL_MODE = 180
    private const val HD_256_MODE = 0x800000B1.toInt()

    /** 触发后动画模式的保持时长：要覆盖「调用 → 真正重绘」的间隔。 */
    private const val DURATION_MS = 500L

    /** 手势与导航之间的时间窗：超过这个间隔就不再认为动画由该手势触发。 */
    private const val SWIPE_WINDOW_MS = 600L

    /** 连击节流，避免两个窗口互相覆盖。 */
    private const val THROTTLE_MS = 120L

    /** 旋转 0/90/180/270 → 四个方向对应的面板模式（行 = 旋转档位，列 = Direction.base - 1）。 */
    private val PANEL_MODES = arrayOf(
        intArrayOf(1, 2, 3, 4), // 0°
        intArrayOf(3, 4, 2, 1), // 90°
        intArrayOf(2, 1, 4, 3), // 180°
        intArrayOf(4, 3, 1, 2), // 270°
    )

    @Volatile private var policy: Any? = null
    @Volatile private var animationMethod: Method? = null
    @Volatile private var policyGetter: Method? = null
    @Volatile private var refreshModeSetter: Method? = null
    @Volatile private var initialized = false
    @Volatile private var available = false

    @Volatile private var lastSwipe: Direction? = null
    @Volatile private var lastSwipeAt = 0L
    private var lastTriggerAt = 0L

    val isAvailable: Boolean get() = available

    /** 在 Application/Activity 启动时调用一次；非大我设备返回 false。 */
    fun init(context: Context): Boolean {
        if (initialized) return available
        synchronized(this) {
            if (initialized) return available
            initialized = true
            available = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // 只豁免大我框架包，避免全局放开 hidden API
                    HiddenApiBypass.addHiddenApiExemptions("Lxrz/framework/")
                }
                val cls = Class.forName(CLASS_NAME)
                val manager = cls.getDeclaredConstructor(Context::class.java)
                    .apply { isAccessible = true }
                    .newInstance(context.applicationContext)

                val p = cls.getMethod("getDisplayPolicyManager")
                    .apply { isAccessible = true }
                    .invoke(manager)
                    ?: throw IllegalStateException("getDisplayPolicyManager() == null")

                val m = p.javaClass.getMethod(
                    ANIMATION_METHOD,
                    Int::class.javaPrimitiveType,
                    Long::class.javaPrimitiveType
                ).apply { isAccessible = true }

                policy = p
                animationMethod = m
                policyGetter = runCatching {
                    p.javaClass.getMethod("getPolicyByPackage", String::class.java)
                        .apply { isAccessible = true }
                }.getOrNull()
                refreshModeSetter = runCatching {
                    p.javaClass.getMethod(
                        "setRefreshModeForPackage",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    ).apply { isAccessible = true }
                }.getOrNull()

                Log.i(TAG, "Bigme e-ink animation ready")
                true
            } catch (t: Throwable) {
                Log.i(TAG, "not a Bigme device / unavailable: ${t.javaClass.simpleName}: ${t.message}")
                false
            }
        }
        if (available) ensureAnimationVisible(context)
        return available
    }

    /**
     * 让动画真的可见：本包刷新波形必须是 Regal(180) 或 HD256。已满足则不写系统策略；
     * 否则写一次 Regal（持久，可在系统设置里改回）。
     */
    private fun ensureAnimationVisible(context: Context) {
        val getter = policyGetter ?: return
        val setter = refreshModeSetter ?: return
        try {
            val current = runCatching {
                @Suppress("UNCHECKED_CAST")
                val p = getter.invoke(policy, context.packageName)
                p?.javaClass?.getField("refreshMode")?.getInt(p)
            }.getOrNull()
            if (current == REGAL_MODE || current == HD_256_MODE) {
                Log.i(TAG, "refresh mode already animation-capable: $current")
                return
            }
            setter.invoke(policy, context.packageName, REGAL_MODE)
            Log.i(TAG, "refresh mode $current -> $REGAL_MODE for ${context.packageName}")
        } catch (t: Throwable) {
            Log.w(TAG, "ensureAnimationVisible failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 记录方向手势；由手势层在识别出方向时调用，供导航那一刻取用。 */
    fun noteSwipe(direction: Direction) {
        lastSwipe = direction
        lastSwipeAt = SystemClock.uptimeMillis()
    }

    /** 最近一次手势方向（仍在时间窗内），没有则返回 null。 */
    fun recentSwipeOrNull(): Direction? = recentSwipe()

    /** 打开一个由手势绑定的界面：按 [direction] 播放。 */
    fun onScreenOpened(context: Context, direction: Direction) {
        trigger(context, direction, "open")
    }

    /** 关闭同一个界面：按关闭方向 [direction] 播放。 */
    fun onScreenClosed(context: Context, direction: Direction) {
        trigger(context, direction, "close")
    }

    /**
     * 主页收藏应用列表翻页。方向由「翻到哪一页」决定，与手指方向无关：
     * 下一页 = 新内容自下而上进入（4），上一页 = 自上而下（3）。
     */
    fun onHomePageTurn(context: Context, forward: Boolean) {
        trigger(
            context,
            if (forward) Direction.B2T else Direction.T2B,
            if (forward) "home page next" else "home page prev",
        )
    }

    private fun recentSwipe(): Direction? =
        lastSwipe?.takeIf { SystemClock.uptimeMillis() - lastSwipeAt <= SWIPE_WINDOW_MS }

    private fun trigger(context: Context, direction: Direction, reason: String) {
        if (!init(context)) return
        val method = animationMethod ?: return
        val target = policy ?: return

        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerAt < THROTTLE_MS) return
        lastTriggerAt = now

        val rotation = rotationOf(context)
        val mode = panelMode(direction.base, rotation)
        try {
            val result = method.invoke(target, mode, DURATION_MS)
            Log.d(TAG, "screen $reason dir=${direction.name} rotation=$rotation panelMode=$mode -> $result")
        } catch (t: Throwable) {
            Log.w(TAG, "invoke failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 0° 下的方向值 → 当前屏幕旋转下的面板模式。 */
    private fun panelMode(base: Int, rotation: Int): Int {
        val row = PANEL_MODES[((rotation % 4) + 4) % 4]
        return row[(base - 1).coerceIn(0, 3)]
    }

    private fun rotationOf(context: Context): Int {
        return try {
            @Suppress("DEPRECATION")
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            @Suppress("DEPRECATION")
            wm.defaultDisplay.rotation
        } catch (_: Throwable) {
            0
        }
    }
}
