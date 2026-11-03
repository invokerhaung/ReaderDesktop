package io.legado.app.help.source

import io.legado.app.data.entities.BaseSource
import io.legado.app.data.entities.BookSource
import org.mozilla.javascript.Scriptable
import kotlin.coroutines.CoroutineContext

/**
 * Phase 0 不共享 Rhino scope；后续可缓存 jsLib 的 scope。
 */
fun BaseSource.getShareScope(coroutineContext: CoroutineContext): Scriptable? = null

fun BookSource.getBookType(): Int = bookSourceType
