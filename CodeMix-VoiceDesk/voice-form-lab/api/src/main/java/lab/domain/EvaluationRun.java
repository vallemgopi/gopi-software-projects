package lab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "evaluation_run")
public class EvaluationRun {
  @Id public UUID id;
  public String actor;

  @Column(columnDefinition = "text")
  public String report;

  public Instant createdAt;
}
