package lab.repository;

import java.util.*;
import lab.domain.LabCase;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CaseRepository extends JpaRepository<LabCase, UUID> {
  List<LabCase> findByOwnerOrderByCreatedAtDesc(String owner);
}
