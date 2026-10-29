package com.watchlisten.music.ui.nav

/**
 * 页面栈的纯逻辑部分。
 *
 * 维护单一宿主内的页面顺序、替换与返回语义。
 * 连点合并与页面转场由调用方协调，
 * 页面栈自身只处理数据，不依赖 Android 视图；
 * 动画与手势在 Compose 层处理。
 */
class PageStack<T>(private val root: T) {

    private val stack = ArrayDeque<T>().apply { addLast(root) }

    val depth: Int get() = stack.size

    val current: T get() = stack.last()

    val isRootVisible: Boolean get() = stack.size == 1

    /** 栈内全部页面，从根到顶。 */
    fun snapshot(): List<T> = stack.toList()

    /** 系统重建时恢复已有路径，空快照退回根页。 */
    fun restore(pages: List<T>) {
        stack.clear()
        if (pages.isEmpty()) stack.addLast(root)
        else pages.forEach(stack::addLast)
    }

    /**
     * 压入新页。
     *
     * @param replace 为 true 时替换当前页而非叠加，对应原实现的"定位到"语义。
     * @return false 表示压栈被忽略（例如目标页已在栈顶）。
     */
    fun push(page: T, replace: Boolean = false): Boolean {
        if (stack.last() == page) return false
        if (replace) stack.removeLast()
        stack.addLast(page)
        return true
    }

    /** 回退一页。根页不可回退，返回 null 表示已在根页。 */
    fun pop(): T? = if (stack.size == 1) null else stack.removeLast()

    /** 回到根页，销毁其上的所有页面。 */
    fun popToRoot(): T {
        while (stack.size > 1) stack.removeLast()
        return stack.last()
    }

    /** 指定页面是否在栈内，供深链复用已有实例而不是重复压栈。 */
    fun contains(page: T): Boolean = stack.contains(page)

    /**
     * 供"回到当前播放"这类操作使用：把目标页移到栈顶但不改写中间层级。
     * 不在栈内时返回 false。
     */
    fun moveToTop(page: T): Boolean {
        if (!stack.remove(page)) return false
        stack.addLast(page)
        return true
    }
}
