package ca.bc.gov.nrs.fam.constants;

import lombok.Getter;

/**
 * BC natural resource districts, used to scope roles marked district-scoped.
 *
 * <p>Port of the {@code District} enum in {@code constants.py}. A fixed reference
 * set held in code rather than a table: unlike forest clients, districts are a
 * small, slow-moving list, and FAM does not own the source data.
 *
 * <p>{@code expired} marks a district that has been dissolved or renamed. Expired
 * districts stay in the list so that a permission already referencing one still
 * resolves, but they are filtered out of the picker so no new grant can use them.
 */
@Getter
public enum District {

  DCC("DCC", "Cariboo-Chilcotin Natural Resource District", false),
  DCS("DCS", "Cascades Natural Resource District", false),
  DOS("DOS", "Okanagan Shuswap Natural Resource District", false),
  DRM("DRM", "Rocky Mountain Natural Resource District", false),
  DNI("DNI", "North Island - Central Coast Natural Resource District", false),
  DPG("DPG", "Prince George Natural Resource District", false),
  DVA("DVA", "Stuart Nechako Natural Resource District", false),
  DKM("DKM", "Coast Mountains Natural Resource District", false),
  DMK("DMK", "Mackenzie Natural Resource District", false),
  DFN("DFN", "Fort Nelson Natural Resource District", false),
  DKA("DKA", "Thompson Rivers Natural Resource District", false),
  DMH("DMH", "100 Mile House Natural Resource District", false),
  DQU("DQU", "Quesnel Natural Resource District", false),
  DCK("DCK", "Chilliwack Natural Resource District", false),
  DSQ("DSQ", "Sea to Sky Natural Resource District", false),
  DSC("DSC", "Sunshine Coast Natural Resource District", false),
  DCR("DCR", "Campbell River Natural Resource District", false),
  DQC("DQC", "Haida Gwaii Natural Resource District", false),
  DSI("DSI", "South Island Natural Resource District", false),
  DND("DND", "Nadina Natural Resource District", false),
  DSS("DSS", "Skeena Stikine Natural Resource District", false),
  DPC("DPC", "Peace Natural Resource District", false),
  DSE("DSE", "Selkirk Natural Resource District", false);

  /** Org unit code, e.g. {@code DCC}. This is what travels in a scoped role name. */
  private final String orgUnitCode;

  private final String orgUnitName;

  private final boolean expired;

  District(String orgUnitCode, String orgUnitName, boolean expired) {
    this.orgUnitCode = orgUnitCode;
    this.orgUnitName = orgUnitName;
    this.expired = expired;
  }
}
