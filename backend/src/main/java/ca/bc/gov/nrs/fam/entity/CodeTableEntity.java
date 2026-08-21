package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared shape of the FAM code tables. Only {@code fam_privilege_change_type}
 * remains, but the superclass is kept: it is what states that a code table
 * carries the same four audit columns as every other table.
 *
 * <p>Extends {@link AuditedEntity}: code tables carry the same four audit
 * columns as every other table, so "who put this row here" has one answer
 * regardless of which table is being read. Rows are seeded by migrations and so
 * are stamped {@code system}.
 *
 * <p>{@code expiry_date} marks a code as retired - it is never used to filter
 * reads today, but it is part of the schema.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class CodeTableEntity extends AuditedEntity {

  @Column(name = "effective_date", nullable = false)
  private LocalDateTime effectiveDate;

  @Column(name = "expiry_date")
  private LocalDateTime expiryDate;
}
