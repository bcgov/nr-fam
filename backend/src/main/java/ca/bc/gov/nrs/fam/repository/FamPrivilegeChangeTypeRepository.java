package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.entity.FamPrivilegeChangeType;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The privilege change code table.
 *
 * <p>Exists for the smoke test rather than for the audit write path, which
 * reaches the row through {@code EntityManager.getReference} and never needs to
 * load it. These rows are seeded by the baseline, so a non-zero count is
 * evidence that migrations ran.
 */
public interface FamPrivilegeChangeTypeRepository
    extends JpaRepository<FamPrivilegeChangeType, String> {}
