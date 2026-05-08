package grails.plugin.sentry

import ch.qos.logback.classic.Logger
import com.stehno.ersatz.ErsatzServer
import grails.core.GrailsApplication
import grails.testing.mixin.integration.Integration
import io.sentry.Sentry
import io.sentry.SentryOptions
import org.slf4j.LoggerFactory
import spock.lang.Specification

@Integration
class SanityIntegrationSpec extends Specification {

    GrailsLogbackSentryAppender sentryAppender
    GrailsApplication grailsApplication

    def "Sentry features are disabled by default"() {
        given:
            def ctx = grailsApplication.mainContext

        when:
            SentryConfig config = new SentryConfig(grailsApplication.config.grails.plugin.sentry)

        then:
            !config.tracingEnabled
            !config.breadcrumbsEnabled
            !config.distributedTracingEnabled
            !ctx.containsBean('sentryTracingInterceptor')
            !ctx.containsBean('sentryMvcConfigurer')
            !ctx.containsBean('sentryServiceTracingAspect')
            !ctx.containsBean('sentryBreadcrumbAppender')
            !ctx.containsBean('sentryTracingFilter')
    }

    def "everything works"() {
        expect: "sentry appender bean is registered"
            sentryAppender != null

        when: "mock envelope server is started"
            ErsatzServer server = new ErsatzServer()
            server.expectations {
                post("/api/123/envelope/") {
                    called 1
                    responder {
                        code 200
                    }
                }
            }
            server.start()

        and: "Sentry is re-initialized to point at the mock server"
            Sentry.close()
            Sentry.init { SentryOptions options ->
                options.dsn = "http://foo:bar@localhost:${server.httpPort}/123"
            }
            LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME).error("Test Me", new Exception("Failure!"))
            Sentry.flush(5000)

        then: "event envelope is sent to the mock server"
            server.verify()

        cleanup:
            server?.stop()
    }

}
