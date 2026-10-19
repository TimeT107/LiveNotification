package com.example.livenotification.island.hook

import android.view.View
import android.view.ViewGroup
import com.example.livenotification.island.util.IslandLog

/**
 * 跨 ClassLoader 的 ConstraintLayout 约束辅助。
 *
 * ============ 为什么必须这样写（真机实测教训） ============
 *
 * 真机日志：
 *   ★ View.onAttachedToWindow 命中锁屏根布局：
 *       id=0x7f0a0460 class=com.android.systemui.keyguard.ui.view.KeyguardRootView
 *   E attach 失败：锁屏根布局无法转成 ConstraintLayout（类加载器隔离）
 *
 * id 与类名都完全正确，但 `as? ConstraintLayout` 失败。
 * 根因是**类加载器隔离**：模块 ClassLoader 解析出的
 * `androidx.constraintlayout.widget.ConstraintLayout`
 * 与 SystemUI 实例的实际类**不是同一个 Class 对象**，
 * 因此 `is` / `as` 一律失败（祖先遍历失效也是同一个原因）。
 *
 * 对策：
 *   - 宿主类型用 `ViewGroup`（框架类，双方共享）
 *   - LayoutParams 用反射构造
 *   - 约束字段用反射按名读写（androidx 各版本字段名稳定）
 */
internal object ClConstraints {

    /**
     * ConstraintLayout.LayoutParams.PARENT_ID = **0**。
     *
     * ⚠ 真机踩坑记录：我最初写的是 -1（那是 ViewGroup.LayoutParams.MATCH_PARENT）。
     * 结果所有约束都变成 `topToTop = -1`（指向不存在的 id -1）而不是指向父容器，
     * 约束**完全不生效**，岛一直留在 (0,0)——表现为「宽度对、位置错」。
     * 这个错值从第 13 轮一直错到现在，靠真机日志才定位到。
     *
     * 已用 javap 对 constraintlayout-2.0.1.aar 核实：
     *   public static final int PARENT_ID = 0;
     */
    const val PARENT_ID = 0

    /** ConstraintLayout.LayoutParams.MATCH_CONSTRAINT */
    const val MATCH_CONSTRAINT = 0

    private const val LAYOUT_PARAMS =
        "androidx.constraintlayout.widget.ConstraintLayout\$LayoutParams"

    /** 父类链描述，用于诊断类加载器隔离 */
    fun superChain(v: Any): String {
        val sb = StringBuilder()
        var c: Class<*>? = v.javaClass
        var depth = 0
        while (c != null && depth < 8) {
            sb.append(c.simpleName).append(" < ")
            c = c.superclass
            depth++
        }
        return sb.toString().trimEnd(' ', '<')
    }

    fun isConstraintLayoutParams(lp: Any?): Boolean =
        lp != null && lp.javaClass.name == LAYOUT_PARAMS

    /** 反射造 ConstraintLayout.LayoutParams(width, height) */
    fun newLayoutParams(width: Int, height: Int): ViewGroup.LayoutParams? = try {
        val cls = Class.forName(LAYOUT_PARAMS)
        val ctor = cls.getConstructor(
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
        )
        ctor.newInstance(width, height) as? ViewGroup.LayoutParams
    } catch (t: Throwable) {
        IslandLog.e("[ClConstraints] 反射创建 ConstraintLayout.LayoutParams 失败", t)
        null
    }

    fun setInt(target: Any, field: String, value: Int) {
        try {
            target.javaClass.getField(field).setInt(target, value)
        } catch (t: Throwable) {
            IslandLog.w("[ClConstraints] 设置 $field 失败: ${t.message}")
        }
    }

    fun setFloat(target: Any, field: String, value: Float) {
        try {
            target.javaClass.getField(field).setFloat(target, value)
        } catch (t: Throwable) {
            IslandLog.w("[ClConstraints] 设置 $field 失败: ${t.message}")
        }
    }

