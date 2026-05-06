package grails.plugin.sentry

import groovy.transform.CompileStatic
import io.sentry.Sentry
import io.sentry.TransactionContext

import javax.servlet.Filter
import javax.servlet.FilterChain
import javax.servlet.FilterConfig
import javax.servlet.ServletRequest
import javax.servlet.ServletResponse
import javax.servlet.http.HttpServletRequest

@CompileStatic
class SentryTracingFilter implements Filter {

    static final String TRANSACTION_CONTEXT_ATTRIBUTE = 'sentry.transactionContext'

    @Override
    void init(FilterConfig filterConfig) {}

    @Override
    void destroy() {}

    @Override
    void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) {
        HttpServletRequest httpRequest = request as HttpServletRequest
        String sentryTrace = httpRequest.getHeader('sentry-trace')

        if (sentryTrace) {
            List<String> baggage = Collections.list(httpRequest.getHeaders('baggage')) as List<String>
            TransactionContext context = Sentry.continueTrace(sentryTrace, baggage)
            request.setAttribute(TRANSACTION_CONTEXT_ATTRIBUTE, context)
        }

        chain.doFilter(request, response)
    }
}
