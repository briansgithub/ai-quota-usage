package com.usagewidget.net

import com.usagewidget.domain.DiagnosticSanitizer
import com.usagewidget.domain.FetchAttempt
import com.usagewidget.domain.FetchOutcome
import com.usagewidget.domain.SoftwareSectionId
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.SerializationException

object FetchErrorClassifier {
    fun attempt(
        error: Throwable,
        sectionId: SoftwareSectionId,
        endpoint: String?,
        atEpochMs: Long,
        source: String? = "json"
    ): FetchAttempt {
        val classified = classify(error)
        return FetchAttempt.of(
            atEpochMs = atEpochMs,
            sectionId = sectionId,
            outcome = classified.outcome,
            httpStatus = classified.httpStatus,
            message = classified.message,
            endpoint = endpoint,
            source = source
        )
    }

    fun classify(error: Throwable): ClassifiedError {
        var current: Throwable? = error
        while (current != null) {
            when (current) {
                is SessionExpiredException -> return ClassifiedError(
                    FetchOutcome.AUTH_EXPIRED,
                    current.code.takeIf { it > 0 },
                    DiagnosticSanitizer.message(current.message)
                )
                is ProviderHttpException -> return ClassifiedError(
                    FetchOutcome.HTTP,
                    current.code,
                    DiagnosticSanitizer.message(current.message)
                )
                is SocketTimeoutException, is TimeoutException, is InterruptedIOException,
                is TimeoutCancellationException -> return ClassifiedError(
                    FetchOutcome.TIMEOUT,
                    message = DiagnosticSanitizer.message(current.message)
                )
                is UnknownHostException, is ConnectException, is NoRouteToHostException ->
                    return ClassifiedError(
                        FetchOutcome.NETWORK,
                        message = DiagnosticSanitizer.message(current.message)
                    )
                is SerializationException -> return ClassifiedError(
                    FetchOutcome.PARSE,
                    message = DiagnosticSanitizer.message(current.message)
                )
                is IllegalArgumentException -> {
                    val message = current.message.orEmpty()
                    val outcome = if (
                        message.contains("missing", ignoreCase = true) ||
                        message.contains("could not find", ignoreCase = true)
                    ) {
                        FetchOutcome.MISSING_FIELDS
                    } else {
                        FetchOutcome.PARSE
                    }
                    return ClassifiedError(outcome, message = DiagnosticSanitizer.message(message))
                }
            }
            current = current.cause
        }
        return when (error) {
            is java.io.IOException -> ClassifiedError(
                FetchOutcome.NETWORK,
                message = DiagnosticSanitizer.message(error.message)
            )
            else -> {
                val message = error.message.orEmpty()
                if (message.contains("accessToken", ignoreCase = true) ||
                    message.contains("not_authenticated", ignoreCase = true)
                ) {
                    ClassifiedError(FetchOutcome.AUTH_EXPIRED, message = DiagnosticSanitizer.message(message))
                } else {
                    ClassifiedError(FetchOutcome.UNKNOWN, message = DiagnosticSanitizer.message(message))
                }
            }
        }
    }

    data class ClassifiedError(
        val outcome: FetchOutcome,
        val httpStatus: Int? = null,
        val message: String? = null
    )
}
