package dev.queryfence.query;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.queryfence.plan.*;
import dev.queryfence.planner.*;
import jakarta.validation.Validation;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QueryServiceTest {
  final PlanValidator validator =
      new PlanValidator(Validation.buildDefaultValidatorFactory().getValidator());
  final QueryExecutor executor = mock(QueryExecutor.class);

  static class MutableClock extends Clock {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }
  }

  @Test
  void duplicateAndExpiryCannotExecute() {
    var clock = new MutableClock();
    var service =
        new QueryService(new DemoPlanner(), validator, new SqlCompiler(validator), executor, clock);
    when(executor.execute(any())).thenReturn(List.of(Map.of("label", "HOME", "value", 1)));
    var plan = service.plan("revenue by category");
    service.execute(plan.planId());
    assertThrows(IllegalArgumentException.class, () -> service.execute(plan.planId()));
    verify(executor, times(1)).execute(any());
    var expired = service.plan("order count by region");
    clock.now = clock.now.plusSeconds(601);
    assertThrows(IllegalArgumentException.class, () -> service.execute(expired.planId()));
    verifyNoMoreInteractions(executor);
  }

  @Test
  void failuresConsumePlanAndHistoryIsBounded() {
    var service =
        new QueryService(new DemoPlanner(), validator, new SqlCompiler(validator), executor);
    when(executor.execute(any())).thenThrow(new IllegalStateException());
    var p = service.plan("sales by month");
    assertThrows(IllegalStateException.class, () -> service.execute(p.planId()));
    assertThrows(IllegalArgumentException.class, () -> service.execute(p.planId()));
    for (int i = 0; i < 105; i++)
      assertThrows(IllegalArgumentException.class, () -> service.plan("profit"));
    assertEquals(100, service.history().size());
  }

  @Test
  void pendingPlansBounded() {
    var service =
        new QueryService(new DemoPlanner(), validator, new SqlCompiler(validator), executor);
    for (int i = 0; i < 200; i++) service.plan("revenue by category");
    assertThrows(IllegalArgumentException.class, () -> service.plan("revenue by category"));
  }

  @Test
  void concurrentConfirmationRunsOnlyOnce() throws Exception {
    var service =
        new QueryService(new DemoPlanner(), validator, new SqlCompiler(validator), executor);
    when(executor.execute(any())).thenReturn(List.of());
    var p = service.plan("revenue by category");
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(8)) {
      var tasks = new java.util.ArrayList<java.util.concurrent.Callable<Boolean>>();
      for (int i = 0; i < 8; i++)
        tasks.add(
            () -> {
              try {
                service.execute(p.planId());
                return true;
              } catch (IllegalArgumentException e) {
                return false;
              }
            });
      int successes = 0;
      for (var future : pool.invokeAll(tasks)) if (future.get()) successes++;
      assertEquals(1, successes);
      verify(executor, times(1)).execute(any());
    }
  }
}
