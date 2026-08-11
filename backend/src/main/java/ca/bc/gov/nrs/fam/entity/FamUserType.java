package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Identity provider of a user: I (IDIR), B (Business BCeID), and the three BC
 * Services Card codes CD/CT/CP added by V27.
 *
 * <p>Mapped as an entity rather than a JPA enum so an unrecognised code read
 * from the database cannot fail the query. See
 * {@link ca.bc.gov.nrs.fam.constants.UserType} for the enum used in logic.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_user_type_code")
public class FamUserType extends CodeTableEntity {

  @Id
  @Column(name = "user_type_code", length = 2, nullable = false)
  private String userTypeCode;

  @Column(name = "description", length = 100)
  private String description;
}
