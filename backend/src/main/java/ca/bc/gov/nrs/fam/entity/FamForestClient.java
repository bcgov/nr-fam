package ca.bc.gov.nrs.fam.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A business, individual or agency a user can hold a privilege "on behalf of".
 *
 * <p>FAM stores only the client number; the name and status are read live from
 * the Forest Client API. The {@code client_name} column was dropped in V16.
 *
 * <p>{@code forest_client_number} is an unbounded {@code VARCHAR} in the schema
 * (added by V7 without a length), so no length is declared here. Input is capped
 * at {@link ca.bc.gov.nrs.fam.constants.FamConstants#CLIENT_NUMBER_MAX_LEN} by
 * validation on the way in.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "fam_forest_client",
    uniqueConstraints = @UniqueConstraint(name = "fam_for_cli_num_uk",
        columnNames = "forest_client_number"))
public class FamForestClient extends AuditedEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "client_number_id")
  private Long clientNumberId;

  @Column(name = "forest_client_number", nullable = false)
  private String forestClientNumber;

  @Override
  public String toString() {
    return "FamForestClient(%d, %s)".formatted(clientNumberId, forestClientNumber);
  }
}
