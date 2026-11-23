package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import ca.bc.gov.nrs.fam.repository.FamPrivilegeChangeAuditRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Who holds each role, remembered between visits to the users table.
 *
 * <p><b>Why.</b> CSS answers membership one role at a time, and a scoped role is
 * a role per scope value - FOM_SUBMITTER for four hundred organisations is four
 * hundred roles. Read fresh on every visit, an application with a few thousand
 * users took twenty seconds to show its table. Nearly all of those reads return
 * exactly what they returned last time.
 *
 * <p><b>Display only.</b> This backs the users listing and nothing else. Anything
 * that decides what somebody may do - authorisation, the expiry sweep, the bulk
 * upload's already-held check - reads CSS directly, so a stale entry here can
 * mislabel a row for a moment but never grant or withhold anything.
 *
 * <p><b>Stale-while-revalidate.</b> An entry younger than {@code fresh-for} is
 * used as is. One older, up to {@code max-stale}, is still shown - and the
 * caller refreshes it in the background, so the next visit is current. Past
 * {@code max-stale} it is read again before answering.
 *
 * <p><b>FAM's own changes show at once, on every pod.</b> FAM runs several pods,
 * each with its own memory, so a grant on one has to reach the others' caches.
 * The audit trail already records every grant and revocation FAM makes, with
 * the application, the role and its scopes - so before each listing the new
 * audit rows for that application are read, and the roles they touched are
 * dropped here. One indexed query per listing, rather than a second record of
 * every change that could drift from the first.
 *
 * <p>Changes made outside FAM - by hand in the CSS console - leave no audit row.
 * They appear once the entry goes stale and is refreshed: within
 * {@code fresh-for}, plus one visit.
 *
 * <p><b>Mock-friendly by shape.</b> A Mockito mock of this class answers
 * {@link Optional#empty()}, {@code false} and no-ops, which is exactly "no cache":
 * every role is read from CSS, as before this existed.
 */
@Slf4j
@Component
public class RoleMembershipCache {

  /**
   * How far back a pod looks past its last check of the audit trail.
   *
   * <p>Audit rows are dated by the clock of the pod that wrote them. A margin
   * covers skew between pods, and the transaction that commits a row a moment
   * after it was dated. Rows already acted on are remembered, so the overlap
   * costs a query's worth of rows rather than repeated invalidations.
   */
  private static final Duration AUDIT_OVERLAP = Duration.ofMinutes(5);

  private final FamPrivilegeChangeAuditRepository auditRepository;
  private final ObjectMapper objectMapper;
  private final Duration freshFor;
  private final Duration maxStale;

  /** One listing's worth of holders, keyed by the role's exact CSS name. */
  private final Map<Key, Entry> entries = new ConcurrentHashMap<>();

  /**
   * When each application last had anything invalidated, by
   * {@link System#nanoTime()}.
   *
   * <p>Per application rather than per role: a read in flight has no entry to
   * remove, and its answer may predate the change - so any read that began
   * before the latest invalidation is returned to its caller but not stored.
   * Coarse, and invalidations are rare enough that the cost is the odd re-read.
   */
  private final Map<App, Long> invalidatedAt = new ConcurrentHashMap<>();

  /** Per application: when the audit trail was last read, and what it held. */
  private final Map<App, AuditWatermark> watermarks = new ConcurrentHashMap<>();

  /** Applications with a background refresh in flight, so visits do not pile up. */
  private final Set<App> refreshing = ConcurrentHashMap.newKeySet();

  public RoleMembershipCache(
      FamPrivilegeChangeAuditRepository auditRepository,
      ObjectMapper objectMapper,
      @Value("${fam.css.membership-cache.fresh-for:PT2M}") Duration freshFor,
      @Value("${fam.css.membership-cache.max-stale:PT12H}") Duration maxStale) {
    this.auditRepository = auditRepository;
    this.objectMapper = objectMapper;
    this.freshFor = freshFor;
    this.maxStale = maxStale;
  }

  /** The holders of one role as last read, and whether they are still fresh. */
  public record Cached(List<CssApiService.CssUserDto> users, boolean fresh) {}

  private record Key(int integrationId, String environment, String roleName) {
    static Key exact(int integrationId, String environment, String roleName) {
      return new Key(integrationId, environment.toLowerCase(Locale.ROOT), roleName);
    }

  }

  private record Entry(List<CssApiService.CssUserDto> users, Instant readAt) {}

  private record App(int integrationId, String environment) {
    static App of(int integrationId, String environment) {
      return new App(integrationId, environment.toLowerCase(Locale.ROOT));
    }
  }

  private record AuditWatermark(LocalDateTime checkedAt, Map<UUID, LocalDateTime> seen) {}

  // ---------------------------------------------------------------------------
  // Reads and writes
  // ---------------------------------------------------------------------------

  /** What is held for one role, unless it is missing or too old to show. */
  public Optional<Cached> lookup(int integrationId, String environment, String roleName) {
    Entry entry = entries.get(Key.exact(integrationId, environment, roleName));
    if (entry == null) {
      return Optional.empty();
    }
    Duration age = Duration.between(entry.readAt(), Instant.now());
    if (age.compareTo(maxStale) > 0) {
      return Optional.empty();
    }
    return Optional.of(new Cached(entry.users(), age.compareTo(freshFor) <= 0));
  }

  /** Marks the start of a CSS read, for {@link #store}. */
  public long beginRead() {
    return System.nanoTime();
  }

  /**
   * Remember what a read returned - unless the role was invalidated after the
   * read began, in which case the answer may predate the change and is dropped.
   */
  public void store(int integrationId, String environment, String roleName,
      List<CssApiService.CssUserDto> users, long readStartedAt) {

    Long mark = invalidatedAt.get(App.of(integrationId, environment));
    if (mark != null && mark - readStartedAt >= 0) {
      log.debug("Not caching {} on {} ({}): it changed while it was being read.",
          roleName, integrationId, environment);
      return;
    }
    entries.put(Key.exact(integrationId, environment, roleName),
        new Entry(List.copyOf(users), Instant.now()));
  }

  /** Claims the background refresh for one application. False if one is running. */
  public boolean tryBeginRefresh(int integrationId, String environment) {
    return refreshing.add(App.of(integrationId, environment));
  }

  public void endRefresh(int integrationId, String environment) {
    refreshing.remove(App.of(integrationId, environment));
  }

  // ---------------------------------------------------------------------------
  // Invalidation
  // ---------------------------------------------------------------------------

  /**
   * Drop every role on this application that FAM has changed since the last
   * look, on any pod.
   *
   * <p>Best effort: if the audit trail cannot be read, everything held for the
   * application is dropped instead. A slow listing is a better failure than a
   * table that quietly hides a grant somebody just made.
   */
  public void syncWithAuditTrail(int integrationId, String environment) {
    App app = App.of(integrationId, environment);
    LocalDateTime now = LocalDateTime.now();
    AuditWatermark previous = watermarks.get(app);

    // Nothing is held for an application before its first listing, so there
    // is nothing for older rows to invalidate.
    LocalDateTime since = previous == null
        ? now.minus(AUDIT_OVERLAP)
        : previous.checkedAt().minus(AUDIT_OVERLAP);

    List<Object[]> changes;
    try {
      changes = auditRepository.findChangesSince(integrationId, environment, since);
    } catch (RuntimeException e) {
      log.warn("Could not read the audit trail for {} ({}); dropping its cached role "
          + "membership: {}", integrationId, environment, e.getMessage());
      invalidateWhere(integrationId, environment, roleName -> true);
      watermarks.remove(app);
      return;
    }

    Map<UUID, LocalDateTime> seen = new ConcurrentHashMap<>();
    if (previous != null) {
      previous.seen().forEach((id, at) -> {
        if (!at.isBefore(since)) {
          seen.put(id, at);
        }
      });
    }

    int applied = 0;
    for (Object[] change : changes) {
      UUID id = (UUID) change[0];
      LocalDateTime changedAt = (LocalDateTime) change[1];
      if (seen.putIfAbsent(id, changedAt) != null) {
        continue;
      }
      invalidateFor(integrationId, environment, (String) change[2]);
      applied++;
    }

    watermarks.put(app, new AuditWatermark(now, seen));
    if (applied > 0) {
      log.debug("Applied {} audited change(s) to cached role membership for {} ({}).",
          applied, integrationId, environment);
    }
  }

  /**
   * Drop the roles one audit row describes.
   *
   * <p>The row holds the base role and the scope values granted, not the
   * concrete role names - so every held role with that base whose scope values
   * all appear in the row is dropped. That can over-reach for a compound scope,
   * which costs a re-read; it can never miss the role that changed. The base
   * role's expiry sidecars go too, since a grant with an expiry assigns one.
   */
  private void invalidateFor(int integrationId, String environment, String detailsJson) {
    /*
        Read as a tree, for the two fields this needs - each role's name and its
        scopes' values - rather than bound to PrivilegeDetailsDto. The trail is
        append-only and years deep; a row written under an older shape of that
        DTO must still invalidate the right role, not fail to bind and drop the
        whole application.
    */
    JsonNode roles;
    try {
      roles = objectMapper.readTree(detailsJson).path("roles");
    } catch (Exception e) {
      roles = null;
    }
    if (roles == null || !roles.isArray() || roles.isEmpty()) {
      // Unreadable, or an appointment that names no role. Nothing narrower is
      // safe, so everything for the application goes.
      invalidateWhere(integrationId, environment, roleName -> true);
      return;
    }

    for (JsonNode role : roles) {
      String base = role.path("role").asText("");
      if (base.isBlank()) {
        invalidateWhere(integrationId, environment, roleName -> true);
        return;
      }
      Set<String> values = new HashSet<>();
      for (JsonNode scope : role.path("scopes")) {
        String value = scope.path("client_id").asText("");
        if (!value.isBlank()) {
          values.add(value.toUpperCase(Locale.ROOT));
        }
      }
      invalidateWhere(integrationId, environment,
          roleName -> touches(roleName, base, values));
    }
  }

  /** Whether a held role is one an audit row for {@code base} could have changed. */
  private static boolean touches(String roleName, String base, Set<String> values) {
    Optional<CssRoleNaming.RoleExpiry> expiry = CssRoleNaming.parseExpiry(roleName);
    if (expiry.isPresent()) {
      return CssRoleNaming.parse(expiry.get().roleName()).baseRoleName()
          .equalsIgnoreCase(base);
    }
    CssRoleNaming.ScopedRoleName parsed = CssRoleNaming.parse(roleName);
    if (!parsed.baseRoleName().equalsIgnoreCase(base)) {
      return false;
    }
    Set<String> held = new HashSet<>();
    parsed.scopes().forEach(scope -> held.add(scope.value().toUpperCase(Locale.ROOT)));
    return values.containsAll(held);
  }

  private void invalidateWhere(
      int integrationId, String environment, Predicate<String> roleName) {

    String env = environment.toLowerCase(Locale.ROOT);
    invalidatedAt.put(App.of(integrationId, environment), System.nanoTime());
    entries.keySet().removeIf(key -> key.integrationId() == integrationId
        && key.environment().equals(env)
        && roleName.test(key.roleName()));
  }
}
