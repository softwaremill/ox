import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.{ossPublishSettings, updateDocs}
import com.softwaremill.UpdateVersionInDocs
import com.typesafe.tools.mima.core.{MissingClassProblem, ProblemFilters}
import scalanative.build.*

commonSmlBuildSettings
ossPublishSettings

organization := "com.softwaremill.ox"
lazy val scala3 = "3.9.0"

scalaVersion := scala3
scalacOptions ++= Seq("-java-output-version", "21")
Test / scalacOptions += "-Wconf:msg=unused value of type org.scalatest.Assertion:s"
Test / scalacOptions += "-Wconf:msg=unused value of type org.scalatest.compatible.Assertion:s"
mimaPreviousArtifacts := Set.empty // we only use MiMa for `core` for now, using enableMimaSettings

val enableMimaSettings = Seq(
  mimaPreviousArtifacts := {
    val current = version.value
    val isRcOrMilestone = current.contains("M") || current.contains("RC")
    if !isRcOrMilestone then
      val previous = previousStableVersion.value
      println(s"[info] Not a M or RC version, using previous version for MiMa check: $previous")
      previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet
    else
      println(s"[info] $current is an M or RC version, no previous version to check with MiMa")
      Set.empty
  },
  mimaBinaryIssueFilters ++= Seq(
    // GroupingTimeout is only ever used within the groupWithin method, never exposed externally
    ProblemFilters.exclude[MissingClassProblem]("ox.flow.FlowOps$GroupingTimeout$")
  )
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.20" % Test
val slf4j = "org.slf4j" % "slf4j-api" % "2.0.20"
val openTelemetryVersion = "1.68.0"
val logback = "ch.qos.logback" % "logback-classic" % "1.6.7"
val jsoniterVersion = "2.43.0"
val jsoniterCore = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-core" % jsoniterVersion
val jsoniterMacros = "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros" % jsoniterVersion

// used during CI to verify that the documentation compiles
val compileDocumentation: TaskKey[Unit] = taskKey[Unit]("Compiles documentation throwing away its output")

lazy val root = rootProject
  .settings(
    publishArtifact := false,
    name := "ox",
    updateDocs := Def.uncached(Def.taskDyn {
      val files1 = UpdateVersionInDocs(sLog.value, organization.value, version.value)
      Def.task {
        (documentation / mdoc).toTask("").value
        files1 ++ Seq(file("generated-doc/out"))
      }
    }.value),
    compileDocumentation := (documentation / mdoc).toTask(" --out target/ox-doc").value
  )
  .autoAggregate

lazy val core = (projectMatrix in file("core"))
  .settings(
    name := "core",
    libraryDependencies += scalaTest
  )
  .jvmPlatform(
    scalaVersions = Seq(scala3),
    settings = enableMimaSettings ++ Seq(
      Test / fork := true,
      libraryDependencies ++= Seq(
        "com.softwaremill.jox" % "channels" % "1.1.2",
        "org.apache.pekko" %% "pekko-stream" % "2.0.0" % Test,
        "org.reactivestreams" % "reactive-streams-tck-flow" % "1.0.4" % Test
      )
    )
  )
  .nativePlatform(
    scalaVersions = Seq(scala3),
    settings = Seq(
      Test / fork := false,
      // One native test process, so a CI hang leaves a single suite at the end of the log.
      Test / parallelExecution := false,
      Test / logBuffered := false,
      nativeConfig ~= { _.withMultithreading(true) }
    )
  )

lazy val examples = (projectMatrix in file("examples"))
  .settings(
    name := "examples",
    publishArtifact := false,
    Compile / mainClass := Some("VirtualThreadsNativeJvmBenchmark")
  )
  .jvmPlatform(
    scalaVersions = Seq(scala3),
    settings = Seq(
      assembly / assemblyJarName := "examples-assembly.jar"
    )
  )
  .nativePlatform(
    scalaVersions = Seq(scala3),
    settings = Seq(
      nativeConfig ~= { _.withMultithreading(true) }
    )
  )
  .dependsOn(core)

lazy val kafka: Project = (project in file("kafka"))
  .settings(
    name := "kafka",
    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "4.4.0",
      slf4j,
      logback % Test,
      "io.github.embeddedkafka" %% "embedded-kafka" % "4.4.0" % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.2.0" % Test,
      "org.apache.pekko" %% "pekko-stream" % "2.0.0" % Test,
      scalaTest
    )
  )
  .dependsOn(core.jvm(scala3))

lazy val mdcLogback: Project = (project in file("mdc-logback"))
  .settings(
    name := "mdc-logback",
    libraryDependencies ++= Seq(
      logback,
      scalaTest
    )
  )
  .dependsOn(core.jvm(scala3))

lazy val flowReactiveStreams: Project = (project in file("flow-reactive-streams"))
  .settings(
    name := "flow-reactive-streams",
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams" % "1.0.4",
      scalaTest
    )
  )
  .dependsOn(core.jvm(scala3))

lazy val flowJson: Project = (project in file("flow-json"))
  .settings(
    name := "flow-json",
    libraryDependencies ++= Seq(
      jsoniterCore,
      jsoniterMacros % Test,
      scalaTest
    )
  )
  .dependsOn(core.jvm(scala3))

lazy val cron: Project = (project in file("cron"))
  .settings(
    name := "cron",
    libraryDependencies ++= Seq(
      "com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.8.2",
      scalaTest
    )
  )
  .dependsOn(core.jvm(scala3) % "test->test;compile->compile")

lazy val otelContext: Project = (project in file("otel-context"))
  .settings(
    name := "otel-context",
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion,
      scalaTest
    )
  )
  .dependsOn(core.jvm(scala3) % "test->test;compile->compile")

lazy val otelTracing: Project = (project in file("otel-tracing"))
  .settings(
    name := "otel-tracing",
    Test / fork := true,
    Test / javaOptions += "-Dio.opentelemetry.context.enableStrictContext=true",
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-api" % openTelemetryVersion,
      scalaTest,
      "io.opentelemetry" % "opentelemetry-sdk-testing" % openTelemetryVersion % Test,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % openTelemetryVersion % Test
    )
  )
  .dependsOn(core.jvm(scala3) % "test->test;compile->compile", otelContext % "test->test;compile->compile")

lazy val documentation: Project = (project in file("generated-doc")) // important: it must not be doc/
  .enablePlugins(MdocPlugin)
  .settings(
    mdocIn := file("doc"),
    moduleName := "ox-doc",
    mdocVariables := Map(
      "VERSION" -> version.value,
      "JSONITER_VERSION" -> jsoniterVersion
    ),
    mdocOut := file("generated-doc/out"),
    mdocExtraArguments := Seq("--clean-target"),
    publishArtifact := false,
    name := "doc",
    libraryDependencies ++= Seq(logback % Test, jsoniterMacros)
  )
  .dependsOn(
    core.jvm(scala3),
    kafka,
    mdcLogback,
    flowReactiveStreams,
    flowJson,
    cron,
    otelContext,
    otelTracing
  )
