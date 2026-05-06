package grails.plugin.sentry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.SentryLevel
import org.slf4j.LoggerFactory
import spock.lang.Specification

class SentryBreadcrumbAppenderSpec extends Specification {

    SentryOptions options
    Logger logger
    SentryBreadcrumbAppender breadcrumbAppender
    GrailsLogbackSentryAppender sentryAppender

    def setup() {
        RecordingTransport.reset()
        Sentry.close()
        options = new SentryOptions()
        options.dsn = 'https://foo:bar@example.com/123'
        options.transportFactory = new RecordingTransportFactory()
        Sentry.init(options)

        LoggerContext context = LoggerFactory.getILoggerFactory() as LoggerContext
        logger = context.getLogger('breadcrumb-test')
        logger.detachAndStopAllAppenders()
        logger.level = Level.INFO  // must be set so Logback delivers INFO events to appenders
        logger.additive = false

        SentryConfig config = new SentryConfig([
                dsn: 'https://foo:bar@example.com/123',
                breadcrumbsEnabled: true,
                breadcrumbLevel: 'INFO',
                levels: ['ERROR']
        ])
        breadcrumbAppender = new SentryBreadcrumbAppender(config)
        breadcrumbAppender.context = context
        breadcrumbAppender.start()
        logger.addAppender(breadcrumbAppender)

        sentryAppender = new GrailsLogbackSentryAppender(config)
        sentryAppender.context = context
        sentryAppender.start()
        logger.addAppender(sentryAppender)
    }

    def cleanup() {
        logger?.detachAndStopAllAppenders()
        Sentry.close()
        RecordingTransport.reset()
    }

    def "qualifying log event becomes breadcrumb on later error event"() {
        when:
            logger.info('Loading {}', 'book')
            logger.error('Failed', new RuntimeException('boom'))
            Sentry.flush(5000)

        then:
            def event = RecordingTransport.events(options).first()
            event.breadcrumbs*.message == ['Loading book']
            event.breadcrumbs*.category == ['breadcrumb-test']
            event.breadcrumbs*.level == [SentryLevel.INFO]
    }

    def "events below breadcrumb level are ignored"() {
        given:
            breadcrumbAppender.config = new SentryConfig([
                    dsn: 'https://foo:bar@example.com/123',
                    breadcrumbsEnabled: true,
                    breadcrumbLevel: 'WARN',
                    levels: ['ERROR']
            ])

        when:
            logger.info('Ignored breadcrumb')
            logger.warn('Recorded breadcrumb')
            logger.error('Failed', new RuntimeException('boom'))
            Sentry.flush(5000)

        then:
            RecordingTransport.events(options).first().breadcrumbs*.message == ['Recorded breadcrumb']
    }

    def "logback level maps to sentry breadcrumb level"() {
        expect:
            SentryBreadcrumbAppender.toSentryLevel(Level.DEBUG) == SentryLevel.DEBUG
            SentryBreadcrumbAppender.toSentryLevel(Level.INFO) == SentryLevel.INFO
            SentryBreadcrumbAppender.toSentryLevel(Level.WARN) == SentryLevel.WARNING
            SentryBreadcrumbAppender.toSentryLevel(Level.ERROR) == SentryLevel.ERROR
    }
}
