package dev.queryfence.persistence;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Single-instance demo recovery. Never retries an analytics query or recreates an approval. */
@Component
public class InterruptedWorkRecovery {
  private final ControlStore store;

  public InterruptedWorkRecovery(ControlStore store) {
    this.store = store;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void recover() {
    store.jdbc.update(
        "INSERT INTO queryfence.audit_events(actor,role,action,plan_id,outcome,approved,details)"
            + " SELECT"
            + " actor,role,'RECOVER',id,'INTERRUPTED',confirmation,'{\"applicationVersion\":\"2.0.0\"}'::jsonb"
            + " FROM queryfence.plans WHERE status='EXECUTING'");
    store.jdbc.update(
        "UPDATE queryfence.plans SET status='FAILED',approval_hash=NULL WHERE status='EXECUTING'");
    store.jdbc.update("UPDATE queryfence.evaluations SET status='FAILED' WHERE status='RUNNING'");
  }
}
