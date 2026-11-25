# Design Decisions

## Comments: one element per line, not merged

**Decision:** Each comment line in the CSV is emitted as a separate `Comment` element in the flow, even though Apache Commons CSV merges consecutive comment lines into a single `getComment()` string.

**Rationale:**
- `zipWithIndex` on the flow gives correct line numbers — no ambiguity about what "one element" means.
- Record-by-record parsing semantics stay obvious: one line = one element.
- Re-splitting Apache's merged comment string on `\n` is safe because CSV comments are not quoted fields — they have no escaping or quoting rules. Each `\n` in a merged comment string genuinely represents a line boundary in the source.

**Trade-off:** Requires splitting Apache's merged comment string back into individual lines. Minimal cost, preserves source fidelity.

## Flow element types: flat union, no wrapper

**Decision:** The parsed flow type is `Flow[Comment | T | HeaderRecord | EmptyRecord]` — data rows are bare `T` (the user's case class), not wrapped in `DataRecord` or `CsvRecord`.

**Rationale:**
- Each element is one thing — no nested `Option[String]` comment fields on every record type.
- Filtering by type (e.g. `.collect { case t: T => t }`) is natural.
- Comments decomposed into their own elements (see above) removes the need for a wrapper that associates comment + data.

## Missing data: three distinct states

**Decision:** The decoder distinguishes three states per column:
1. **Missing** — column absent from row (ragged row, iterator exhausted). Handled by `MissingDataFallback` typeclass.
2. **Null** — format's null marker matched (e.g. `\N` in PostgreSQL). Apache returns Java `null`.
3. **Empty string** — cell present with value `""`. A valid value for `String` fields.

**Rationale:** Apache Commons CSV preserves all three states: iterator stops for missing columns, returns `null` for null-marked values, returns `""` for empty cells. Collapsing any two loses information.

## DisallowRaggedRows: opt-in strictness

**Decision:** Ragged row tolerance is the default — rows with fewer columns than the target type use `MissingDataFallback.default` for missing trailing fields. The `DisallowRaggedRows` type-level format setting opts into strict mode: short rows fail at runtime.

**Rationale:** No tool conforming to any predefined format produces ragged rows in normal operation (Excel pads, DB exports always emit all columns, RFC 4180 SHOULD have uniform count). However, real-world data is messy — lenient by default, strict by opt-in. `Rfc4180Strict` and `ExcelStrict` include `DisallowRaggedRows`.

## Edge case: empty lines, ragged rows, and NullString overlap

**Decision:** Document — not resolve — the inherent CSV ambiguity between empty lines, ragged rows with empty fields, and null string matching.

**The problem:** An empty line in CSV produces a record with `size=1, value=""`. This is indistinguishable from:
- A ragged row where only the first field exists and is empty
- A legitimate single-column row with empty string value
- A null value when `NullString` is `""`

**Consequences by format setting combination:**

| IgnoreEmptyLines | Ragged fallbacks | NullString="" | Behavior |
|-----------------|-----------------|---------------|----------|
| true | — | — | Empty lines silently dropped. Single-column unquoted empty values also dropped (data loss). Quoted `""` preserved. |
| false | inactive | — | Empty lines emitted as `EmptyRecord` (detectable by column count < expected). |
| false | active | — | Empty lines decoded as `T` with all default values. `EmptyRecord` never emitted. |
| false | active | yes | Empty line → null first field + defaults for rest → valid `T`. Indistinguishable from all-null data row. |

**Rationale:** This ambiguity is inherent to CSV — no library design can resolve it. The type-level format settings make the user aware of which states are possible. The `EmptyRecord` type in the flow union signals "handle this case."

## CsvEncoder / CsvDecoder: separate traits

**Decision:** Field-level codec split into `CsvDecoder[+T]` (covariant) and `CsvEncoder[-T]` (contravariant) instead of a single `CsvCodec`.

**Rationale:** Encode and decode have opposite variance requirements. A combined trait forces invariance or `Any`-based escape hatches. Separate traits preserve type safety and allow providing only one direction when the other isn't needed.

## CsvEncoder returns Option[String]

**Decision:** `CsvEncoder.encode` returns `Option[String]`, where `None` represents null. Apache CSV's printer handles null rendering per format configuration (null string, quoting).

**Rationale:** Keeps encoder as a pure type-to-value bridge. Format-specific null representation (e.g. `\N`, empty string, omitted) is the printer's concern, not the encoder's.

## Handling missing and null data: MissingData and NullData typeclasses

**Decision:** Two separate typeclasses govern what happens when a field has no value:
- `MissingData[T]` — column absent from row (ragged rows). Consulted when `DisallowRaggedRows` is absent (the default).
- `NullData[T]` — format's null string matched (e.g. `\N`). Consulted when the format defines a null string.

**Instance resolution follows a layered design:**

| Layer | Scope | What it provides |
|-------|-------|------------------|
| Companion object | Always in scope | `Option[T]` → `None` |
| `defaults.missingData` | Explicit import | Primitives: `Int` → `0`, `String` → `""`, etc. |
| `defaults.nullData` | Explicit import | Same primitives for null handling |
| `defaults.all` | Explicit import | Re-exports both above |
| User-defined | Explicit `given` | Custom handling for any type |

**Import paths (cats-style tree):**

```scala
import ox.flow.csv.codec.defaults.all.given // all primitive defaults
import ox.flow.csv.codec.defaults.missingDataFallback.given // only MissingData primitives
import ox.flow.csv.codec.defaults.nullData.given // only NullData primitives
```

**Rationale:**
- `Option[T]` is the natural Scala type for nullable/missing data — instance always available, no import needed. `T | Null` deliberately has none, see below.
- Primitive defaults (0, "", false) are a convenience with a cost: they silently substitute values that may mask data quality issues. Requiring explicit import makes this a conscious choice.
- Separating `MissingDataFallback` from `NullFallback` allows different handling: a field might accept nulls (database semantics) but not tolerate missing columns (schema mismatch), or vice versa.

**Providing both at once:** When a type needs the same handling for both missing and null, a single `given` can satisfy both:
```scala
given (NullData[MyType] & MissingData[MyType]) = new NullData[MyType] with MissingData[MyType]:
  def value: MyType = MyType.empty
  def default: MyType = value
```

**When no instance is found, `@implicitNotFound` guides the user with four options:**
1. Change field type to `Option[T]`
2. Import primitive defaults
3. Provide a custom `given` instance
4. Use a `CsvDecoder[Option[String] => T]` to handle decoding + missing/null in one place

## No `T | Null` fallback instance

**Decision:** Neither `MissingDataFallback` nor `NullFallback` provides a `given [T]: F[T | Null]`. A nullable field is expressed as `Option[T]`. Primitive types, `String` included, require an explicit `defaults` import.

**Rationale:** The module does not enable `-Yexplicit-nulls`, so `Null` remains a subtype of every reference type and both directions hold:

```scala
summon[String <:< String | Null] // ok
summon[String | Null <:< String] // ok, without -Yexplicit-nulls
```

`String | Null` and `String` are therefore the *same type*. Since the fallbacks are covariant (`F[+T]`), a `given [T]: F[T | Null]` matches a plain `F[String]` with `T := String` — no import, no warning. It then supplies `null`.

The consequences contradicted "Missing data: three distinct states" above:
- `case class Row(a: String, b: String)` parsed from `"x"` yielded `Row("x", null)` — a `null` in a field declared non-nullable, which NPEs on first use downstream.
- Missing, null, and empty collapsed into one outcome for `String`, the exact loss of information that section rejects.
- The opt-in `MissingDataFallback[String] = ""` in `defaults` was unreachable for a bare `String` field, so the documented "conscious choice" could not be made.

Only non-nullable types (`Int`, `Boolean`, …) behaved as designed, which is why the gap stayed hidden.

**Alternatives considered:**
- *Enable `-Yexplicit-nulls`*: genuinely fixes resolution (verified: the given no longer matches `F[String]`), but is module-wide and would not fix the encoder side — `CsvEncoderWithNull[-T]` is contravariant, so `CsvEncoderWithNull[T | Null] <: CsvEncoderWithNull[T]` holds with or without the flag.
- *Make the fallbacks invariant*: blocks the accidental match, but still leaves `T | Null` as a type indistinguishable from `T`.

Dropping the instance is the smaller change and makes nullability explicit at the field type.

**Note on the encoder:** `CsvEncoderWithNull[Int]` still resolves via the contravariant `T | Null` instance. This is benign — a non-nullable field never produces a `null`, so the instance delegates to the plain `CsvEncoder` (`encode(5) == "5"`). It is an over-permissive signature, not a correctness bug, unlike the decode side which injected an actual `null`.

## Apache CSV processing pipeline (verified by tests)

**Order of operations (fixed, not configurable):**

```
1. ignoreEmptyLines   — raw line check, zero-length only, BEFORE parsing
2. parse fields       — delimiter splitting, quote handling
3. trim / ignoreSurroundingSpaces — per field (trim strips inside quotes too; ignoreSurroundingSpaces respects quotes)
4. QuoteMode null inference — ALL_NON_NULL/NON_NUMERIC + quoteChar set: unquoted empty → null
5. nullString comparison — per field, on trimmed value (under ALL/MINIMAL: matches regardless of quoting; under ALL_NON_NULL/NON_NUMERIC: quoting protects from nullString)
```

**Key findings:**
- ignoreEmptyLines only checks zero-length lines. Space-only lines survive → trim may make them empty → nullString may make them null.
- Trim happens AFTER ignoreEmptyLines — no interaction between them.
- QuoteMode ALL_NON_NULL/NON_NUMERIC make the parser treat unquoted empty as null even without nullString (requires quoteChar set). Without quoteChar (e.g. MySQL), this inference doesn't fire.
- Apache does NOT support roundtrip for lossy settings (trim, ignoreEmptyLines, nullString="" with QuoteMode.ALL). Only ALL_NON_NULL + nullString="" roundtrips correctly (PostgreSQL CSV pattern).

## Empty line detection: charPos tracking

**Decision:** Detect empty lines via `CSVRecord.getCharacterPosition` delta, not record content.

**Why:** After trim + nullString, an empty line and a space-only line are indistinguishable by value (both become null/empty). Only the character position delta reveals original line length:
`currentRecord.charPos - lastCharPos - separatorLength == 0` → truly empty line.

Single `var lastCharPos` state, no lookahead needed.

## Rfc4180: no NullString by default

**Decision:** `Rfc4180` format does not set `NullString[""]`. Empty string `""` is legitimate CSV data — forcing null interpretation is lossy and not in the RFC spec.

**Empty line handling:** With `ignoreEmptyLines=false`, empty line → ragged row `['']`. First field gets `""` decoded normally via `CsvDecoder`. Remaining fields missing → compile-time check requires `DecoderMissing` for fields 2..N. User can opt into `NullString[""]` via builder if they want Apache-compatible null semantics.

## EmptyRecord dropped

**Decision:** `EmptyRecord` removed from all format return types. Empty lines are decoded as data rows.

**Why:** With `ignoreEmptyLines=false`, an empty line produces a record with `size=1, value=""` — a ragged row with `""` in the first column. With `trailingDelimiter=true`, an empty line acts as EOF (swallows remaining data). Either way, no distinct `EmptyRecord` type is needed:
- `ignoreEmptyLines=true` → empty lines never reach us
- `ignoreEmptyLines=false` → empty line = ragged row, first field `""`, rest via `DecoderMissing`
- `trailingDelimiter=true` → empty line = EOF, no record emitted

**Note:** `trailingDelimiter=true` + `ignoreEmptyLines=false` is broken in Apache CSV — empty line terminates parsing and swallows all subsequent records. No predefined format uses `trailingDelimiter=true`.

## Decode pipeline priority

**Decision:** Per-field decode follows this priority chain:

```
1. Missing check — record.size < expectedSize → DecoderMissing[T] for missing trailing fields
2. Null check    — Apache returned null for this field → DecoderNull[T]
3. Normal decode — CsvDecoder[T] (String → T)
```

## Encode pipeline priority and format constraints

**Decision:** Per-field encode follows this priority, constrained by format:

```
1. EncoderMissing — should this field be omitted? (trailing only → ragged row)
2. EncoderNull    — should this value print as nullString?
3. CsvEncoder     — T → String
```

Having all three (Missing, Null, Data) simultaneously requires precedence: Missing > Null > Data. Without this order, a value that is both "missing-like" and "null-like" would be ambiguous.

**Format constrains what's available:**

| Format has | Missing allowed? | Null allowed? | Encode chain |
|---|---|---|---|
| ragged + nullString | yes | yes | Missing → Null → Data |
| ragged, no nullString | yes | no | Missing → Data |
| DisallowRaggedRows + nullString | no | yes | Null → Data |
| DisallowRaggedRows, no nullString | no | no | Data only |

**Middle-of-row missing degrades:** CSV can't represent a gap mid-row. If EncoderMissing fires for a non-trailing field → fall through to EncoderNull → fall through to CsvEncoder (print as empty string).

**Compile-time enforcement:** Format determines which typeclasses are required. `Option[Int]` field + no nullString + DisallowRaggedRows → compile error ("field type can produce null but format has no nullString configured").

## Encoder derivation is gated on the format's null string

**Decision:** `CsvRowEncoder` summons `CsvEncoderWithNull` per field when the format defines a null string,
and a plain `CsvEncoder` otherwise. Never both, and never a fallback between them.

**Why:** writing a `null` into a format with no null string does not round-trip — the cell comes back as
whatever the dialect renders for an absent value. Gating the summon means a field can only produce a null
when the format can represent one.

**Consequence per field type:**

| Field type | no null string | with null string |
|---|---|---|
| `String`, `Int`, … | plain `CsvEncoder` | derived from `CsvEncoder` |
| `T \| Null` | rejected | derived from `CsvEncoder[T]` |
| `Option[T]` | rejected | derived, `None` → null |
| a user's own type | needs `CsvEncoder` | needs `CsvEncoderWithNull` |

Only `Option[T]` and `T | Null` derive, so any other type has to state what a null means for it — that is the
point of the requirement, not an obstacle to work around.

**Not enforced:** a user may write a plain `CsvEncoder[MyType | Null]`, and a format with no null string will
accept it. The value returned for `null` is then an ordinary cell that does not read back as a null. This is
catchable at compile time (the field type is a union) but deliberately left alone: rendering a null as `"-"`
or `"NULL"` for a particular sink is a legitimate choice, and refusing it would be the library overreaching.
Documented on `CsvEncoderWithNull` instead.

## Codec separation: six typeclasses

**Decision:** Field-level handling splits into three concerns × two directions:

| Concern | Decode (parse) | Encode (print) |
|---|---|---|
| Value | `CsvDecoder[T]: String → T` | `CsvEncoder[T]: T → String` |
| Null | `DecoderNull[T]: () → T` | `EncoderNull[T]: T → Boolean` |
| Missing | `DecoderMissing[T]: () → T` | `EncoderMissing[T]: T → Boolean` |

**Rationale:** CsvDecoder/CsvEncoder stay simple `String ↔ T`. No null/missing concern. Option[T] doesn't need special decoder/encoder — it composes: `DecoderNull[Option[T]]` handles null → `None`, then `CsvDecoder[T]` handles the inner value. User writes `CsvDecoder[Int]`, type system composes it with `DecoderNull[Option[Int]]` at field level.

## Encoder symmetry: DecoderNull/EncoderNull, DecoderMissing/EncoderMissing

**Decision:** EncoderNull is `T → Boolean` (should this value encode as null?). Gives user control over roundtrip: e.g. `EncoderNull[Int]` can map `0 → true` (write as null) or `0 → false` (write literal "0"). Without this, decoded null→0 always writes back as "0", losing null.

**EncoderMissing dropped.** Missing mid-row shifts columns → broken CSV. Only valid trailing. Not worth a typeclass — can be simplified to a format-level `CompactFormat` setting that truncates trailing default-valued fields on write. Deferred.

## QuoteMode parser effects

**Decision:** QuoteMode is not just a printer setting — ALL_NON_NULL and NON_NUMERIC affect parsing.

| QuoteMode | quoteChar | Unquoted empty | Quoted empty | Unquoted nullString | Quoted nullString |
|---|---|---|---|---|---|
| MINIMAL/ALL | set | `""` | `""` | null | null |
| ALL_NON_NULL | set | **null** | `""` (protected) | null | `string` (protected) |
| NON_NUMERIC | set | **null** | `""` (protected) | null | `string` (protected) |
| ALL_NON_NULL | **none** (MySQL) | `""` (no inference) | n/a | null | n/a |

Only POSTGRESQL_CSV uses ALL_NON_NULL + quoteChar among predefined formats. For custom formats with ALL_NON_NULL + quoteChar, this needs to be modeled as a type-level setting that triggers NullFallback requirement.

---

## Raw notes (historical)

notes:
we would need handling for ignore empty lines
1. if its empty - make it EmptyRecord
2. if its not empty - and ragged rows disallowed -
   throw
3. if its not empty and ragged rows enabled - parse as
   ragged row would be, probably according to missing
   data
4. if its empty and we made "" a null string we should
   still consider it as empty line - not handle null and
   fill it with defaults
5. special case is one column csv, represented by one
   field case class, here its even more ambigious because
   empty line is represented as "", if we mark "" as
   null string or we have missing data ahndling , our
   otuput type should never contain EmptyRow, because
   missing data handling takes care or null handling
   takes care
6. even more special - case class OneField(foo:String)
- without nullstring set and missingdata set (they
  are not required because missin is just "", there is
  no ragged data possible) null string need to be
  checked still if this is set in format, but if we dont
  set null string then format should not force us.
  so in this case we will be never required to provide
  missingdata fallback , we can be required to provide
  null fallback if we explicitly set null string, and
  then we should first check if foo is null string and
  convert it to target field with typeclass, however:
  if we have OneField(Option[String]) or OneField(String
  | Null) ? and nullstring is set to ""? i think we
  should follow what apache does, after all we are just
  interpreting apache output and if it puts null we put
  null according to null handler. so we follow priority
  of apache parser
