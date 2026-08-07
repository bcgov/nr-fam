package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamForestClient;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamForestClientRepository extends JpaRepository<FamForestClient, Long> {

  Optional<FamForestClient> findByForestClientNumber(String forestClientNumber);
}
