package io.premiumspread.logging

import org.slf4j.MDC
import org.springframework.core.task.TaskDecorator

/** 제출 thread의 MDC를 worker에 복사하고 worker의 기존 MDC를 반드시 복원한다. */
object MdcContext {
    fun wrap(task: Runnable): Runnable {
        val captured = MDC.getCopyOfContextMap()
        return Runnable {
            val previous = MDC.getCopyOfContextMap()
            try {
                restore(captured)
                task.run()
            } finally {
                restore(previous)
            }
        }
    }

    internal fun restore(context: Map<String, String>?) {
        if (context.isNullOrEmpty()) MDC.clear() else MDC.setContextMap(context)
    }
}

class MdcTaskDecorator : TaskDecorator {
    override fun decorate(runnable: Runnable): Runnable = MdcContext.wrap(runnable)
}
