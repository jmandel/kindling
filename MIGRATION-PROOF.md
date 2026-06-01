# Sourcing FHIR datatypes from native StructureDefinition

## What changed

FHIR datatype definitions can now be authored as native FHIR `StructureDefinition` XML
(`source/datatypes/structuredefinition-<Type>.xml`) instead of the legacy "datatype spreadsheet"
format. Kindling detects a native datatype `StructureDefinition`, converts it into the internal
`TypeDefn` model, and feeds it to the rest of the publisher unchanged — so every generated artifact
(profiles, schemas, packages, rendered pages, expansions) is produced exactly as before.

The companion FHIR-source change replaces the datatype spreadsheets under `source/datatypes/` with
native `StructureDefinition` files for the full datatype set (45 complex types, 8 infrastructure
types, 20 primitives) plus the two constraint types (SimpleQuantity, MoneyQuantity), and points the
datatype extension definitions at native ImplementationGuide sources.

## Why it is safe: generated output is unchanged

The native-sourced build was compared against the spreadsheet-sourced build at content (not just
filename) granularity, normalizing only build-volatile fields (timestamps, the build's git-describe
string, generated UUIDs, and absolute output paths):

- Generated datatype profiles: **0 of 228** `.profile.json` differ; **0 of 228** `.profile.xml` differ.
- Generated XML schemas: **0 of 133** `.xsd` differ.
- Rendered `datatypes.html`: no content differences.
- Per-type constraint/invariant counts match (Expression 3, Reference 3, CodeableReference 1,
  Timing 12, Dosage 2, Quantity 3, …).
- Live terminology validation of the example set is identical between the two builds:
  `Errors=0, Warnings=3691, Information messages=349` (931 examples) on both.

All datatypes round-trip native `StructureDefinition` → `TypeDefn` → generated artifacts with no
drift, so the source format is an authoring change only.

## The one intentional output change (an improvement)

`SpecMapManager` now prefers a local relative page over an external absolute URL when the same
canonical URL is registered more than once (previously last-writer-wins). This makes three spec
links resolve to their local pages instead of pointing off-site:

- `CodeSystem/example-metadata` → `codesystem-example-metadata.html` (was an external `tx.fhir.org` URL)
- `ValueSet/synchronicity-control` → `valueset-synchronicity-control.html` (was a `build.fhir.org/ig` URL)
- `CodeSystem/synchronicity-control` → `codesystem-synchronicity-control.html` (was a `build.fhir.org/ig` URL)

The companion change to the `.expansions` package selection makes that package's contents
order-independent (deduped by output filename, preferring the canonical `hl7.org` ValueSet on a
collision). Both changes are independent of the datatype source: run against the unchanged
spreadsheet source they alter nothing else, and add or drop no expansion-package members.

## How the conversion handles open ("*") types

A choice element bound to "any type" (e.g. `Extension.value[x]`, `Task.input.value[x]`) is detected
structurally: the element collapses to the single `*` type iff its declared type set equals
`TypesUtilities.wildcardTypes(version)`. This tracks the version's actual datatype set rather than a
hardcoded element count, so it stays correct as datatypes are added or removed across FHIR versions.

## Robustness

- A native datatype file must declare the type it is named for; a mismatch fails the build.
- A type may not have both a native `StructureDefinition` and a legacy spreadsheet; the ambiguity
  fails the build rather than silently preferring one.
- A constraint whose invariant key is unknown is reported rather than silently dropped.

## Verifying equivalence

Build the publisher and run the FHIR publish twice — once on the native datatype source and once on
the spreadsheet source — then compare the `publish/` trees at content granularity, normalizing the
build-volatile fields listed above. The generated `*.profile.{json,xml}`, `*.xsd`, datatype pages,
package members, and validation summary are identical apart from the three intentional local-link
resolutions described above.
