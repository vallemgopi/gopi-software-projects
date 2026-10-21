package lab.service;

import java.time.Clock;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Instance-wide backstop, in addition to nginx's client-IP rate limit. Stores no IPs. */
@Component
public class PublicIntakeGuard {
  private final Semaphore active = new Semaphore(2);
  private final Clock clock;
  private long minute = -1;
  private int requests;

  public PublicIntakeGuard() {
    this(Clock.systemUTC());
  }

  public PublicIntakeGuard(Clock clock) {
    this.clock = clock;
  }

  public synchronized void enter() {
    long now = clock.millis() / 60000;
    if (now != minute) {
      minute = now;
      requests = 0;
    }
    if (requests >= 30 || !active.tryAcquire())
      throw new ResponseStatusException(
          HttpStatus.TOO_MANY_REQUESTS, "Intake is busy. Please try again in a minute.");
    requests++;
  }

  public void leave() {
    active.release();
  }
}
