package com.umay.pdfreader.data.repository

import android.content.Context
import com.umay.pdfreader.Book
import com.umay.pdfreader.BookStore
import com.umay.pdfreader.domain.repository.LibraryRepository
import java.io.File

/**
 * v5.2 — LibraryRepository uygulaması: BookStore okuma-yönetim yüzeyi.
 */
class LibraryRepositoryImpl : LibraryRepository {

    override fun loadBooks(ctx: Context): List<Book> = BookStore.loadBooks(ctx)

    override fun sourceFile(ctx: Context, id: String): File =
        BookStore.sourceFile(ctx, id)

    override fun deleteBook(ctx: Context, id: String) = BookStore.deleteBook(ctx, id)

    override fun ensureCover(ctx: Context, id: String): File? =
        BookStore.ensureCover(ctx, id)
}
