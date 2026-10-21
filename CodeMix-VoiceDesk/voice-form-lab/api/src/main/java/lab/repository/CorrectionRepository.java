package lab.repository;

import java.util.*;
import lab.domain.Correction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CorrectionRepository extends JpaRepository<Correction, UUID> {
  List<Correction> findByCaseIdOrderByCreatedAtAsc(UUID caseId);
}
