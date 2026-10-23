package dev.queryfence.planner;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;

/** Bounds provider response memory before deserialization, including chunked responses. */
final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
  private final int maxBytes;
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private final CompletableFuture<byte[]> body = new CompletableFuture<>();
  private Flow.Subscription subscription;

  LimitedBodySubscriber(int maxBytes) {
    this.maxBytes = maxBytes;
  }

  public CompletionStage<byte[]> getBody() {
    return body;
  }

  public void onSubscribe(Flow.Subscription subscription) {
    this.subscription = subscription;
    subscription.request(1);
  }

  public void onNext(List<ByteBuffer> buffers) {
    for (ByteBuffer buffer : buffers) {
      if (buffer.remaining() > maxBytes - bytes.size()) {
        subscription.cancel();
        body.completeExceptionally(new IllegalArgumentException("Provider response too large"));
        return;
      }
      byte[] chunk = new byte[buffer.remaining()];
      buffer.get(chunk);
      bytes.writeBytes(chunk);
    }
    subscription.request(1);
  }

  public void onError(Throwable error) {
    body.completeExceptionally(error);
  }

  public void onComplete() {
    body.complete(bytes.toByteArray());
  }
}