    fun getInt(target: Any, field: String, def: Int): Int = try {
        target.javaClass.getField(field).getInt(target)
    } catch (_: Throwable) {
        def
    }

    /**
     * 给岛设置标准约束：左右贴父、上下贴父、bias 控制垂直位置。
     * 全部走反射，不依赖类型转换。
     */
    fun applyIslandConstraints(
        lp: ViewGroup.LayoutParams,
        width: Int,
        height: Int,
        horizontalBias: Float,
        verticalBias: Float
    ) {
        lp.width = width
        lp.height = height
        setInt(lp, "startToStart", PARENT_ID)
        setInt(lp, "endToEnd", PARENT_ID)
        setInt(lp, "topToTop", PARENT_ID)
        setInt(lp, "bottomToBottom", PARENT_ID)
        setFloat(lp, "horizontalBias", horizontalBias)
        setFloat(lp, "verticalBias", verticalBias)
    }

    /** ConstraintLayout.LayoutParams.UNSET */
    const val UNSET = -1

    /**
     * 只用 topMargin 的确定性定位（不依赖 verticalBias 的求解时机）。
     *
     * ============ 为什么需要它（真机实测结论） ============
     *
     * verticalBias 设成 0.84、topMargin 设成 2052，岛却始终停在 top=0。
     * 而唯一能强制父容器重解约束的 API（ConstraintSet.clone/applyTo）
     * 在跨 ClassLoader 时必然失败：
     *     Cannot cast KeyguardRootView to ConstraintLayout
     * （SystemUI 实例的 ConstraintLayout 与模块拿到的那份不是同一个 Class，
     *   MethodHandle 也跨不过去。）
     *
     * 也就是说「靠 bias 定位」在这个宿主上不可靠。
     *
     * 但 topMargin 是 MarginLayoutParams 的普通字段：配合单一的 topToTop 约束，
     * 它就是「距父容器顶部 N 像素」，不存在求解时机问题 —— 确定性成立。
     *
     * 代价：垂直位置不再随 bias 自动缩放，由调用方按屏幕高度算好传进来。
     */
    fun applyDeterministicTop(
        lp: ViewGroup.LayoutParams,
        width: Int,
        height: Int,
        topMarginPx: Int
    ) {
        lp.width = width
        lp.height = height
        // 只保留 start/end 与 top 约束：唯一解，无歧义
        setInt(lp, "startToStart", PARENT_ID)
        setInt(lp, "endToEnd", PARENT_ID)
        setInt(lp, "topToTop", PARENT_ID)
        // 关键：解掉 bottom 约束。否则 top+bottom+bias 三者共同决定位置，
        // 结果又回到不可控的 bias 上。
        setInt(lp, "bottomToBottom", UNSET)
        setFloat(lp, "verticalBias", 0f)
        if (lp is ViewGroup.MarginLayoutParams) {
            lp.topMargin = topMarginPx
        } else {
            runCatching {
                lp.javaClass.getField("topMargin").setInt(lp, topMarginPx)
            }.onFailure { IslandLog.w("[ClConstraints] 设置 topMargin 失败: ${it.message}") }
        }
    }

    /** 约束是否还完整（被 ConstraintSet.apply 冲掉时判为不完整） */
    fun isIntact(lp: Any?): Boolean {
        if (!isConstraintLayoutParams(lp)) return false
        return getInt(lp!!, "topToTop", Int.MIN_VALUE) == PARENT_ID &&
            getInt(lp, "startToStart", Int.MIN_VALUE) == PARENT_ID &&
            getInt(lp, "endToEnd", Int.MIN_VALUE) == PARENT_ID
    }

    private const val CONSTRAINT_SET =
        "androidx.constraintlayout.widget.ConstraintSet"

