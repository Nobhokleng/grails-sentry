package grails.plugin.sentry

import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.TransactionContext
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class SentryTracingFilterSpec extends Specification {

    def setup() {
        Sentry.close()
        Sentry.init { SentryOptions options ->
            options.dsn = 'https://foo:bar@example.com/123'
            options.tracesSampleRate = 1.0
        }
    }

    def cleanup() {
        Sentry.close()
    }

    def "stores transaction context from inbound trace headers"() {
        given:
            SentryTracingFilter filter = new SentryTracingFilter()
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()
            MockFilterChain chain = new MockFilterChain()
            request.addHeader('sentry-trace', '771a43a4192642f0b136d5159a501700-6e8f22c393e68f19-1')
            request.addHeader('baggage', 'sentry-trace_id=771a43a4192642f0b136d5159a501700,sentry-public_key=public')

        when:
            filter.doFilter(request, response, chain)

        then:
            TransactionContext context = request.getAttribute(SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE) as TransactionContext
            context != null
            context.traceId.toString() == '771a43a4192642f0b136d5159a501700'
            context.parentSpanId.toString() == '6e8f22c393e68f19'
    }

    def "continues filter chain when headers are absent"() {
        given:
            SentryTracingFilter filter = new SentryTracingFilter()
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()
            MockFilterChain chain = new MockFilterChain()

        when:
            filter.doFilter(request, response, chain)

        then:
            request.getAttribute(SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE) == null
            chain.request.is(request)
            chain.response.is(response)
    }
}
