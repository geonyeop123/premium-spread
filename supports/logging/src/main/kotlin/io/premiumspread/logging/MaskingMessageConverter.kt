package io.premiumspread.logging

import ch.qos.logback.classic.pattern.MessageConverter
import ch.qos.logback.classic.spi.ILoggingEvent

/**
 * Logback 메시지 변환기
 *
 * 로그 메시지에서 민감 정보를 마스킹
 */
class MaskingMessageConverter : MessageConverter() {

    override fun convert(event: ILoggingEvent): String {
        val originalMessage = super.convert(event)
        return LogMaskingFilter.mask(originalMessage)
    }
}
