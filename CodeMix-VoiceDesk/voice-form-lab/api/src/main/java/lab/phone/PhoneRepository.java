package lab.phone;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PhoneRepository extends JpaRepository<PhoneIntake, UUID> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PhoneIntake> findByCallSid(String callSid);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from PhoneIntake p where p.id=:id")
  Optional<PhoneIntake> locked(@Param("id") UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PhoneIntake> findFirstByStateAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
      String state, Instant now);

  List<PhoneIntake> findTop100ByOwnerOrderByCreatedAtDesc(String owner);

  List<PhoneIntake> findTop10ByStateAndMediaDeletedFalseAndRecordingSidIsNotNullOrderByUpdatedAtAsc(
      String state);

  List<PhoneIntake> findByStateAndLeaseUntilBefore(String state, Instant now);

  List<PhoneIntake> findByStateInAndCreatedAtBefore(List<String> states, Instant before);
}
