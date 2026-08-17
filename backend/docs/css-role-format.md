# How a role is represented in CSS

FAM used to hold roles in its own tables, with a code, a display name, a
description and a scope type as columns. Those tables are gone; CSS is the store
now. This is how the same information is carried when the store has one field.

## The constraint

A CSS role is a name. That is the whole of it:

```console
$ curl -X POST .../integrations/22261/dev/roles \
       -d '{"name":"ZZ_PROBE","description":"Probe"}'
{"message":"only name is supported"}
```

No description, no display name, no attributes. `GET` returns `{"name": ...,
"composite": ...}` and nothing else. So everything beyond the name has to be
expressed either **in** a name or through **composite membership**.

Role names themselves are permissive - spaces, `:`, `|` and `.` are all accepted -
which is what makes the conventions below possible.

## The format

A role defined through *Manage roles* becomes up to three CSS roles:

```
FREP_ADMINISTRATOR                                  the role; its name is the code
└── HAS_DISTRICT_ROLE                               composite child, when scoped

FAM:LABEL:FREP_ADMINISTRATOR:FREP Administrator     sidecar, holds the description
```

| Piece | Carried by | Read by |
| ----- | ---------- | ------- |
| Raw code | the role's own name | `CssRoleOptionDto.name` |
| Description | a sidecar role, `FAM:LABEL:<CODE>:<text>` | `CssRoleNaming.parseLabel` |
| Scope type | a marker role composed into it | `CssRoleNaming.MARKERS` |
| Scope value | appended at grant time, `<CODE>_DISTRICT-DCC` | `CssRoleNaming.parse` |

### Why the code is the role's own name

The role name is what reaches the access token, and it is what an application
authorises on. Making the code *be* the name means the thing applications check
for is the thing an administrator typed.

### Why the description is a sidecar, not a composite child

**Composite children propagate into the token.** Keycloak expands a composite, so
a description held as a child role would appear in every holder's token as though
it were a role, leaving every downstream application to filter it out. A sidecar
is assigned to nobody and composed into nothing, so it exists only in the role
listing, where FAM reads it.

It also decouples the two. Correcting a description rewrites one sidecar and
touches no assignment. Under the alternative - naming the outer composite role for
the description, which is how the roles inherited from FSP are shaped - the
display text is what gets assigned and scope-suffixed, so a token carries
`Submitter (SLR)_DISTRICT-DCC` and rewording the description orphans every
existing assignment.

### Why `:` is the delimiter

A role code is validated as `^[A-Z][A-Z0-9_]{1,58}$`, which excludes both `:` and
`-`. So a sidecar splits unambiguously at the first `:` after the prefix, and a
description containing a colon survives whole. The `-` exclusion keeps the scope
suffix parseable for the same reason.

## The older shape still reads

Roles configured by hand before this screen existed are shaped the other way
round: a composite named for the display text, wrapping the machine code.

```
Submitter (CHR)  ->  CHR_FREP_EDITOR  ->  HAS_DISTRICT_ROLE
```

Both are read by the same code. A role is selectable when nothing else composes
it, its scope comes from any marker in its chain, and `roleCode` is the first
non-marker descendant. A role in the new format simply has no descendant but its
marker, and finds its description on a sidecar instead.

## Rules enforced at creation

- **FAM administrators only.** Deciding *which roles exist* changes an
  application's authorisation model; deciding *who holds one* does not. `APP_ADMIN`
  can do the second and not the first.
- **District or forest client, never both.** A grant carries one `scope_type` and
  the picker offers one kind of scope, so a role marked both would silently behave
  as district scoped with its client side unreachable.
- **A code cannot be reused.** People may already hold it, and `create` must not
  silently redefine what they have.
- **Marker names are reserved.** Creating `HAS_DISTRICT_ROLE` as a role of its own
  would make every scoped role in the integration point at it.
- **Partial creations are undone.** Half a role - one with no description, or a
  scoped role with no marker - is indistinguishable from one somebody meant to
  create, and cannot be finished from the screen because the code is taken. Only
  roles the failed call itself created are removed; a marker that already existed
  belongs to other roles.

## What is recorded

CSS keeps no history of role definitions, so FAM writes one
`fam_privilege_change_audit` row per role created - type `CREATE_ROLE`, added in
V95. Without it nothing anywhere records who introduced a role.

