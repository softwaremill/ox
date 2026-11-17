package ox.flow.csv.format.typed

import scala.quoted.*

/** Compile-time queries over a format's type-level settings intersection. */
private[csv] object SettingsQuery:

  /** True when `S` contains `Sample`. Pass a wildcard application, e.g. `NullString[?]`, to match
    * every instantiation, or an exact one, e.g. `HeaderModeSetting[HeaderMode.Force]`.
    *
    * A macro rather than `summonFrom`, so the result is a literal the enclosing `inline if` can reduce on.
    */
  transparent inline def hasSetting[S, Sample]: Boolean = ${ hasSettingImpl[S, Sample] }

  private def hasSettingImpl[S: Type, Sample: Type](using Quotes): Expr[Boolean] =
    import quotes.reflect.*
    if TypeRepr.of[S] <:< TypeRepr.of[Sample] then Expr(true) else Expr(false)
