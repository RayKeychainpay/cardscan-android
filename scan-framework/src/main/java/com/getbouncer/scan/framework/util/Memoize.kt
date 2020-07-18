package com.getbouncer.scan.framework.util

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A class that memoizes the result of a suspend function. Only one coroutine will ever perform the
 * work in the suspend function, others will suspend until a result is available, and then return
 * that result.
 */
private class MemoizeSuspend0<out Result>(private val f: suspend () -> Result) {
    private val initializeMutex = Mutex()

    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    fun memoize(): suspend () -> Result = {
        initializeMutex.withLock {
            if (value == UNINITIALIZED_VALUE) {
                value = f()
            }
            @Suppress("UNCHECKED_CAST") (value as Result)
        }
    }
}

/**
 * A class that memoizes the result of a suspend function. Only one coroutine will ever perform the
 * work in the suspend function, others will suspend until a result is available, and then return
 * that result.
 */
private class MemoizeSuspend1<in Input, out Result>(private val f: suspend (Input) -> Result) {
    private val lookupMutex = Mutex()

    private val values = mutableMapOf<Input, Result>()
    private val mutexes = mutableMapOf<Input, Mutex>()

    private suspend fun getMutex(input: Input): Mutex = lookupMutex.withLock {
        mutexes.getOrPut(input) { Mutex() }
    }

    fun memoize(): suspend (Input) -> Result = { input ->
        getMutex(input).withLock {
            values.getOrPut(input) { f(input) }
        }
    }
}

/**
 * A class that memoizes the result of a suspend function. Only one coroutine will ever perform the
 * work in the suspend function, others will suspend until a result is available, and then return
 * that result.
 */
private class MemoizeSuspend2<in Input1, in Input2, out Result>(private val f: suspend (Input1, Input2) -> Result) {
    private val lookupMutex = Mutex()

    private val values = mutableMapOf<Pair<Input1, Input2>, Result>()
    private val mutexes = mutableMapOf<Pair<Input1, Input2>, Mutex>()

    private suspend fun getMutex(input1: Input1, input2: Input2): Mutex = lookupMutex.withLock {
        mutexes.getOrPut(input1 to input2) { Mutex() }
    }

    fun memoize(): suspend (Input1, Input2) -> Result = { input1, input2 ->
        getMutex(input1, input2).withLock {
            values.getOrPut(input1 to input2) { f(input1, input2) }
        }
    }
}

/**
 * A class that memoizes the result of a suspend function. Only one coroutine will ever perform the
 * work in the suspend function, others will suspend until a result is available, and then return
 * that result.
 */
private class MemoizeSuspend3<in Input1, in Input2, in Input3, out Result>(private val f: suspend (Input1, Input2, Input3) -> Result) {
    private val lookupMutex = Mutex()

    private val values = mutableMapOf<Triple<Input1, Input2, Input3>, Result>()
    private val mutexes = mutableMapOf<Triple<Input1, Input2, Input3>, Mutex>()

    private suspend fun getMutex(input1: Input1, input2: Input2, input3: Input3): Mutex = lookupMutex.withLock {
        mutexes.getOrPut(Triple(input1, input2, input3)) { Mutex() }
    }

    fun memoize(): suspend (Input1, Input2, Input3) -> Result = { input1, input2, input3 ->
        getMutex(input1, input2, input3).withLock {
            values.getOrPut(Triple(input1, input2, input3)) { f(input1, input2, input3) }
        }
    }
}

/**
 * A class that memoizes the result of a function. This method is threadsafe. Only one thread will
 * ever invoke the backing function, other threads will block until a result is available, and then
 * return that result.
 */
private class Memoize0<out Result>(private val function: () -> Result) : () -> Result {
    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    @Synchronized
    override fun invoke(): Result {
        if (value == UNINITIALIZED_VALUE) {
            value = function()
        }
        @Suppress("UNCHECKED_CAST") return (value as Result)
    }
}

/**
 * A class that memoizes the result of a function. This method is threadsafe. Only one thread will
 * ever invoke the backing function, other threads will block until a result is available, and then
 * return that result.
 */
private class Memoize1<in Input, out Result>(private val function: (Input) -> Result) : (Input) -> Result {
    private val values = mutableMapOf<Input, Result>()
    private val locks = mutableMapOf<Input, Any>()

    @Synchronized
    private fun getLock(input: Input): Any = locks.getOrPut(input) { Object() }

    override fun invoke(input: Input): Result {
        val lock = getLock(input)
        return synchronized(lock) {
            values.getOrPut(input) { function(input) }
        }
    }
}

/**
 * A class that memoizes the result of a function. This method is threadsafe. Only one thread will
 * ever invoke the backing function, other threads will block until a result is available, and then
 * return that result.
 */
