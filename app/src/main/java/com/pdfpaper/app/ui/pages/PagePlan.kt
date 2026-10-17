package com.pdfpaper.app.ui.pages

/**
 * One page in the edit plan: which source page it is, which loaded document it
 * came from, and any extra rotation applied in this session (T10).
 */
data class PlanPage(
    val docIndex: Int,
    val sourceIndex: Int,
    val rotationDelta: Int = 0
)

/**
 * Pure page-plan edits (§14.11.2 rotation, §7.7.3 order). Pure Kotlin so the
 * undo/redo core is unit-testable without Android. Every function returns a new
 * immutable list, which makes the history snapshots cheap and safe to share.
 */
object PagePlan {

    fun rotate(plan: List<PlanPage>, positions: Set<Int>, degrees: Int): List<PlanPage> =
        plan.mapIndexed { index, page ->
            if (index in positions) page.copy(rotationDelta = page.rotationDelta + degrees) else page
        }

    fun delete(plan: List<PlanPage>, positions: Set<Int>): List<PlanPage> =
        plan.filterIndexed { index, _ -> index !in positions }

    /** Moves every selected position one slot up, keeping their relative order. */
    fun moveUp(plan: List<PlanPage>, positions: Set<Int>): List<PlanPage> =
        plan.toMutableList().also { list ->
            val selected = positions.toMutableSet()
            for (i in 0 until list.size) {
                if (i in selected && i > 0 && (i - 1) !in selected) {
                    val above = list[i - 1]
                    list[i - 1] = list[i]
                    list[i] = above
                    selected.remove(i)
                    selected.add(i - 1)
                }
            }
        }

    /** Moves every selected position one slot down, keeping their relative order. */
    fun moveDown(plan: List<PlanPage>, positions: Set<Int>): List<PlanPage> =
        plan.toMutableList().also { list ->
            val selected = positions.toMutableSet()
            for (i in list.size - 1 downTo 0) {
                if (i in selected && i < list.size - 1 && (i + 1) !in selected) {
                    val below = list[i + 1]
                    list[i + 1] = list[i]
                    list[i] = below
                    selected.remove(i)
                    selected.add(i + 1)
                }
            }
        }
}

/**
 * Bounded undo/redo over immutable [PlanPage] snapshots. Each [commit] records
 * the previous plan; [undo]/[redo] walk the two stacks. The stacks are capped so
 * a long editing session cannot grow without bound.
 */
class PagePlanHistory(initial: List<PlanPage>, private val limit: Int = 64) {

    private val past = ArrayDeque<List<PlanPage>>()
    private val future = ArrayDeque<List<PlanPage>>()

    var plan: List<PlanPage> = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records [newPlan] as the current state, enabling undo back to the old one. */
    fun commit(newPlan: List<PlanPage>) {
        if (newPlan == plan) return
        past.addLast(plan)
        if (past.size > limit) past.removeFirst()
        future.clear()
        plan = newPlan
    }

    fun undo(): Boolean {
        val previous = past.removeLastOrNull() ?: return false
        future.addLast(plan)
        plan = previous
        return true
    }

    fun redo(): Boolean {
        val next = future.removeLastOrNull() ?: return false
        past.addLast(plan)
        plan = next
        return true
    }

    /** Drops all history and starts again from [newPlan]. */
    fun reset(newPlan: List<PlanPage>) {
        past.clear()
        future.clear()
        plan = newPlan
    }
}
