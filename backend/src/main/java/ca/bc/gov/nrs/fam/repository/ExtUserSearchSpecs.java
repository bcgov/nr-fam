package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.constants.IdpType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchParams;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.jpa.domain.Specification;

/**
 * Query construction for the external user search.
 *
 * <p>Port of {@code ExtAppUserSearchService._apply_user_filters}. Filters are
 * ANDed; a role filter matches any of the supplied roles.
 *
 * <p>The query joins through role assignments to constrain by application, so a
 * user holding several roles would otherwise appear once per role -
 * {@code distinct} keeps one row per user.
 */
public final class ExtUserSearchSpecs {

  private ExtUserSearchSpecs() {}

  public static Specification<FamUser> forApplication(
      Long applicationId, ExtUserSearchParams params) {

    return (root, query, cb) -> {
      Join<FamUser, FamUserRoleXref> xref = root.join("userRoleXrefs");
      Join<FamUserRoleXref, FamRole> role = xref.join("role");

      if (query != null) {
        // One row per user, not per role assignment.
        query.distinct(true);
      }

      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(role.get("application").get("applicationId"), applicationId));

      if (params.getIdpType() != null) {
        predicates.add(idpTypePredicate(cb, root, params.getIdpType()));
      }
      if (hasText(params.getIdpUsername())) {
        predicates.add(contains(cb, root.get("userName"), params.getIdpUsername()));
      }
      if (hasText(params.getFirstName())) {
        predicates.add(contains(cb, root.get("firstName"), params.getFirstName()));
      }
      if (hasText(params.getLastName())) {
        predicates.add(contains(cb, root.get("lastName"), params.getLastName()));
      }
      if (params.getRole() != null && !params.getRole().isEmpty()) {
        // Prefix match, not contains: role codes are hierarchical, so
        // "FOM_SUBMITTER" is meant to also match "FOM_SUBMITTER_00001011".
        List<Predicate> roleMatches = params.getRole().stream()
            .filter(ExtUserSearchSpecs::hasText)
            .map(r -> cb.like(cb.lower(role.get("roleName")), r.toLowerCase(Locale.ROOT) + "%"))
            .map(Predicate.class::cast)
            .toList();
        if (!roleMatches.isEmpty()) {
          predicates.add(cb.or(roleMatches.toArray(new Predicate[0])));
        }
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  /**
   * BCSC is expressed as "neither IDIR nor BCeID" rather than by listing the
   * three BC Services Card codes, so an environment-specific code cannot be
   * missed.
   */
  private static Predicate idpTypePredicate(
      CriteriaBuilder cb, Root<FamUser> root, IdpType idpType) {

    return switch (idpType) {
      case IDIR -> cb.equal(root.get("userTypeCode"), UserType.IDIR.getCode());
      case BCEID -> cb.equal(root.get("userTypeCode"), UserType.BCEID.getCode());
      case BCSC -> cb.and(
          cb.notEqual(root.get("userTypeCode"), UserType.IDIR.getCode()),
          cb.notEqual(root.get("userTypeCode"), UserType.BCEID.getCode()));
    };
  }

  private static Predicate contains(
      CriteriaBuilder cb, jakarta.persistence.criteria.Path<String> path, String value) {
    return cb.like(cb.lower(path), "%" + value.toLowerCase(Locale.ROOT) + "%");
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
