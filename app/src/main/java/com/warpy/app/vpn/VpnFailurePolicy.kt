package com.warpy.app.vpn

import com.warpy.app.model.Protocol

internal data class ValidationFailureDecision(
    val message: String,
    val recoverable: Boolean,
)

internal fun classifyInitialValidationFailure(
    hasValidatedNetwork: Boolean,
    protocol: Protocol?,
    probeFailure: String?,
): ValidationFailureDecision {
    if (!hasValidatedNetwork) {
        return ValidationFailureDecision(
            message = "Ожидание сети",
            recoverable = true,
        )
    }

    val failure = probeFailure.orEmpty()
    val terminalMessage = when {
        failure.contains("SOCKS server general failure", ignoreCase = true) &&
            protocol == Protocol.Hysteria2 ->
            "Профиль не подключился: сервер не принял Hysteria2 handshake; проверьте SNI, пароль и obfs"
        failure.contains("x509", ignoreCase = true) ||
            failure.contains("certificate", ignoreCase = true) ->
            "Профиль не подключился: TLS-сертификат не совпадает с SNI"
        failure.contains("authentication", ignoreCase = true) ||
            failure.contains("unauthorized", ignoreCase = true) ||
            failure.contains("invalid password", ignoreCase = true) ->
            "Профиль не подключился: сервер отклонил данные авторизации"
        else -> null
    }
    if (terminalMessage != null) {
        return ValidationFailureDecision(
            message = terminalMessage,
            recoverable = false,
        )
    }

    val recoverableMessage = when {
        failure.contains("connection refused", ignoreCase = true) ->
            "Сервер временно отклонил соединение"
        failure.contains("timeout", ignoreCase = true) ||
            failure.contains("timed out", ignoreCase = true) ->
            "Сервер временно не отвечает"
        else -> "Соединение восстанавливается"
    }
    return ValidationFailureDecision(
        message = recoverableMessage,
        recoverable = true,
    )
}
