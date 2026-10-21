package lab.repository;

import java.util.*;
import lab.domain.EvaluationRun;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvaluationRepository extends JpaRepository<EvaluationRun, UUID> {
  Optional<EvaluationRun> findFirstByActorOrderByCreatedAtDesc(String actor);
}
