package lab.phone;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "phone_intake")
public class PhoneIntake {
  @Id public UUID id;

  @Column(nullable = false, length = 34, unique = true)
  public String callSid;

  @Column(nullable = false, length = 12, unique = true)
  public String reference;

  @Column(nullable = false)
  public String owner;

  @Column(length = 10)
  public String language;

  @Column(nullable = false, length = 30)
  public String state;

  public Instant consentedAt;

  @Column(length = 34, unique = true)
  public String recordingSid;

  public UUID caseId;
  public int attempts;
  public int menuAttempts;
  public Instant nextAttemptAt;
  public Instant leaseUntil;

  @Column(length = 50)
  public String errorCode;

  public boolean mediaDeleted;
  public Instant createdAt;
  public Instant updatedAt;
  @Version public long version;
}
