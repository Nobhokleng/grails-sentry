package grails.plugin.sentry

import io.sentry.Hint
import io.sentry.ITransportFactory
import io.sentry.JsonSerializer
import io.sentry.RequestDetails
import io.sentry.SentryEnvelope
import io.sentry.SentryEnvelopeItem
import io.sentry.SentryEvent
import io.sentry.SentryItemType
import io.sentry.SentryOptions
import io.sentry.protocol.SentryTransaction
import io.sentry.transport.ITransport
import io.sentry.transport.RateLimiter

class RecordingTransport implements ITransport {

    static final List<SentryEnvelope> envelopes = Collections.synchronizedList([])

    static void reset() {
        envelopes.clear()
    }

    static List<SentryEnvelopeItem> items(SentryItemType type) {
        envelopes.collectMany { SentryEnvelope envelope ->
            envelope.items.findAll { SentryEnvelopeItem item ->
                item.header.type == type
            }
        }
    }

    static List<SentryEvent> events(SentryOptions options) {
        JsonSerializer serializer = new JsonSerializer(options)
        items(SentryItemType.Event).collect { SentryEnvelopeItem item ->
            item.getEvent(serializer)
        }
    }

    static List<SentryTransaction> transactions(SentryOptions options) {
        JsonSerializer serializer = new JsonSerializer(options)
        items(SentryItemType.Transaction).collect { SentryEnvelopeItem item ->
            item.getTransaction(serializer)
        }
    }

    @Override
    void send(SentryEnvelope envelope, Hint hint) {
        envelopes << envelope
    }

    @Override
    boolean isHealthy() {
        true
    }

    @Override
    void flush(long timeoutMillis) {
    }

    @Override
    RateLimiter getRateLimiter() {
        null
    }

    @Override
    void close() {
    }

    @Override
    void close(boolean isRestarting) {
    }
}

class RecordingTransportFactory implements ITransportFactory {

    @Override
    ITransport create(SentryOptions options, RequestDetails requestDetails) {
        new RecordingTransport()
    }
}
