package com.routeassistant.app.util

/** Simple result wrapper - avoids throwing across provider boundaries. */
sealed class AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>()
    data class Err(val message: String, val cause: Throwable? = null) : AppResult<Nothing>()
}

fun <T> AppResult<T>.getOrNull(): T? = (this as? AppResult.Ok)?.value
fun <T> AppResult<T>.errorOrNull(): String? = (this as? AppResult.Err)?.message
