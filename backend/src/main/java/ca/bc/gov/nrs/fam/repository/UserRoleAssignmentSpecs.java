package ca.bc.gov.nrs.fam.repository;

import ca.bc.gov.nrs.fam.constants.SortOrder;
import ca.bc.gov.nrs.fam.constants.UserRoleSortBy;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.UserRolePageParams;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.security.Requester;
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
 * Query construction for the application's user-role listing.
 *
 * <p>Port of {@code crud_application.__query_user_roles_by_app_privilege} and
 * {@code __build_filter_criteria}. Three concerns are layered:
 *
 * <ol>
 *   <li>the application filter, which always applies;
 *   <li>privilege scoping - an application admin sees everything, a delegated
 *       admin sees only the roles they manage, and a BCeID delegated admin is
 *       further restricted to their own organisation;
 *   <li>the free-text search, which ORs a case-insensitive LIKE across every
 *       sortable column.
 * </ol>
 */
public final class UserRoleAssignmentSpecs {

  /** Matches {@code TIMESTAMP_FORMAT_DEFAULT} in {@code datetime_format.py}. */
  private static final String TIMESTAMP_SEARCH_FORMAT = "YYYY-MM-DD HH24:MI:SS";

  private UserRoleAssignmentSpecs() {}

  /**
   * @param managedRoleIds roles the requester may manage, when they are acting as
   *     a delegated admin rather than an application admin. Ignored when
   *     {@code isAppAdmin} is true.
   */
  public static Specification<FamUserRoleXref> forApplication(
      Long applicationId,
      Requester requester,
      boolean isAppAdmin,
      List<Long> managedRoleIds,
      UserRolePageParams pageParams) {

    return (root, query, cb) -> {
      Join<FamUserRoleXref, FamUser> user = root.join("user");
      Join<FamUserRoleXref, FamRole> role = root.join("role");
      role.fetch("forestClient", JoinType.LEFT);

      List<Predicate> predicates = new ArrayList<>();
      predicates.add(cb.equal(role.get("application").get("applicationId"), applicationId));

      if (!isAppAdmin) {
        // A delegated admin sees only assignments for roles they manage. An empty
        // list must match nothing, not everything - cb.in with no values would
        // produce invalid SQL, so short-circuit to a false predicate.
        if (managedRoleIds.isEmpty()) {
          predicates.add(cb.disjunction());
        } else {
          predicates.add(role.get("roleId").in(managedRoleIds));
        }

        if (requester.isBceid()) {
          // A BCeID delegated admin is confined to their own organisation.
          predicates.add(cb.equal(user.get("userTypeCode"), UserType.BCEID.getCode()));
          predicates.add(cb.equal(
              cb.upper(user.get("businessGuid")),
              requester.businessGuid() == null
                  ? null
                  : requester.businessGuid().toUpperCase()));
        }
      }

      String search = pageParams.getSearch();
      if (search != null && !search.isBlank()) {
        predicates.add(searchPredicate(cb, root, user, role, search));
      }

      // Sorting lives here rather than in the Pageable because FULL_NAME sorts on
      // an expression, not a column. Skip it for the count query Spring Data
      // issues alongside the page - ordering there is pointless and some
      // databases reject it.
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
      Root<FamUserRoleXref> root,
      Join<FamUserRoleXref, FamUser> user,
      Join<FamUserRoleXref, FamRole> role,
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

    // Dates are searched on their rendered form, so "2024-03" matches a month.
    matches.add(cb.like(
        cb.lower(cb.function("to_char", String.class,
            root.get("createDate"), cb.literal(TIMESTAMP_SEARCH_FORMAT))),
        pattern));

    return cb.or(matches.toArray(new Predicate[0]));
  }

  private static Expression<?> sortExpression(
      CriteriaBuilder cb,
      Root<FamUserRoleXref> root,
      Join<FamUserRoleXref, FamUser> user,
      Join<FamUserRoleXref, FamRole> role,
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

  /**
   * First and last name joined for sorting and searching.
   *
   * <p>Matches the SQL that SQLAlchemy generated for the {@code full_name} hybrid
   * property: a plain concatenation. A user with no first name concatenates to
   * NULL, so such rows sort last and do not match a name search - the same
   * behaviour as upstream.
   */
  private static Expression<String> fullName(
      CriteriaBuilder cb, Join<FamUserRoleXref, FamUser> user) {
    return cb.concat(cb.concat(user.get("firstName"), " "), user.get("lastName"));
  }
}
