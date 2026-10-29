package com.umay.pdfreader.domain.repository

import android.content.Context
import com.umay.pdfreader.Book
import java.io.File

/**
 * v5.2 (ADIM C temizliği) — Kütüphane domain sözleşmesi (çekirdek okuma).
 * Altında yatan: BookStore. Kitap oluşturma/OCR akışları henüz bu
 * interface'e taşınmadı (aşamalı geçiş kuralı); ilk dalga okuma-yönetim.
 */
interface LibraryRepository {

    /** Kütüphanedeki tüm kitaplar (dosya bozulması halinde boş liste). */
    fun loadBooks(ctx: Context): List<Book>

    /** Kitabın kaynağı (kütüphane altındaki source.pdf). */
    fun sourceFile(ctx: Context, id: String): File

    /** Kitabı ve tüm alt verisini siler. */
    fun deleteBook(ctx: Context, id: String)

    /** Kapak yoksa üretir (gradient fallback dahil), varsa döndürür. */
    fun ensureCover(ctx: Context, id: String): File?
}
