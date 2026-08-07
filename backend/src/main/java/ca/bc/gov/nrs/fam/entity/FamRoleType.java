package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A (abstract) or C (concrete). Users may only be assigned to concrete roles;
 * an abstract role is a parent that concrete, client-scoped roles hang off.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_role_type")
public class FamRoleType extends CodeTableEntity {

  @Id
  @Column(name = "role_type_code", length = 2, nullable = false)
  private String roleTypeCode;

  @Column(name = "description", length = 100)
  private String description;
}
