package grails.plugin.sentry

import ch.qos.logback.classic.Logger
import com.stehno.ersatz.ErsatzServer
import grails.testing.mixin.integration.Integration
import io.sentry.Sentry
import io.sentry.SentryOptions
import org.slf4j.LoggerFactory
import spock.lang.Specification

@Integration
class SanityIntegrationSpec extends Specification {

    GrailsLogbackSentryAppender sentryAppender

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
