package lab.repository;

import java.util.*;
import lab.domain.LabUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<LabUser, Long> {
  Optional<LabUser> findByUsername(String username);
}
