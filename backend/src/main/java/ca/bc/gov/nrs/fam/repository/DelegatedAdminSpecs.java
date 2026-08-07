package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.constants.SortOrder;
import ca.bc.gov.nrs.fam.constants.UserRoleSortBy;
import ca.bc.gov.nrs.fam.dto.UserRolePageParams;
import ca.bc.gov.nrs.fam.entity.FamAccessControlPrivilege;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.jpa.domain.Specification;

/**
 * Query construction for the paged delegated-admin listing.
 *
 * <p>Mirrors {@link UserRoleAssignmentSpecs}, but over
 * {@code fam_access_control_privilege} rather than {@code fam_user_role_xref}.
 * The sortable columns are the same set, which is why
 * {@link UserRoleSortBy} is reused rather than duplicated - upstream's
 * {@code DelegatedAdminSortByEnum} listed exactly the same values.
 *
 * <p>No privilege scoping here: only an application admin reaches this endpoint,
 * and they see every delegated admin of their application.
 */
public final class DelegatedAdminSpecs {

  private static final String TIMESTAMP_SEARCH_FORMAT = "YYYY-MM-DD HH24:MI:SS";

  private DelegatedAdminSpecs() {}

  public static Specification<FamAccessControlPrivilege> forApplication(
      Long applicationId, UserRolePageParams pageParams) {

    return (root, query, cb) -> {
      Join<FamAccessControlPrivilege, FamUser> user = root.join("user");
      Join<FamAccessControlPrivilege, FamRole> role = root.join("role");
      role.fetch("forestClient", JoinType.LEFT);

      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(role.get("application").get("applicationId"), applicationId));

      String search = pageParams.getSearch();
      if (search != null && !search.isBlank()) {
        predicates.add(searchPredicate(cb, root, user, role, search));
      }

      // Skipped for the count query Spring Data issues alongside the page.
      if (query != null && !Long.class.equals(query.getResultType())) {
        Expression<?> sortExpression = sortExpression(cb, root, user, role, pageParams.getSortBy());
        query.orderBy(pageParams.getSortOrder() == SortOrder.ASC
            ? cb.asc(sortExpression)
            : cb.desc(sortExpression));
      }

      return cb.and(predicates.toArray(new Predicate[0]));
    };
  }

  private static Predicate searchPredicate(
      CriteriaBuilder cb,
      Root<FamAccessControlPrivilege> root,
      Join<FamAccessControlPrivilege, FamUser> user,
      Join<FamAccessControlPrivilege, FamRole> role,
      String search) {

    String pattern = "%" + search.toLowerCase() + "%";
    List<Predicate> matches = new ArrayList<>();

    matches.add(cb.like(cb.lower(user.get("userName")), pattern));
    matches.add(cb.like(cb.lower(user.get("userTypeCode")), pattern));
    matches.add(cb.like(cb.lower(user.get("email")), pattern));
    matches.add(cb.like(cb.lower(fullName(cb, user)), pattern));
    matches.add(cb.like(cb.lower(role.get("displayName")), pattern));
    matches.add(cb.like(
        cb.lower(role.join("forestClient", JoinType.LEFT).get("forestClientNumber")), pattern));
    matches.add(cb.like(
        cb.lower(cb.function("to_char", String.class,
            root.get("createDate"), cb.literal(TIMESTAMP_SEARCH_FORMAT))),
        pattern));

    return cb.or(matches.toArray(new Predicate[0]));
  }

  private static Expression<?> sortExpression(
      CriteriaBuilder cb,
      Root<FamAccessControlPrivilege> root,
      Join<FamAccessControlPrivilege, FamUser> user,
      Join<FamAccessControlPrivilege, FamRole> role,
      UserRoleSortBy sortBy) {

    UserRoleSortBy effective = sortBy == null ? UserRoleSortBy.defaultSort() : sortBy;
    return switch (effective) {
      case CREATE_DATE -> root.get("createDate");
      case USER_NAME -> user.get("userName");
      case DOMAIN -> user.get("userTypeCode");
      case EMAIL -> user.get("email");
      case FULL_NAME -> fullName(cb, user);
      case ROLE_DISPLAY_NAME -> role.get("displayName");
      case FOREST_CLIENT_NUMBER ->
          role.join("forestClient", JoinType.LEFT).get("forestClientNumber");
    };
  }

  /** Plain concatenation, matching the end-user listing: a null first name sorts last. */
  private static Expression<String> fullName(
      CriteriaBuilder cb, Join<FamAccessControlPrivilege, FamUser> user) {
    return cb.concat(cb.concat(user.get("firstName"), " "), user.get("lastName"));
  }
}
