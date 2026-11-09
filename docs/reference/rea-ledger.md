# REA evidence ledger

Target: installed GoLand and PyCharm 2026.1.4, macOS Apple silicon.
These are reference applications. No proprietary implementation is included in
the reconstruction. Static declarations are acceptance candidates, not proof of
runtime behavior.

| Observation | Authority | Evidence | Scope / limitations |
| --- | --- | --- | --- |
| GoLand bundle identity GO-261.26222.72 | shipped-artifact, observed | `ev_f4de67db294b87c7b83c32378d461135a99d4699fc4087ab6c69966fa85e02ba` | Explicit installed `Info.plist`; SHA-256 `9968b91af9d7b7c51c344e56c9e9afbb4d2d448c9ed5d994e1611a03e68fcf0a`. No runtime behavior established. |
| PyCharm bundle identity PY-261.26222.68 | shipped-artifact, observed | `ev_3a2794bd04161298dbe071a3ea6edb0e4df6b30a3aee9fbccdcfbb506d1b8bab` | Explicit installed `Info.plist`; SHA-256 `40efccabf6681f20d008025d998403b43d0e56dd3ab90479cd372ce1f4e3e260`. No runtime behavior established. |

The exported session is `rea-bundle-initial.json`. It also retains an earlier
explicit PyCharm plist read made while GoLand's plist was the active target
(`ev_76f2955650a945a06c9bc508235eb174482e2beba7a74ddab2a4ac0721b9892e`).
Its parsed source path is PyCharm but its subject inherits the GoLand context;
use the later, correctly bound PyCharm record above for identity claims.

See `installed-ides.json` for reproducible selected JAR/resource observations,
and `acceptance-matrix.json` for runtime requirements. None of the latter are
marked verified by the static inventory.

## Native UI observation

On 2026-11-08 the installed GoLand was launched with Computer Use. Its welcome
workbench rendered, including native tool-window stripes and Run/Debug controls.
Attempting to open the disposable Go acceptance workspace encountered an
activation dialog, so no reference edit/run/debug behavior was established.
No activation, account, license, or preference changes were performed. This is a
Computer Use observation, separate from REA's static Evidence above; it does not
close any runtime feature requirement.

## Go plugin archive

REA inspected the installed `plugins/go-plugin/lib/go-plugin.jar` without
executing or extracting its implementation. Archive SHA-256:
`198efc87e023e13f166248e56421c90dab630dbd2552c756d172a56ca02155f0`.

- Inspection: `ev_1bbe444ca99f806c351f57d339609565400246c3efe20f3108b70a77845a8cf3`.
- Inventory: `ev_6e83a4534edd89427f4d3a3376a4298d405d86d086458de6bdecc6149fc82e8c`.
- Manifest: `agm_689ef0a8e81b9f963c841ca6a3e7379acdaa5bc5f2d79d214dbe34106a8e22d3`.
- Observed 5,701 ZIP entry occurrences, 5,650 content-addressed nodes, and zero
  reported integrity contradictions within the ZIP inventory.

`rea-bundle-go-plugin.json` retains the canonical archive and selected descriptor
inventory. Central-directory inventory does not establish runtime behavior or
the semantics of JVM implementation classes. Resource declarations are further
parsed by the separate reproducible `inspect_reference.py` investigation.


## Interface-generation entry points

The exact Go plugin archive was reopened with REA and its same authenticated
inventory reproduced. Focused descriptor occurrence view
`ev_aacd759185d4d17c2ff9abe2a7f770351dc30434ca4d4e3e79c3adde7c71bc08`
retains the META-INF/plugin.xml identity. Separate XML parsing observed the
codeInsight.implementMethod extension and GoGenerateMethodAction declaration;
resource SHA-256 is `d44228e7f63f725c7c1996d4b1c555650b9b20a14294cc268a8023639337d19f`.
Canonical selected Evidence, primary public guide, inference and unknowns are
retained in [method-generation-reference.json](method-generation-reference.json).
The investigation read no proprietary implementation classes, and the archive
session was closed. Static registration is not commercial runtime verification.


## Struct helper reference scope

The same Go plugin archive was reopened with REA for a bounded descriptor and
user-visible resource inventory. Selected occurrence view
`ev_7142f9e56a540a2c11fc9bb1a689eeb712d18f33465120757a7d6f354cf87044`
retains nine selected resource identities. The separately parsed registrations
include struct constructors/accessors and tag add/update/name-style intentions.
[struct-helpers-reference.json](struct-helpers-reference.json) records exact
SHA-256 identities, public JetBrains documentation, observations, inference and
unknowns. The reference update-value description concerns placing multiple
carets, so our bulk tag planner is an independent workflow and does not establish
that interaction's parity. Remove-key behavior has no selected reference proof.
No proprietary implementation classes were read, copied or executed; the REA
archive session was closed. Commercial runtime behavior remains unverified.


## Native typing and indentation registrations

The authenticated Go plugin descriptor was revisited through selected occurrence
view `ev_b5ba1d7cac2f6cb834034e1109f5bd50cdbcc6eebd854c1e2c35829a089dab2d`.
Its unchanged resource digest binds the registered quote, comment, brace, typed,
Enter, line-indent and Smart Enter extension candidates.
[Typing reference scope](editor-typing-reference.json) records exact selected
attributes, primary 2026.1 help and OSS Platform documentation, inferences and
unknowns. No proprietary implementation classes were read or executed, no
commercial settings were changed, and the REA session was closed. These static
registrations do not verify commercial typing or editor parity.


## Type/call hierarchy and Structure reference

REA reauthenticated the installed GoLand 2026.1.4 Go plugin and platform archives.
The bounded descriptor, action and keymap investigation retained exact resource
occurrences and hashes; all seven selected resources matched the canonical
archive inventories. [Hierarchy and Structure reference](hierarchy-structure-reference.json)
records complete Evidence IDs, standard directions/base-navigation actions and
primary 2026.1 help. Two official help images separately show signatures, field
types and receiver grouping. These are static resource/public image observations,
not local commercial runtime or default-setting verification. No proprietary
implementation classes were read or copied, no reference settings were changed,
and REA was closed after the investigation. Full runtime parity remains open.


## Structure package view, receiver grouping and Go controls

[Structure package reference](structure-package-reference.json) preserves a further
bounded REA resource pass: the unchanged Go archives were authenticated and five
selected `messages/GoBundle.properties` entries identify package visibility, private
members, exportability ordering and a method-location literal. The frontend resource
SHA-256 is `e042135ed9a5159235b144ac919a1ba083778f8e4b6296261049d64d67c8b3e1`.
No proprietary implementation class contents or reference feature runtime were read,
and the REA session was closed. Literal labels do not establish internal property
IDs, effective defaults or the meaning of the `of {0}` interpolation.

The same record separates published 2026.1 screenshots, pinned OSS model/filter/
sorter contracts, and an owned actual gopls v0.21.1 stdio control. The backend command
returns a syntax-based graph for its selected metadata context, including independent
child-file indices; it is not a typed receiver proof or native UI evidence. The
reconstruction's recovery for omitted receiver methods and its build/native checks
are tracked in [Structure package verification](../verification/structure-package.md).
Commercial runtime defaults and complete feature parity remain unverified.
