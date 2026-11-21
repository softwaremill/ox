import org.pastalab.fray.plugins.base.{Commons, FrayVersion, FrayWorkspaceInitializer}
import sbt.*

import scala.jdk.CollectionConverters.*

/** Prepares a workspace for running Fray (https://github.com/cmu-pasta/fray) tests: an instrumented JDK 25, the JVMTI agent and the report
  * directory. A port of Fray's Maven plugin (`prepare-fray` goal).
  *
  * Tests must run in a forked JVM, using [[javaHome]] and the options returned by [[prepare]]. The JDK used to create the instrumented one
  * is taken from `JDK25_HOME`, or downloaded to `target` if that's not set.
  *
  * Fray only supports some OS and architecture combinations, see [[isSupported]].
  */
object FrayWorkspace:
  val version: String = FrayVersion.INSTANCE.getVersion

  /** Dependencies of Fray's instrumentation, which need to be added to the [[Configuration]] passed to [[prepare]]. */
  val dependencies: Seq[ModuleID] = Seq(
    "org.pastalab.fray.instrumentation" % "fray-instrumentation-jdk" % version,
    "org.pastalab.fray.instrumentation" % "fray-instrumentation-agent" % version
  ) ++ osAndArch.map(p => "org.pastalab.fray" % s"fray-jvmti-$p" % version)

  /** Whether Fray publishes a JVMTI agent for the current OS and architecture. If not, Fray tests can't run. */
  def isSupported: Boolean = osAndArch.isDefined

  def javaHome(buildDir: File): File = Commons.INSTANCE.getFrayJavaHomePath(buildDir.toPath).toFile

  /** Creates the workspace in `buildDir` (if not yet created), using the jars from `frayJars`. Returns the options for the test JVM.
    * Requires [[isSupported]].
    */
  def prepare(buildDir: File, frayJars: Seq[File]): Seq[String] =
    def jarOf(artifactName: String) = frayJars
      .find(_.getName.startsWith(s"$artifactName-$version"))
      .getOrElse(throw new IllegalStateException(s"Missing Fray artifact: $artifactName"))

    val agentJar = jarOf("fray-instrumentation-agent")
    val jvmtiJar = jarOf(s"fray-jvmti-${osAndArch.getOrElse(throw new IllegalStateException("Fray doesn't support this platform"))}")
    val jlinkDependencies = frayJars.filterNot(f => f == agentJar || f == jvmtiJar)

    val initializer =
      new FrayWorkspaceInitializer(buildDir.toPath, jarOf("fray-instrumentation-jdk"), jlinkDependencies.toSet.asJava, jvmtiJar)
    initializer.createInstrumentedJDK(version, null)
    initializer.createJVMTiRuntime()

    Seq(
      s"-javaagent:${agentJar.getAbsolutePath}",
      s"-agentpath:${Commons.INSTANCE.getFrayJvmtiPath(buildDir.toPath).toAbsolutePath}",
      s"-Dfray.workDir=${initializer.getReportPath.toAbsolutePath}"
    )
  end prepare

  private def osAndArch: Option[String] =
    val osName = System.getProperty("os.name").toLowerCase
    val os =
      if osName.contains("mac") then "macos"
      else if osName.contains("linux") then "linux"
      else if osName.contains("windows") then "windows"
      else "unknown"
    val arch = System.getProperty("os.arch").replace("-", "") match
      case "amd64" => "x8664"
      case other   => other
    val published = Set("linux-x8664", "linux-aarch64", "macos-aarch64", "windows-x8664")
    Some(s"$os-$arch").filter(published.contains)
  end osAndArch
end FrayWorkspace