That row is shaped differently from the rest of the trail:

- **No target user.** There is nobody it was done to, so `target_user_guid` and
  `target_user_type_code` are null rather than pointed at the performer, which
  would read as somebody granting themselves something.
- **`privilege_details` describes the role, not a privilege** - its code,
  description and required scope type. The document is deliberately a *superset*
  of the grant/revoke shape, carrying `permission_type` and `roles` as well, so
  anything reading the column the old way sees a coherent record naming the role
  instead of failing on a shape it does not recognise.
- **Not currently readable through the API.** `GET /permission-audit-history` is
  keyed on a target user GUID, and this row has none, so it never surfaces there.
  It is written for the trail, not for a screen.

A failed audit write fails the request. The role already exists in CSS at that
point, so the caller is told the operation failed while the role is there - the
same bargain the grant path makes, on the grounds that an unrecorded change is
worse than a confusing one.

## Granting to somebody who has never signed in

Assignment goes through `POST .../users/{username}/roles-new`, not the older
`.../roles`. Keycloak only holds a federated user once they have authenticated at
least once, and the older endpoint answers **404 User not found** for anyone
else - which is every new starter. They could not sign in to be created, having
no access, and could not be granted access, not having signed in. `roles-new`
verifies the username against the upstream identity provider and creates the
record itself.

It also refuses a username the directory cannot resolve:

```console
$ POST .../users/00000000000000000000000000000001@azureidir/roles-new
{"message":"could not verify user ... with the upstream identity provider"}
```

That message reaches the administrator on the failed role, which is worth having:
a role assigned to a username that does not exist is a grant that silently does
nothing.

**Only `azureidir` and `bceidbusiness` are accepted.** The legacy `idir` alias is
rejected outright (`invalid idp idir`), which is why the aliases are fixed in
`application.yml` rather than exposed as deployment configuration - there is no
correct alternative value to supply. The service still warns at startup if the
environment-variable escape hatch is used to set the legacy one.

### The record starts empty

A user created this way holds a username and nothing else until they first sign
in. Compare what CSS returns for the two:

```jsonc
// created by a grant, never signed in
{ "username": "1122...0011@azureidir" }

// after signing in
{ "username": "0a1b...e8f9@azureidir", "email": "...", "firstName": "Jane",
  "lastName": "Smith", "attributes": { "idir_username": ["JSMITH"], ... } }
```

CSS cannot fill that in - the attributes are populated by the sign-in itself -
so **FAM resolves those rows against the directory instead**. See
`AssignmentRowEnrichmentService`: rows CSS could not name are looked up by GUID
through `nr-user-lookup-api`, deduplicated, so the table shows `JSMITH /
Jane Smith` rather than a raw GUID.

Three properties of that enrichment are deliberate:

- **Only the unnamed rows.** A user CSS has already named is left alone; CSS is
  the more current source for anyone who has signed in, and re-resolving them
  would cost a call per user to change nothing.
- **Best effort.** `UserLookupClient` otherwise raises on an upstream failure,
  because its usual caller is an administrator searching for somebody and
  "nobody matched" differs materially from "the directory is unreachable". Here
  the assignments are already correct, so a directory outage costs a few names
  rather than the whole table: failures are logged, the loop stops, and the rows
  return as CSS sent them.
- **Bounded at 25 lookups per listing**, logged when it bites. Each is a call to
  a SOAP-backed directory, and a backlog of never-signed-in users would otherwise
  turn one page load into hundreds of upstream requests.

This needs the `userGuid` form of `/idir-account-detail`, which the directory
gained for this purpose - IDIR lookups were by `userId` only, and a GUID is all
CSS has. BCeID rows are not resolved: the directory supports it, but the
same-organisation rule that governs reading a Business BCeID user has no obvious
reading on a row the administrator is already entitled to see.

## What CSS still cannot do

- **No rename or edit.** A description can be corrected by replacing its sidecar,
  but nothing here changes a role code, because the code is the identity of every
  assignment made under it.
- **No deletion from the UI.** Deleting a role revokes everyone holding it, at
  once and silently. `CssApiService.deleteRole` exists only to undo a failed
  creation.
- **Scope-specific roles accumulate.** `<CODE>_DISTRICT-DCC` is created on first
  grant and never removed, which is what makes reading assignments cost one
  request per role.
