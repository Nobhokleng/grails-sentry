package grails.plugin.sentry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import groovy.transform.CompileStatic
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel

@CompileStatic
class SentryBreadcrumbAppender extends AppenderBase<ILoggingEvent> {

    SentryConfig config

    SentryBreadcrumbAppender(SentryConfig config) {
        this.config = config
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!config?.active || !config.breadcrumbsEnabled) {
            return
        }

        if (!event.level.isGreaterOrEqual(Level.toLevel(config.breadcrumbLevel, Level.INFO))) {
            return
        }

        // Skip events that will be captured as full Sentry errors by GrailsLogbackSentryAppender;
        // those events need not also appear as breadcrumbs in themselves
        Set<Level> sentryLevels = config.levels ?: SentryConfig.defaultLevels
        if (sentryLevels.contains(event.level)) {
            return
        }

        Breadcrumb breadcrumb = new Breadcrumb()
        breadcrumb.setCategory(event.loggerName)
        breadcrumb.setMessage(event.formattedMessage)
        breadcrumb.setLevel(toSentryLevel(event.level))
        breadcrumb.setType('default')

        Sentry.addBreadcrumb(breadcrumb)
    }

    static SentryLevel toSentryLevel(Level level) {
        if (level == Level.ERROR) {
            return SentryLevel.ERROR
        }
        if (level == Level.WARN) {
            return SentryLevel.WARNING
        }
        if (level == Level.DEBUG || level == Level.TRACE) {
            return SentryLevel.DEBUG
        }
        SentryLevel.INFO
    }
}
