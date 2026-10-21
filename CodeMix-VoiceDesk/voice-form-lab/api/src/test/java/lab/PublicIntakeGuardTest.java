package lab;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import lab.service.PublicIntakeGuard;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class PublicIntakeGuardTest {
  @Test
  void boundsConcurrencyAndRateWithoutKeepingClientData() {
    PublicIntakeGuard guard =
        new PublicIntakeGuard(Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    guard.enter();
    guard.enter();
    assertThrows(ResponseStatusException.class, guard::enter);
    guard.leave();
    guard.leave();
    for (int i = 2; i < 30; i++) {
      guard.enter();
      guard.leave();
    }
    assertThrows(ResponseStatusException.class, guard::enter);
  }
}
