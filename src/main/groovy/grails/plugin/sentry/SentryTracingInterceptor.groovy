package grails.plugin.sentry

import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.ModelAndView

import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class SentryTracingInterceptor implements HandlerInterceptor {

    static final String TRANSACTION_ATTRIBUTE = 'sentry.transaction'

    SentryConfig config

    SentryTracingInterceptor(SentryConfig config) {
        this.config = config
    }

    @Override
    boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!config?.tracingEnabled) {
            return true
        }

        TransactionOptions txOptions = new TransactionOptions()
        txOptions.setBindToScope(true)

        ITransaction transaction
        TransactionContext context = request.getAttribute(SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE) as TransactionContext
        if (config.distributedTracingEnabled && context) {
            transaction = Sentry.startTransaction(context, txOptions)
        } else {
            transaction = Sentry.startTransaction(transactionName(request), 'http.server', txOptions)
        }

        request.setAttribute(TRANSACTION_ATTRIBUTE, transaction)
        Sentry.setTransaction(transaction.name)

        true
    }

    @Override
    void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
    }

    @Override
    void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        ITransaction transaction = request.getAttribute(TRANSACTION_ATTRIBUTE) as ITransaction
        if (!transaction || transaction.finished) {
            return
        }

        transaction.finish(statusFor(response.status))
    }

    private static String transactionName(HttpServletRequest request) {
        String controller = request.getAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE') as String ?: 'unknown'
        String action = request.getAttribute('org.grails.ACTION_NAME_ATTRIBUTE') as String ?: 'index'
        "${controller}/${action}"
    }

    private static SpanStatus statusFor(int status) {
        if (status < 400) {
            return SpanStatus.OK
        }
        SpanStatus.fromHttpStatusCode(status, SpanStatus.INTERNAL_ERROR)
    }
}
