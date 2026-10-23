package dev.queryfence.planner;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class LimitedBodySubscriberTest {
  @Test
  void rejectsOversizedChunkedResponse() {
    var subscription = mock(Flow.Subscription.class);
    var body = new LimitedBodySubscriber(4);
    body.onSubscribe(subscription);
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));
    assertThrows(CompletionException.class, () -> body.getBody().toCompletableFuture().join());
    verify(subscription).cancel();
  }

  @Test
  void acceptsBoundedChunks() {
    var body = new LimitedBodySubscriber(4);
    body.onSubscribe(mock(Flow.Subscription.class));
    body.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
    body.onComplete();
    assertArrayEquals(new byte[] {1, 2, 3, 4}, body.getBody().toCompletableFuture().join());
  }
}
