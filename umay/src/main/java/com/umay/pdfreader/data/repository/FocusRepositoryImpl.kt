package com.umay.pdfreader.data.repository

import android.content.Context
import com.umay.pdfreader.PomodoroManager
import com.umay.pdfreader.domain.repository.FocusRepository

/**
 * v5.2 — FocusRepository uygulaması: PomodoroManager'ı INCE sarmalar.
 * Yeni davranış eklemez; tek sorumluluk = domain sözleşmesini tek nesneye bağlamak.
 */
class FocusRepositoryImpl : FocusRepository {

    override fun isRunning(): Boolean = PomodoroManager.isRunning()

    override fun formatRemaining(ms: Long): String = PomodoroManager.formatRemaining(ms)

    override fun start(
        ctx: Context,
        minutes: Int,
        onTick: (Long) -> Unit,
        onEnd: (completed: Boolean) -> Unit
    ) = PomodoroManager.start(ctx, minutes, onTick, onEnd)

    override fun cancel(onEnd: (completed: Boolean) -> Unit) =
        PomodoroManager.cancel(onEnd)
}
