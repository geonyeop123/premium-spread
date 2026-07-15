package io.premiumspread.interfaces.api.auth

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.http.HttpStatus
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.server.ResponseStatusException

class LoginMemberArgumentResolver : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean = parameter.hasParameterAnnotation(
        LoginMemberId::class.java,
    )

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): Any {
        val principal = webRequest.getNativeRequest(HttpServletRequest::class.java)?.userPrincipal
        principal?.name?.toLongOrNull()?.let { return it }
        throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.")
    }
}
