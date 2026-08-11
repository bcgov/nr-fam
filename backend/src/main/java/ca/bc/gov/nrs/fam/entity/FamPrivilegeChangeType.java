package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * GRANT, REVOKE or UPDATE - the kind of change recorded in
 * {@link FamPrivilegeChangeAudit}.
 *
 * <p>The SQLAlchemy model declared these timestamps as naive {@code TIMESTAMP},
 * but a later migration converted the table to {@code timestamptz}; the
 * inherited {@link CodeTableEntity} mapping follows the schema.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_privilege_change_type")
public class FamPrivilegeChangeType extends CodeTableEntity {

  @Id
  @Column(name = "privilege_change_type_code", length = 10, nullable = false)
  private String privilegeChangeTypeCode;

  @Column(name = "description", length = 100, nullable = false)
  private String description;
}
