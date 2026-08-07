package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.IdpType;
import ca.bc.gov.nrs.fam.constants.RoleType;
import ca.bc.gov.nrs.fam.constants.ScopeType;
import ca.bc.gov.nrs.fam.constants.UserType;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchPagedResults;
import ca.bc.gov.nrs.fam.dto.ExtUserSearchParams;
import ca.bc.gov.nrs.fam.entity.FamApplication;
import ca.bc.gov.nrs.fam.entity.FamForestClient;
import ca.bc.gov.nrs.fam.entity.FamRole;
import ca.bc.gov.nrs.fam.entity.FamUser;
import ca.bc.gov.nrs.fam.entity.FamUserRoleXref;
import ca.bc.gov.nrs.fam.repository.FamUserRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ExtAppUserSearchService (port of ext_app_user_search_service.py)")
class ExtAppUserSearchServiceTest {

  private static final Long APPLICATION_ID = 1L;

  @Mock private FamUserRepository userRepository;
  @InjectMocks private ExtAppUserSearchService service;

  private FamApplication application;
  private FamApplication otherApplication;

  @BeforeEach
  void setUp() {
    application = new FamApplication();
    application.setApplicationId(APPLICATION_ID);
    application.setApplicationName("FOM_DEV");

    otherApplication = new FamApplication();
    otherApplication.setApplicationId(99L);
    otherApplication.setApplicationName("SPAR_DEV");
  }

  private static FamRole role(Long id, String name, FamApplication app, FamRole parent) {
    FamRole role = new FamRole();
    role.setRoleId(id);
    role.setRoleName(name);
    role.setDisplayName(name);
    role.setRoleTypeCode(parent == null ? RoleType.ABSTRACT.getCode() : RoleType.CONCRETE.getCode());
    role.setApplication(app);
    role.setParentRole(parent);
    return role;
  }

  private static FamRole childRole(Long id, FamRole parent, String clientNumber) {
    FamRole child = role(id, parent.getRoleName() + "_" + clientNumber,
        parent.getApplication(), parent);
    FamForestClient forestClient = new FamForestClient();
    forestClient.setForestClientNumber(clientNumber);
    child.setForestClient(forestClient);
    return child;
  }

  private static FamUser userWithRoles(UserType type, FamRole... roles) {
    FamUser user = new FamUser();
    user.setUserId(2L);
    user.setUserName("JSMITH");
    user.setFirstName("Jane");
    user.setLastName("Smith");
    user.setUserGuid("A".repeat(32));
    user.setUserTypeCode(type.getCode());

    List<FamUserRoleXref> xrefs = new ArrayList<>();
    for (FamRole role : roles) {
      FamUserRoleXref xref = new FamUserRoleXref();
      xref.setUser(user);
      xref.setRole(role);
      xrefs.add(xref);
    }
    user.setUserRoleXrefs(xrefs);
    return user;
  }

  private ExtUserSearchPagedResults search(FamUser... users) {
    when(userRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class),
        any(org.springframework.data.domain.Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(users)));
    return service.searchUsers(APPLICATION_ID, new ExtUserSearchParams());
  }

  @Test
  @DisplayName("reports an unscoped role with an empty client list")
  void unscopedRole() {
    ExtUserSearchPagedResults results =
        search(userWithRoles(UserType.IDIR, role(10L, "FOM_REVIEWER", application, null)));

    assertThat(results.users()).singleElement().satisfies(user -> {
      assertThat(user.idpUsername()).isEqualTo("JSMITH");
      assertThat(user.idpType()).isEqualTo(IdpType.IDIR);
      assertThat(user.roles()).singleElement().satisfies(role -> {
        assertThat(role.roleName()).isEqualTo("FOM_REVIEWER");
        assertThat(role.scopeType()).isNull();
        assertThat(role.value()).isEmpty();
      });
    });
  }

  @Test
  @DisplayName("collapses client-scoped child roles onto their parent")
  void collapsesChildRolesOntoParent() {
    // FAM materialises one child role per client; the contract reports the
    // parent once with a list of client numbers.
    FamRole parent = role(10L, "FOM_SUBMITTER", application, null);

    ExtUserSearchPagedResults results = search(userWithRoles(UserType.IDIR,
        childRole(11L, parent, "00001011"),
        childRole(12L, parent, "00002011")));

    assertThat(results.users()).singleElement()
        .satisfies(user -> assertThat(user.roles()).singleElement().satisfies(role -> {
          assertThat(role.roleName()).isEqualTo("FOM_SUBMITTER");
          assertThat(role.scopeType()).isEqualTo(ScopeType.FOREST_CLIENT);
          assertThat(role.value()).containsExactly("00001011", "00002011");
        }));
  }

  @Test
  @DisplayName("excludes roles belonging to another application")
  void excludesOtherApplicationsRoles() {
    // A user may hold roles across applications; a caller only sees its own.
    ExtUserSearchPagedResults results = search(userWithRoles(UserType.IDIR,
        role(10L, "FOM_REVIEWER", application, null),
        role(20L, "SPAR_VIEWER", otherApplication, null)));

    assertThat(results.users()).singleElement()
        .satisfies(user -> assertThat(user.roles())
            .extracting(r -> r.roleName())
            .containsExactly("FOM_REVIEWER"));
  }

  @Test
  @DisplayName("reports pagination metadata in the external shape")
  void reportsExternalPaginationShape() {
    // total/pageCount/page/size, not the internal total/number_of_pages/...
    ExtUserSearchPagedResults results =
        search(userWithRoles(UserType.IDIR, role(10L, "FOM_REVIEWER", application, null)));

    assertThat(results.meta().total()).isEqualTo(1);
    assertThat(results.meta().page()).isEqualTo(1);
    assertThat(results.meta().size()).isEqualTo(50);
  }

  @ParameterizedTest(name = "{0} -> {1}")
  @CsvSource({"I, IDIR", "B, BCEID", "CD, BCSC", "CT, BCSC", "CP, BCSC"})
  @DisplayName("maps stored user type codes onto the external vocabulary")
  void mapsUserTypeToIdpType(String code, IdpType expected) {
    // Everything that is not IDIR or Business BCeID is reported as BCSC; the
    // environment-specific card codes are not exposed.
    assertThat(ExtAppUserSearchService.toIdpType(code)).isEqualTo(expected);
  }
}
