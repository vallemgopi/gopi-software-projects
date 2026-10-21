package lab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
public class Correction {
  @Id public UUID id;

  @Column(nullable = false)
  public UUID caseId;

  @Column(nullable = false)
  public String reviewer;

  @Column(nullable = false, columnDefinition = "text")
  public String transcript;

  @Column(nullable = false, columnDefinition = "text")
  public String fields;

  @Column(nullable = false)
  public Instant createdAt;
}
