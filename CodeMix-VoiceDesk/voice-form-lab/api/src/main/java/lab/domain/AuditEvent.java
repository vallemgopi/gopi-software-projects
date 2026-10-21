package lab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
  @Id public UUID id;
  public UUID caseId;
  public String actor;
  public String action;
  public Instant createdAt;
}