private class Memoize2<in Input1, in Input2, out Result>(private val function: (Input1, Input2) -> Result) : (Input1, Input2) -> Result {
    private val values = mutableMapOf<Pair<Input1, Input2>, Result>()
    private val locks = mutableMapOf<Pair<Input1, Input2>, Any>()

    @Synchronized
    private fun getLock(input1: Input1, input2: Input2): Any = locks.getOrPut(input1 to input2) { Object() }

    override fun invoke(input1: Input1, input2: Input2): Result {
        val lock = getLock(input1, input2)
        return synchronized(lock) {
            values.getOrPut(input1 to input2) { function(input1, input2) }
        }
    }
}

/**
 * A class that memoizes the result of a function. This method is threadsafe. Only one thread will
 * ever invoke the backing function, other threads will block until a result is available, and then
 * return that result.
 */
private class Memoize3<in Input1, in Input2, in Input3, out Result>(private val function: (Input1, Input2, Input3) -> Result) : (Input1, Input2, Input3) -> Result {
    private val values = mutableMapOf<Triple<Input1, Input2, Input3>, Result>()
    private val locks = mutableMapOf<Triple<Input1, Input2, Input3>, Any>()

    @Synchronized
    private fun getLock(input1: Input1, input2: Input2, input3: Input3): Any = locks.getOrPut(Triple(input1, input2, input3)) { Object() }

    override fun invoke(input1: Input1, input2: Input2, input3: Input3): Result {
        val lock = getLock(input1, input2, input3)
        return synchronized(lock) {
            values.getOrPut(Triple(input1, input2, input3)) { function(input1, input2, input3) }
        }
    }
}

/**
 * Cache the result from calling this method. Subsequent calls, even with different parameters, will not change the
 * cached output.
 */
private class CachedFirstResultSuspend1<in Input, out Result>(private val f: suspend (Input) -> Result) {
    private val initializeMutex = Mutex()

    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    fun cacheFirstResult(): suspend (Input) -> Result = { input ->
        initializeMutex.withLock {
            if (value == UNINITIALIZED_VALUE) {
                value = f(input)
            }
            @Suppress("UNCHECKED_CAST") (value as Result)
        }
    }
}

/**
 * Cache the result from calling this method. Subsequent calls, even with different parameters, will not change the
 * cached output.
 */
private class CachedFirstResultSuspend2<in Input1, in Input2, out Result>(private val f: suspend (Input1, Input2) -> Result) {
    private val initializeMutex = Mutex()

    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    fun cacheFirstResult(): suspend (Input1, Input2) -> Result = { input1, input2 ->
        initializeMutex.withLock {
            if (value == UNINITIALIZED_VALUE) {
                value = f(input1, input2)
            }
            @Suppress("UNCHECKED_CAST") (value as Result)
        }
    }
}

/**
 * Cache the result from calling this method. Subsequent calls, even with different parameters, will not change the
 * cached output.
 */
private class CachedFirstResultSuspend3<in Input1, in Input2, in Input3, out Result>(private val f: suspend (Input1, Input2, Input3) -> Result) {
    private val initializeMutex = Mutex()

    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    fun cacheFirstResult(): suspend (Input1, Input2, Input3) -> Result = { input1, input2, input3 ->
        initializeMutex.withLock {
            if (value == UNINITIALIZED_VALUE) {
                value = f(input1, input2, input3)
            }
            @Suppress("UNCHECKED_CAST") (value as Result)
        }
    }
}

/**
 * Cache the result from calling this method. Subsequent calls, even with different parameters, will not change the
 * cached output.
 */
private class CachedFirstResult1<in Input, out Result>(private val function: (Input) -> Result) : (Input) -> Result {
    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    @Synchronized
    override fun invoke(input: Input): Result {
        if (value == UNINITIALIZED_VALUE) {
            value = function(input)
        }
        @Suppress("UNCHECKED_CAST") return (value as Result)
    }
}

/**
 * Cache the result from calling this method. Subsequent calls, even with different parameters, will not change the
 * cached output.
 */
private class CachedFirstResult2<in Input1, in Input2, out Result>(private val function: (Input1, Input2) -> Result) : (Input1, Input2) -> Result {
    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    @Synchronized
    override fun invoke(input1: Input1, input2: Input2): Result {
        if (value == UNINITIALIZED_VALUE) {
            value = function(input1, input2)
        }
        @Suppress("UNCHECKED_CAST") return (value as Result)
    }
}

/**
 * Cache the result from calling this method. Subsequent calls, even with different parameters, will not change the
 * cached output.
 */
private class CachedFirstResult3<in Input1, in Input2, in Input3, out Result>(private val function: (Input1, Input2, Input3) -> Result) : (Input1, Input2, Input3) -> Result {
    private object UNINITIALIZED_VALUE
    @Volatile private var value: Any? = UNINITIALIZED_VALUE

    @Synchronized
    override fun invoke(input1: Input1, input2: Input2, input3: Input3): Result {
        if (value == UNINITIALIZED_VALUE) {
            value = function(input1, input2, input3)
        }
        @Suppress("UNCHECKED_CAST") return (value as Result)
    }
}

