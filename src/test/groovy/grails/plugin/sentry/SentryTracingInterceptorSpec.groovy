package grails.plugin.sentry

import io.sentry.Breadcrumb
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class SentryTracingInterceptorSpec extends Specification {

    SentryOptions options

    def setup() {
        RecordingTransport.reset()
        Sentry.close()
        options = new SentryOptions()
        options.dsn = 'https://foo:bar@example.com/123'
        options.tracesSampleRate = 1.0d
        options.transportFactory = new RecordingTransportFactory()
        Sentry.init(options)
    }

    def cleanup() {
        Sentry.close()
        RecordingTransport.reset()
    }

    def "preHandle starts and binds a request transaction"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            request.setAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE', 'book')
            request.setAttribute('org.grails.ACTION_NAME_ATTRIBUTE', 'show')
            MockHttpServletResponse response = new MockHttpServletResponse()

        expect:
            interceptor.preHandle(request, response, null)

        and:
            request.getAttribute(SentryTracingInterceptor.TRANSACTION_ATTRIBUTE) instanceof ITransaction
            Sentry.getSpan() != null
    }

    def "afterCompletion finishes the transaction with HTTP status"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            request.setAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE', 'book')
            request.setAttribute('org.grails.ACTION_NAME_ATTRIBUTE', 'show')
            MockHttpServletResponse response = new MockHttpServletResponse()

        when:
            interceptor.preHandle(request, response, null)
            response.status = 500
            interceptor.afterCompletion(request, response, null, null)
            Sentry.flush(5000)

        then:
            RecordingTransport.transactions(options)*.transaction == ['book/show']
            RecordingTransport.transactions(options)*.status == [SpanStatus.INTERNAL_ERROR]
    }

    def "preHandle uses distributed transaction context when present"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true,
                    distributedTracingEnabled: true
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            request.setAttribute('org.grails.CONTROLLER_NAME_ATTRIBUTE', 'book')
            request.setAttribute('org.grails.ACTION_NAME_ATTRIBUTE', 'show')
            MockHttpServletResponse response = new MockHttpServletResponse()
            request.setAttribute(
                    SentryTracingFilter.TRANSACTION_CONTEXT_ATTRIBUTE,
                    new TransactionContext('upstream/request', 'http.server')
            )

        when:
            interceptor.preHandle(request, response, null)
            interceptor.afterCompletion(request, response, null, null)
            Sentry.flush(5000)

        then:
            RecordingTransport.transactions(options)*.transaction == ['upstream/request']
    }

    def "preHandle clears breadcrumbs accumulated before the request"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true
            ]))
            Sentry.addBreadcrumb(new Breadcrumb('startup log from before the request'))
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()

        when:
            interceptor.preHandle(request, response, null)

        then:
            Sentry.currentScopes.scope.breadcrumbs.empty
    }

    def "disabled tracing leaves request untouched"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: false
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest()
            MockHttpServletResponse response = new MockHttpServletResponse()

        expect:
            interceptor.preHandle(request, response, null)
            request.getAttribute(SentryTracingInterceptor.TRANSACTION_ATTRIBUTE) == null
    }

    def "OPTIONS request tracing can be disabled"() {
        given:
            SentryTracingInterceptor interceptor = new SentryTracingInterceptor(new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    tracingEnabled: true,
                    traceOptionsRequests: false
            ]))
            MockHttpServletRequest request = new MockHttpServletRequest('OPTIONS', '/books')
            MockHttpServletResponse response = new MockHttpServletResponse()

        expect:
            interceptor.preHandle(request, response, null)
            request.getAttribute(SentryTracingInterceptor.TRANSACTION_ATTRIBUTE) == null
            Sentry.getSpan() == null
    }
}
