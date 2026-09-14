package ox

import scala.annotation.targetName

// binary-compatibility bridges for callers compiled against previous ox versions; remove in 2.0. The Scala-level
// names differ from the originals (to avoid overload ambiguity at in-package call sites), while @targetName restores
// the original JVM names, preserving linkage.

@targetName("useInScope")
private[ox] def useInScopeCompat[T](acquire: => T)(release: T => Unit)(using ox: OxUnsupervised): T =
  useInScope(acquire)(release)
@targetName("useCloseableInScope")
private[ox] def useCloseableInScopeCompat[T <: AutoCloseable](c: => T)(using ox: OxUnsupervised): T =
  useCloseableInScope(c)
@targetName("releaseAfterScope")
private[ox] def releaseAfterScopeCompat(release: => Unit)(using ox: OxUnsupervised): Unit =
  releaseAfterScope(release)
@targetName("releaseCloseableAfterScope")
private[ox] def releaseCloseableAfterScopeCompat(toRelease: AutoCloseable)(using ox: OxUnsupervised): Unit =
  releaseCloseableAfterScope(toRelease)