fun <Result> (() -> Result).memoized(): () -> Result = Memoize0(this)
fun <Input, Result> ((Input) -> Result).memoized(): (Input) -> Result = Memoize1(this)
fun <Input1, Input2, Result> ((Input1, Input2) -> Result).memoized(): (Input1, Input2) -> Result = Memoize2(this)
fun <Input1, Input2, Input3, Result> ((Input1, Input2, Input3) -> Result).memoized(): (Input1, Input2, Input3) -> Result = Memoize3(this)

fun <Result> (suspend () -> Result).memoizedSuspend() = MemoizeSuspend0(this).memoize()
fun <Input, Result> (suspend (Input) -> Result).memoizedSuspend() = MemoizeSuspend1(this).memoize()
fun <Input1, Input2, Result> (suspend (Input1, Input2) -> Result).memoizedSuspend() = MemoizeSuspend2(this).memoize()
fun <Input1, Input2, Input3, Result> (suspend (Input1, Input2, Input3) -> Result).memoizedSuspend() = MemoizeSuspend3(this).memoize()

fun <Result> memoize(f: () -> Result): () -> Result = Memoize0(f)
fun <Input, Result> memoize(f: (Input) -> Result): (Input) -> Result = Memoize1(f)
fun <Input1, Input2, Result> memoize(f: (Input1, Input2) -> Result): (Input1, Input2) -> Result = Memoize2(f)
fun <Input1, Input2, Input3, Result> memoize(f: (Input1, Input2, Input3) -> Result): (Input1, Input2, Input3) -> Result = Memoize3(f)

fun <Result> memoizeSuspend(f: suspend() -> Result): suspend () -> Result = MemoizeSuspend0(f).memoize()
fun <Input, Result> memoizeSuspend(f: suspend(Input) -> Result): suspend (Input) -> Result = MemoizeSuspend1(f).memoize()
fun <Input1, Input2, Result> memoizeSuspend(f: suspend(Input1, Input2) -> Result): suspend (Input1, Input2) -> Result = MemoizeSuspend2(f).memoize()
fun <Input1, Input2, Input3, Result> memoizeSuspend(f: suspend(Input1, Input2, Input3) -> Result): suspend (Input1, Input2, Input3) -> Result = MemoizeSuspend3(f).memoize()

fun <Result> (() -> Result).cachedFirstResult(): () -> Result = Memoize0(this)
fun <Input, Result> ((Input) -> Result).cachedFirstResult(): (Input) -> Result = CachedFirstResult1(this)
fun <Input1, Input2, Result> ((Input1, Input2) -> Result).cachedFirstResult(): (Input1, Input2) -> Result = CachedFirstResult2(this)
fun <Input1, Input2, Input3, Result> ((Input1, Input2, Input3) -> Result).cachedFirstResult(): (Input1, Input2, Input3) -> Result = CachedFirstResult3(this)

fun <Result> (suspend () -> Result).cachedFirstResultSuspend() = MemoizeSuspend0(this).memoize()
fun <Input, Result> (suspend (Input) -> Result).cachedFirstResultSuspend() = CachedFirstResultSuspend1(this).cacheFirstResult()
fun <Input1, Input2, Result> (suspend (Input1, Input2) -> Result).cachedFirstResultSuspend() = CachedFirstResultSuspend2(this).cacheFirstResult()
fun <Input1, Input2, Input3, Result> (suspend (Input1, Input2, Input3) -> Result).cachedFirstResultSuspend() = CachedFirstResultSuspend3(this).cacheFirstResult()

fun <Result> cacheFirstResult(f: () -> Result): () -> Result = Memoize0(f)
fun <Input, Result> cacheFirstResult(f: (Input) -> Result): (Input) -> Result = CachedFirstResult1(f)
fun <Input1, Input2, Result> cacheFirstResult(f: (Input1, Input2) -> Result): (Input1, Input2) -> Result = CachedFirstResult2(f)
fun <Input1, Input2, Input3, Result> cacheFirstResult(f: (Input1, Input2, Input3) -> Result): (Input1, Input2, Input3) -> Result = CachedFirstResult3(f)

fun <Result> cacheFirstResultSuspend(f: suspend() -> Result) = MemoizeSuspend0(f).memoize()
fun <Input, Result> cacheFirstResultSuspend(f: suspend(Input) -> Result) = CachedFirstResultSuspend1(f).cacheFirstResult()
fun <Input1, Input2, Result> cacheFirstResultSuspend(f: suspend(Input1, Input2) -> Result) = CachedFirstResultSuspend2(f).cacheFirstResult()
fun <Input1, Input2, Input3, Result> cacheFirstResultSuspend(f: suspend(Input1, Input2, Input3) -> Result) = CachedFirstResultSuspend3(f).cacheFirstResult()
