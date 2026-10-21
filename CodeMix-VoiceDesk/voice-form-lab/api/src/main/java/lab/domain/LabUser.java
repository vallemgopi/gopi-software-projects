package lab.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "lab_user")
public class LabUser {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  public Long id;

  @Column(nullable = false, unique = true)
  public String username;

  @Column(nullable = false)
  public String passwordHash;
}
