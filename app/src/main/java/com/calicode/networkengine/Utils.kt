package com.calicode.networkengine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

fun <T> lazyAsync(block: suspend CoroutineScope.() -> T) = GlobalScope.async(start = LAZY, block = block)

inline fun <T: Any, V> T.withSync(block: (T) -> V) = synchronized(this) { block.invoke(this) }
