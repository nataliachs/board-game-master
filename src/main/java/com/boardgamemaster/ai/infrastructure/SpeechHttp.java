package com.boardgamemaster.ai.infrastructure;

import com.boardgamemaster.ai.SpeechException;
import java.io.IOException;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Response bodies are bounded while streaming; errors never include provider bodies. */
final class SpeechHttp {
    private SpeechHttp() { }
    static HttpResponse<byte[]> send(HttpClient http, HttpRequest request, int limit, String kind) {
        try {
            var response = http.send(request, info -> new BoundedBody(limit));
            if (response.statusCode() == 429) { throw error(kind, "RATE_LIMITED", "Speech provider rate limit reached; try text or retry later"); }
            if (response.statusCode() < 200 || response.statusCode() >= 300) { throw error(kind, "PROVIDER_UNAVAILABLE", "Speech provider could not complete the request; text remains available"); }
            return response;
        } catch (HttpTimeoutException e) { throw error(kind, "TIMEOUT", "Speech request timed out; try text or retry later");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw error(kind, "PROVIDER_UNAVAILABLE", "Speech request was interrupted");
        } catch (IOException e) { throw error(kind, "PROVIDER_UNAVAILABLE", "Speech provider could not be reached or returned an oversized response"); }
    }
    static SpeechException error(String kind, String suffix, String message) { return new SpeechException(SpeechException.Code.valueOf(kind + "_" + suffix), message); }
    private static class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate = HttpResponse.BodySubscribers.ofByteArray();
        private final int limit;
        private Flow.Subscription subscription;
        private long size;
        BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
        public void onSubscribe(Flow.Subscription value) { subscription = value; delegate.onSubscribe(value); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) { size += buffer.remaining(); }
            if (size > limit) { subscription.cancel(); delegate.onError(new IOException("Speech response exceeds limit")); }
            else { delegate.onNext(buffers); }
        }
        public void onError(Throwable error) { delegate.onError(error); }
        public void onComplete() { delegate.onComplete(); }
    }
}
