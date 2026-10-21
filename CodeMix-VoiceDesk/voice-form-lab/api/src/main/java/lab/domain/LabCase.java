package lab.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "lab_case")
public class LabCase {
  @Id public UUID id;

  @Column(nullable = false)
  public String owner;

  @Column(nullable = false, columnDefinition = "text")
  public String transcript;

  @Column(nullable = false, columnDefinition = "text")
  public String prediction;

  @Column(nullable = false)
  public String status;

  @Column(nullable = false, length = 20)
  public String source = "STAFF";

  public Instant consentedAt;

  @Column(length = 20)
  public String submittedLanguage;

  @Column(unique = true)
  public UUID phoneIntakeId;

  @Column(length = 12)
  public String phoneReference;

  @Column(nullable = false)
  public Instant createdAt;

  @Column(nullable = false)
  public Instant updatedAt;

  @Version public long version;
  public byte[] audio;
}