    /**
     * 用 ConstraintSet 给岛施加约束。
     *
     * ============ 为什么必须走 ConstraintSet（真机踩坑结论） ============
     *
     * 只改 `view.layoutParams` 的字段是不够的：SystemUI 的锁屏蓝图会在我
     * `addView` 之后**再跑一次 `ConstraintSet.apply`**，把 LayoutParams 上
     * 的约束覆盖掉 —— 真机上表现为岛宽度正确（1070）但 `top=0`，bias 失效。
     *
     * 我当时的错误修法是「手动宿主 measure + layout」去逼它生效，
     * 结果那会重新触发 onLayoutChange → 再进 applyConstraints → 无限递归 →
     * SystemUI 主线程卡死 ANR（真机实测把用户手机搞成锁屏加载不出来）。
     *
     * 正确做法就是用 SystemUI 自己的机制：ConstraintSet.apply。
     * 这样我们的约束和蓝图共存，而不是被覆盖。
     */
    fun applyViaConstraintSet(
        container: ViewGroup,
        child: View,
        verticalBias: Float
    ): Boolean {
        return try {
            val cls = Class.forName(CONSTRAINT_SET)
            val set = cls.getDeclaredConstructor().newInstance()

            // ============ 关键：不能用 getMethod 做参数类型校验 ============
            //
            // 真机报错：
            //   ConstraintSet.clone argument 1 has type
            //     androidx.constraintlayout.widget.ConstraintLayout,
            //   got com.android.systemui.keyguard.ui.view.KeyguardRootView
            //
            // 这是**同一个类加载器隔离问题**：getMethod("clone", ConstraintLayout.class)
            // 里的 ConstraintLayout 来自我的 ClassLoader，而传入的实例来自 SystemUI 的
            // ClassLoader，两者不是同一个 Class → 参数校验必然失败。
            //
            // 所以参数类型一律不要写死，改成按名字找方法、用 isAssignableFrom 动态判定。
            val clone = findMethodAccepting(cls, "clone", container)
            val applyTo = findMethodAccepting(cls, "applyTo", container)
            if (clone == null || applyTo == null) {
                IslandLog.w("[ClConstraints] 找不到 clone/applyTo 的可用重载，回退")
                return false
            }
            clone.invoke(set, container)

        val connect = cls.getMethod(
            "connect",
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType
        )
        val TOP = 3
        val BOTTOM = 4
        val START = 6
        val END = 7
        connect.invoke(set, child.id, START, PARENT_ID, START)
        connect.invoke(set, child.id, END, PARENT_ID, END)
        connect.invoke(set, child.id, TOP, PARENT_ID, TOP)
        connect.invoke(set, child.id, BOTTOM, PARENT_ID, BOTTOM)

        cls.getMethod(
            "setVerticalBias", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
        ).invoke(set, child.id, verticalBias)

            applyTo.invoke(set, container)
            true
        } catch (t: Throwable) {
            IslandLog.w("[ClConstraints] ConstraintSet 施加约束失败（回退到 LayoutParams）: ${t.message}")
            false
        }
    }

    /**
     * 按名字找一个「只收一个参数、且该参数类型能接受 [arg]」的方法。
     *
     * 这是为了绕开类加载器隔离：不能写死参数类型，只能按运行时类型匹配。
     */
    private fun findMethodAccepting(
        cls: Class<*>,
        name: String,
        arg: Any
    ): java.lang.reflect.Method? {
        val argClass = arg.javaClass
        // 优先精确匹配（同名同参），否则退化为 isAssignableFrom 匹配
        val candidates = cls.methods.filter {
            it.name == name && it.parameterCount == 1
        }
        candidates.firstOrNull { it.parameterTypes[0] == argClass }?.let { return it }
        candidates.firstOrNull { it.parameterTypes[0].isAssignableFrom(argClass) }?.let { return it }
        // 再退一步：按类名匹配（隔离场景下父类不是同一个 Class 对象）
        val targetName = argClass.name
        return candidates.firstOrNull { m ->
            var c: Class<*>? = argClass
            while (c != null) {
                if (c.name == m.parameterTypes[0].name) return@firstOrNull true
                c = c.superclass
            }
            false
        }
    }

