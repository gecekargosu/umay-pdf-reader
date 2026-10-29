package com.umay.pdfreader.data.repository

import com.umay.pdfreader.CacheKeeper
import com.umay.pdfreader.domain.repository.CacheBudgetRepository
import java.io.File

/**
 * v5.2 — CacheBudgetRepository uygulaması: CacheKeeper ince sarmalayıcısı.
 */
class CacheBudgetRepositoryImpl : CacheBudgetRepository {

    override val maxBytes: Long get() = CacheKeeper.MAX_CACHE_BYTES

    override fun trim(dir: File?, prefix: String): Long =
        CacheKeeper.trim(dir, prefix)

    override fun human(bytes: Long): String = CacheKeeper.human(bytes)
}
