package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamUserType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FamUserTypeRepository extends JpaRepository<FamUserType, String> {
}
