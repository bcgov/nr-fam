package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamUserTermsConditions;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamUserTermsConditionsRepository
    extends JpaRepository<FamUserTermsConditions, Long> {

  Optional<FamUserTermsConditions> findByUserUserIdAndVersion(Long userId, String version);

  boolean existsByUserUserIdAndVersion(Long userId, String version);
}
