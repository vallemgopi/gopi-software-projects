package lab;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import lab.domain.*;
import lab.dto.Requests;
import lab.repository.*;
import lab.service.*;
import lab.validation.ReviewValidator;
import org.junit.jupiter.api.Test;

class CaseServiceTest {
  @Test
  void rejectsOtherOwnersAndStaleReviews() {
    var repo = mock(CaseRepository.class);
    var corrections = mock(CorrectionRepository.class);
    var audits = mock(AuditRepository.class);
    var service =
        new CaseService(
            repo,
            corrections,
            audits,
            mock(MlClient.class),
            new ObjectMapper(),
            new ReviewValidator(),
            false);
    var c = new LabCase();
    c.id = UUID.randomUUID();
    c.owner = "owner";
    c.version = 2;
    when(repo.findById(c.id)).thenReturn(Optional.of(c));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.owned(c.id, "other"));
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () ->
            service.review(
                c.id,
                "owner",
                new Requests.Review(
                    "text",
                    Map.of(
                        "category",
                        "water",
                        "summary",
                        "text",
                        "location",
                        "",
                        "datetime",
                        "",
                        "identifier",
                        ""),
                    1)));
    verifyNoInteractions(corrections, audits);
  }

  @Test
  void validatesReviewSchema() {
    var validator = new ReviewValidator();
    assertThrows(
        IllegalArgumentException.class, () -> validator.validate(Map.of("unexpected", "value")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            validator.validate(
                Map.of(
                    "category",
                    "made-up",
                    "summary",
                    "",
                    "location",
                    "",
                    "datetime",
                    "",
                    "identifier",
                    "")));
  }
}
