package grails.plugin.sentry

import io.sentry.ISpan
import io.sentry.Sentry
import io.sentry.SpanStatus
import org.aspectj.lang.ProceedingJoinPoint
import org.aspectj.lang.annotation.Around
import org.aspectj.lang.annotation.Aspect

@Aspect
class SentryServiceTracingAspect {

    @Around('execution(public * *(..)) && (@within(grails.transaction.Transactional) || @within(org.springframework.transaction.annotation.Transactional) || within(grails.app.services..*))')
    Object traceServiceMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        ISpan activeSpan = Sentry.getSpan()
        if (!activeSpan) {
            return joinPoint.proceed()
        }

        ISpan span = activeSpan.startChild('grails.service', spanName(joinPoint))
        try {
            return joinPoint.proceed()
        } catch (Throwable throwable) {
            span.setThrowable(throwable)
            span.setStatus(SpanStatus.INTERNAL_ERROR)
            throw throwable
        } finally {
            span.finish()
        }
    }

    private static String spanName(ProceedingJoinPoint joinPoint) {
        "${joinPoint.target?.class?.simpleName ?: 'UnknownService'}.${joinPoint.signature.name}"
    }
}
