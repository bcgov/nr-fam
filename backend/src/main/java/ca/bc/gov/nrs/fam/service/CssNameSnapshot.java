package ca.bc.gov.nrs.fam.service;

import ca.bc.gov.nrs.fam.dto.CssRoleNaming;
import ca.bc.gov.nrs.fam.integration.CssApiService;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * What an application and a role are called, for writing onto an audit row.
 *
 * <p>The audit trail records names as snapshots so it stays readable after the
 * thing it names is gone. Taking a snapshot means asking CSS at the moment of
 * the write, and the write happens once per grant - which for a bulk upload is
 * once per row. Asking CSS twice per row would undo the whole reason a large
 * file is possible.
 *
 * <p><b>So the answers are held briefly.</b> A minute is long enough that one
 * upload asks once and short enough that a rename is picked up while somebody is
 * still in the screen that made it. The cost of being stale is an audit row
 * carrying a name that changed seconds earlier, which is a far smaller error
 * than the one this exists to fix - a row that has no name at all.
 *
 * <p>Every lookup is best effort. CSS being unreachable costs the row its
 * readable name, never the row itself: an audit record that failed to write
 * because a label could not be fetched would be the worst outcome available.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CssNameSnapshot {

  /**
   * How long an answer is reused.
   *
   * <p>Sized against the thing it protects: a bulk upload of a thousand rows
   * runs well inside a minute, so it asks CSS once rather than a thousand times.
   */
  private static final Duration TTL = Duration.ofMinutes(1);

  private final CssApiService cssApiService;

  private record Cached<T>(T value, Instant readAt) {
    boolean isFresh(Instant now) {
      return readAt.plus(TTL).isAfter(now);
    }
  }

  private final Map<String, Cached<String>> applicationNames = new ConcurrentHashMap<>();
  private final Map<String, Cached<Map<String, String>>> roleNames = new ConcurrentHashMap<>();

  /**
   * How the application reads, e.g. {@code FREP (DEV)}.
   *
   * <p>The same shape {@code CssIntegrationService.getApplications} produces, so
   * a name written here and a name resolved there are the same string rather
   * than two spellings of one application.
   */
  public Optional<String> applicationName(Integer integrationId, String environment) {
    if (integrationId == null || environment == null) {
      return Optional.empty();
    }
    String key = key(integrationId, environment);
    Cached<String> cached = applicationNames.get(key);
    if (cached != null && cached.isFresh(Instant.now())) {
      return Optional.ofNullable(cached.value());
    }

    String found = readApplicationName(integrationId, environment);
    applicationNames.put(key, new Cached<>(found, Instant.now()));
    return Optional.ofNullable(found);
  }

  /**
   * What a role is called, from its label sidecar.
   *
   * <p>Empty for a role with no sidecar, which is every role added directly in
   * the CSS console. The code is what the row records regardless; this only ever
   * adds a name beside it.
   */
  public Optional<String> roleDisplayName(
      Integer integrationId, String environment, String roleCode) {

    if (integrationId == null || environment == null || roleCode == null) {
      return Optional.empty();
    }
    String key = key(integrationId, environment);
    Cached<Map<String, String>> cached = roleNames.get(key);
    Map<String, String> names;
    if (cached != null && cached.isFresh(Instant.now())) {
      names = cached.value();
    } else {
      names = readRoleNames(integrationId, environment);
      roleNames.put(key, new Cached<>(names, Instant.now()));
    }
    return Optional.ofNullable(names.get(roleCode));
  }

  private String readApplicationName(Integer integrationId, String environment) {
    try {
      return cssApiService.getIntegrations().stream()
          .filter(integration -> integrationId.equals(integration.id()))
          .findFirst()
          .map(integration -> "%s (%s)".formatted(
              integration.projectName(), environment.toUpperCase(Locale.ROOT)))
          .orElse(null);
    } catch (RuntimeException e) {
      log.warn("Could not name integration {} for the audit trail; the row will carry no "
          + "application name. Reason: {}", integrationId, e.getMessage());
      return null;
    }
  }

  private Map<String, String> readRoleNames(Integer integrationId, String environment) {
    try {
      Map<String, String> names = new java.util.HashMap<>();
      cssApiService.getRoles(integrationId, environment).forEach(role ->
          CssRoleNaming.parseLabel(role.name())
              .ifPresent(label -> names.put(label.roleCode(), label.text())));
      return names;
    } catch (RuntimeException e) {
      log.warn("Could not read role names on integration {} ({}) for the audit trail; the "
          + "row will carry codes only. Reason: {}", integrationId, environment, e.getMessage());
      return Map.of();
    }
  }

  private static String key(Integer integrationId, String environment) {
    return integrationId + "|" + environment.toLowerCase(Locale.ROOT);
  }
}