    /**
     * 用 MethodHandle 调 ConstraintSet.clone/applyTo —— 这是唯一能跨 ClassLoader
     * 传递「SystemUI 的 ConstraintLayout 实例」的方式。
     *
     * ============ 为什么 Method.invoke 不行、MethodHandle 行 ============
     *
     * `Method.invoke` 会**严格校验**参数类型：
     *   ConstraintSet.clone argument 1 has type androidx...ConstraintLayout,
     *     got com.android.systemui...KeyguardRootView
     * 两个 ConstraintLayout 来自不同 ClassLoader，不是同一个 Class 对象，
     * 无论怎么按名字挑方法都会卡在这一步（真机实测）。
     *
     * `MethodHandle.invoke` 的类型检查发生在**调用点**，按实参的运行时类型
     * 动态解析，因此可以正确接受 SystemUI 那个 ClassLoader 里的实例。
     * `asType` 则把「以 Object 为形参」的句柄适配成我们实际要传的类型。
     */
    fun applyViaMethodHandle(
        container: ViewGroup,
        child: View,
        verticalBias: Float
    ): Boolean {
        return try {
            val cls = Class.forName(CONSTRAINT_SET)
            val set = cls.getDeclaredConstructor().newInstance()

            val lookup = java.lang.invoke.MethodHandles.publicLookup()

            /**
             * 取「单参方法」的句柄，并把参数类型放宽到 Object 以便跨 ClassLoader 调用。
             *
             * 真机踩过的两个坑：
             *  1) asType 目标写成 (Object[])Object →
             *       cannot convert MethodHandle(ConstraintSet,KeyguardRootView) to (Object[])Object
             *     （返回类型/参数形态都拼错了）
             *  2) 想直接强转成 BiConsumer →
             *       MethodHandleImpl cannot be cast to java.util.function.BiConsumer
             *     Kotlin 的 `as` 是普通类型转换，不会触发 invokedynamic 适配。
             *
             * 正确做法：用 asType 把参数放宽为 Object（赋值兼容即可，
             * 不做 ClassLoader 身份校验），再用 invokeWithArguments 调用。
             */
            fun wideHandle(name: String): java.lang.invoke.MethodHandle? {
                val m = findMethodAccepting(cls, name, container) ?: return null
                return try {
                    val h = lookup.unreflect(m)
                    h.asType(
                        java.lang.invoke.MethodType.methodType(
                            h.type().returnType(),
                            cls,
                            Any::class.java
                        )
                    )
                } catch (t: Throwable) {
                    IslandLog.w("[ClConstraints] $name 放宽参数类型失败: ${t.message}")
                    null
                }
            }

            val mhClone = wideHandle("clone")
            val mhApply = wideHandle("applyTo")
            if (mhClone == null || mhApply == null) {
                IslandLog.w("[ClConstraints] 找不到 clone/applyTo")
                return false
            }

            mhClone.invokeWithArguments(set, container)

            val connect = cls.getMethod(
                "connect",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            val TOP = 3
            val BOTTOM = 4
            val START = 6
            val END = 7
            connect.invoke(set, child.id, START, PARENT_ID, START)
            connect.invoke(set, child.id, END, PARENT_ID, END)
            connect.invoke(set, child.id, TOP, PARENT_ID, TOP)
            connect.invoke(set, child.id, BOTTOM, PARENT_ID, BOTTOM)

            cls.getMethod(
                "setVerticalBias", Int::class.javaPrimitiveType, Float::class.javaPrimitiveType
            ).invoke(set, child.id, verticalBias)

            mhApply.invokeWithArguments(set, container)
            IslandLog.i("[ClConstraints] ConstraintSet 施加成功（MethodHandle）bias=$verticalBias")
            true
        } catch (t: Throwable) {
            IslandLog.w("[ClConstraints] MethodHandle 施加约束失败: ${t.message}")
            false
        }
    }
}
