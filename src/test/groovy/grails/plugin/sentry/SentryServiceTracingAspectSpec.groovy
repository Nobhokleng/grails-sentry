package grails.plugin.sentry

import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SpanStatus
import io.sentry.TransactionOptions
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.Signature
import spock.lang.Specification

class SentryServiceTracingAspectSpec extends Specification {

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

    def "creates child span around service method when transaction is active"() {
        given:
            TransactionOptions txOptions = new TransactionOptions(bindToScope: true)
            ITransaction transaction = Sentry.startTransaction('book/show', 'http.server', txOptions)
            SentryServiceTracingAspect aspect = new SentryServiceTracingAspect()
            ProceedingJoinPoint joinPoint = joinPointFor(new BookService(), 'loadBook', 'ok')

        when:
            def result = aspect.traceServiceMethod(joinPoint)
            transaction.finish()
            Sentry.flush(5000)

        then:
            result == 'ok'
            RecordingTransport.transactions(options).first().spans*.op == ['grails.service']
            RecordingTransport.transactions(options).first().spans*.description == ['BookService.loadBook']
    }

    def "does not create span when no transaction is active"() {
        given:
            SentryServiceTracingAspect aspect = new SentryServiceTracingAspect()
            ProceedingJoinPoint joinPoint = joinPointFor(new BookService(), 'loadBook', 'ok')

        expect:
            aspect.traceServiceMethod(joinPoint) == 'ok'
            RecordingTransport.transactions(options).empty
    }

    def "marks child span internal error and rethrows"() {
        given:
            TransactionOptions txOptions = new TransactionOptions(bindToScope: true)
            ITransaction transaction = Sentry.startTransaction('book/show', 'http.server', txOptions)
            RuntimeException failure = new RuntimeException('boom')
            SentryServiceTracingAspect aspect = new SentryServiceTracingAspect()
            ProceedingJoinPoint joinPoint = failingJoinPointFor(new BookService(), 'loadBook', failure)

        when:
            aspect.traceServiceMethod(joinPoint)

        then:
            RuntimeException ex = thrown()
            ex.is(failure)

        when:
            transaction.finish()
            Sentry.flush(5000)

        then:
            RecordingTransport.transactions(options).first().spans.first().status == SpanStatus.INTERNAL_ERROR
    }

    private ProceedingJoinPoint joinPointFor(Object target, String methodName, Object result) {
        Mock(ProceedingJoinPoint) {
            getTarget() >> target
            getSignature() >> signature(methodName)
            proceed() >> result
        }
    }

    private ProceedingJoinPoint failingJoinPointFor(Object target, String methodName, Throwable failure) {
        Mock(ProceedingJoinPoint) {
            getTarget() >> target
            getSignature() >> signature(methodName)
            proceed() >> { throw failure }
        }
    }

    private Signature signature(String methodName) {
        Mock(Signature) {
            getName() >> methodName
        }
    }

    static class BookService {
    }
}
