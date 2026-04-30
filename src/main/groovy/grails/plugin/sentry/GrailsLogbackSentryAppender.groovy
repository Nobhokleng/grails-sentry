/*
 * Copyright 2016 Alan Rafael Fachini, authors, and contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package grails.plugin.sentry

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.status.ErrorStatus
import ch.qos.logback.core.status.Status
import groovy.transform.CompileStatic
import io.sentry.Sentry
import io.sentry.logback.SentryAppender

@CompileStatic
class GrailsLogbackSentryAppender extends SentryAppender {

    SentryConfig config

    GrailsLogbackSentryAppender(SentryConfig config) {
        super()
        this.config = config
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!config.active) {
            return
        }

        if ((config.levels ?: SentryConfig.defaultLevels).contains(event.level)) {
            // withScope creates an isolated thread-local scope for this event only,
            // preventing concurrent log events from clobbering each other's extras
            Sentry.withScope { scope ->
                event.loggerContextVO.propertyMap.each { String k, String v ->
                    scope.setExtra(k, v)
                }
                super.append(event)
            }
        }
    }

    @Override
    void addStatus(Status status) {
        if (status instanceof ErrorStatus) {
            // this error is otherwise completely swallowed
            status.throwable?.printStackTrace()
        }
        super.addStatus(status)
    }

}
