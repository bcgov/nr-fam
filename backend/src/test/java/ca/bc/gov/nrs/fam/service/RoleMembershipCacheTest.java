package ca.bc.gov.nrs.fam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsPermissionType;
import ca.bc.gov.nrs.fam.constants.PrivilegeDetailsScopeType;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsRoleDto;
import ca.bc.gov.nrs.fam.dto.PrivilegeDetailsScopeDto;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("RoleMembershipCache")
class RoleMembershipCacheTest {

  private static final int APP = 54321;
  private static final String ENV = "test";

  /** The application's own mapper settings, so audit JSON round-trips as written. */
  private final ObjectMapper objectMapper = new ObjectMapper()
      .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);

  private FamPrivilegeChangeAuditRepository auditRepository;
  private final List<Object[]> auditRows = new ArrayList<>();

  private RoleMembershipCache cache;

  @BeforeEach
  void setUp() {
    auditRepository = mock(FamPrivilegeChangeAuditRepository.class);
    when(auditRepository.findChangesSince(anyInt(), anyString(), any()))
        .thenAnswer(i -> List.copyOf(auditRows));
    cache = new RoleMembershipCache(
        auditRepository, objectMapper, Duration.ofMinutes(2), Duration.ofHours(12));
  }

  private static List<CssApiService.CssUserDto> users(String... usernames) {
    return java.util.Arrays.stream(usernames)
        .map(name -> new CssApiService.CssUserDto(name, null, null, null, null))
        .toList();
  }

  private void store(String roleName, String... usernames) {
    cache.store(APP, ENV, roleName, users(usernames), cache.beginRead());
  }

  /** An audit row as PermissionAuditWriteService writes it: the real DTOs, serialised. */
  private void audited(String role, String... clientIds) {
    List<PrivilegeDetailsScopeDto> scopes = java.util.Arrays.stream(clientIds)
        .map(id -> new PrivilegeDetailsScopeDto(PrivilegeDetailsScopeType.CLIENT, id, null, null))
        .toList();
    PrivilegeDetailsDto details = new PrivilegeDetailsDto(
        PrivilegeDetailsPermissionType.END_USER,
        List.of(new PrivilegeDetailsRoleDto(
            role, scopes.isEmpty() ? null : scopes, null, null)));
    try {
      auditRows.add(new Object[] {
          UUID.randomUUID(), LocalDateTime.now(), objectMapper.writeValueAsString(details)});
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  @Nested
  @DisplayName("reads")
  class Reads {

    @Test
    @DisplayName("a role never read is missing")
    void missing() {
      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isEmpty();
    }

    @Test
    @DisplayName("a role just read is served fresh")
    void fresh() {
      store("FOM_REVIEWER", "a@azureidir");

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).hasValueSatisfying(hit -> {
        assertThat(hit.fresh()).isTrue();
        assertThat(hit.users()).extracting(CssApiService.CssUserDto::username)
            .containsExactly("a@azureidir");
      });
    }

    @Test
    @DisplayName("past fresh-for it is still served, marked stale")
    void stale() {
      cache = new RoleMembershipCache(
          auditRepository, objectMapper, Duration.ZERO, Duration.ofHours(12));
      store("FOM_REVIEWER", "a@azureidir");

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER"))
          .hasValueSatisfying(hit -> assertThat(hit.fresh()).isFalse());
    }

    @Test
    @DisplayName("past max-stale it is missing again")
    void tooOld() {
      cache = new RoleMembershipCache(
          auditRepository, objectMapper, Duration.ZERO, Duration.ZERO);
      store("FOM_REVIEWER", "a@azureidir");

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isEmpty();
    }

    @Test
    @DisplayName("roles are kept per application and environment")
    void perApplication() {
      store("FOM_REVIEWER", "a@azureidir");

      assertThat(cache.lookup(APP, "prod", "FOM_REVIEWER")).isEmpty();
      assertThat(cache.lookup(APP + 1, ENV, "FOM_REVIEWER")).isEmpty();
    }
  }

  @Nested
  @DisplayName("FAM's own changes, from the audit trail")
  class AuditInvalidation {

    @BeforeEach
    void firstListing() {
      // A pod's first look sets its watermark; changes after it are what count.
      cache.syncWithAuditTrail(APP, ENV);
    }

    @Test
    @DisplayName("a grant for one organisation drops that organisation's role only")
    void dropsTheScopedRoleGranted() {
      store("FOM_SUBMITTER_FOREST_CLIENT-00169257", "a@bceidbusiness");
      store("FOM_SUBMITTER_FOREST_CLIENT-00082820", "b@bceidbusiness");
      store("FOM_REVIEWER", "c@azureidir");

      audited("FOM_SUBMITTER", "00169257");
      cache.syncWithAuditTrail(APP, ENV);

      assertThat(cache.lookup(APP, ENV, "FOM_SUBMITTER_FOREST_CLIENT-00169257")).isEmpty();
      assertThat(cache.lookup(APP, ENV, "FOM_SUBMITTER_FOREST_CLIENT-00082820")).isPresent();
      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isPresent();
    }

    @Test
    @DisplayName("an unscoped grant drops the base role, not its scoped relatives")
    void dropsTheUnscopedRole() {
      store("FOM_REVIEWER", "c@azureidir");
      store("FOM_SUBMITTER_FOREST_CLIENT-00169257", "a@bceidbusiness");

      audited("FOM_REVIEWER");
      cache.syncWithAuditTrail(APP, ENV);

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isEmpty();
      assertThat(cache.lookup(APP, ENV, "FOM_SUBMITTER_FOREST_CLIENT-00169257")).isPresent();
    }

    @Test
    @DisplayName("the audit trail being unreadable drops everything for the application")
    void auditOutageDropsAll() {
      store("FOM_REVIEWER", "c@azureidir");
      store("OTHER", "d@azureidir");
      when(auditRepository.findChangesSince(anyInt(), anyString(), any()))
          .thenThrow(new IllegalStateException("db down"));

      cache.syncWithAuditTrail(APP, ENV);

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isEmpty();
      assertThat(cache.lookup(APP, ENV, "OTHER")).isEmpty();
    }

    @Test
    @DisplayName("unreadable details drop everything for the application")
    void unreadableDetailsDropAll() {
      store("FOM_REVIEWER", "c@azureidir");
      auditRows.add(new Object[] {UUID.randomUUID(), LocalDateTime.now(), "not json"});

      cache.syncWithAuditTrail(APP, ENV);

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isEmpty();
    }

    @Test
    @DisplayName("a change already acted on is not acted on again")
    void seenOnce() {
      store("FOM_REVIEWER", "c@azureidir");
      audited("FOM_REVIEWER");
      cache.syncWithAuditTrail(APP, ENV);

      // Re-read after the change, and the same row is still inside the overlap.
      store("FOM_REVIEWER", "c@azureidir", "e@azureidir");
      cache.syncWithAuditTrail(APP, ENV);

      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isPresent();
    }

    @Test
    @DisplayName("a read that began before a change is not stored over it")
    void inFlightReadIsRefused() {
      long started = cache.beginRead();

      audited("FOM_REVIEWER");
      cache.syncWithAuditTrail(APP, ENV);

      cache.store(APP, ENV, "FOM_REVIEWER", users("c@azureidir"), started);
      assertThat(cache.lookup(APP, ENV, "FOM_REVIEWER")).isEmpty();
    }
  }

  @Test
  @DisplayName("one background refresh per application at a time")
  void oneRefreshAtATime() {
    assertThat(cache.tryBeginRefresh(APP, ENV)).isTrue();
    assertThat(cache.tryBeginRefresh(APP, ENV)).isFalse();
    assertThat(cache.tryBeginRefresh(APP, "prod")).isTrue();

    cache.endRefresh(APP, ENV);
    assertThat(cache.tryBeginRefresh(APP, ENV)).isTrue();
  }
}
