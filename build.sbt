import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.{ossPublishSettings, updateDocs}
import com.softwaremill.UpdateVersionInDocs
import com.typesafe.tools.mima.core.{MissingClassProblem, ProblemFilters}

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.ox",
  scalaVersion := "3.3.7",
  updateDocs := Def.taskDyn {
    val files1 = UpdateVersionInDocs(sLog.value, organization.value, version.value)
    Def.task {
      (documentation / mdoc).toTask("").value
      files1 ++ Seq(file("generated-doc/out"))
    }
  }.value,
  Test / scalacOptions += "-Wconf:msg=unused value of type org.scalatest.Assertion:s",
  Test / scalacOptions += "-Wconf:msg=unused value of type org.scalatest.compatible.Assertion:s",
  mimaPreviousArtifacts := Set.empty // we only use MiMa for `core` for now, using enableMimaSettings
)

val enableMimaSettings = Seq(
  mimaPreviousArtifacts := {
    val current = version.value
    val isRcOrMilestone = current.contains("M") || current.contains("RC")
    if (!isRcOrMilestone) {
      val previous = previousStableVersion.value
      println(s"[info] Not a M or RC version, using previous version for MiMa check: $previous")
      previousStableVersion.value.map(organization.value %% moduleName.value % _).toSet
    } else {
      println(s"[info] $current is an M or RC version, no previous version to check with MiMa")
      Set.empty
    }
  },
  mimaBinaryIssueFilters ++= Seq(
    // GroupingTimeout is only ever used within the groupWithin method, never exposed externally
    ProblemFilters.exclude[MissingClassProblem]("ox.flow.FlowOps$GroupingTimeout$")
  )
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test
val slf4j = "org.slf4j" % "slf4j-api" % "2.0.17"
val logback = "ch.qos.logback" % "logback-classic" % "1.5.21"

// used during CI to verify that the documentation compiles
val compileDocumentation: TaskKey[Unit] = taskKey[Unit]("Compiles documentation throwing away its output")
compileDocumentation := {
  (documentation / mdoc).toTask(" --out target/ox-doc").value
}

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false, name := "ox")
  .aggregate(core, kafka, mdcLogback, flowReactiveStreams, cron, otelContext)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.jox" % "channels" % "1.0.1",
      scalaTest,
      "org.apache.pekko" %% "pekko-stream" % "1.2.0" % Test,
      "org.reactivestreams" % "reactive-streams-tck-flow" % "1.0.4" % Test
    ),
    Test / fork := true
  )
  .settings(enableMimaSettings)

lazy val kafka: Project = (project in file("kafka"))
  .settings(commonSettings)
  .settings(
    name := "kafka",
    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "4.1.0",
      slf4j,
      logback % Test,
      "io.github.embeddedkafka" %% "embedded-kafka" % "4.1.0" % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.1.0" % Test,
      "org.apache.pekko" %% "pekko-stream" % "1.2.0" % Test,
      scalaTest
    )
  )
  .dependsOn(core)

lazy val mdcLogback: Project = (project in file("mdc-logback"))
  .settings(commonSettings)
  .settings(
    name := "mdc-logback",
    libraryDependencies ++= Seq(
      logback,
      scalaTest
    )
  )
  .dependsOn(core)

lazy val flowReactiveStreams: Project = (project in file("flow-reactive-streams"))
  .settings(commonSettings)
  .settings(
    name := "flow-reactive-streams",
    libraryDependencies ++= Seq(
      "org.reactivestreams" % "reactive-streams" % "1.0.4",
      scalaTest
    )
  )
  .dependsOn(core)

lazy val cron: Project = (project in file("cron"))
  .settings(commonSettings)
  .settings(
    name := "cron",
    libraryDependencies ++= Seq(
      "com.github.alonsodomin.cron4s" %% "cron4s-core" % "0.8.2",
      scalaTest
    )
  )
  .dependsOn(core % "test->test;compile->compile")

lazy val otelContext: Project = (project in file("otel-context"))
  .settings(commonSettings)
  .settings(
    name := "otel-context",
    libraryDependencies ++= Seq(
      "io.opentelemetry" % "opentelemetry-api" % "1.54.0",
      scalaTest
    )
  )
  .dependsOn(core % "test->test;compile->compile")

lazy val documentation: Project = (project in file("generated-doc")) // important: it must not be doc/
  .enablePlugins(MdocPlugin)
  .settings(commonSettings)
  .settings(
    mdocIn := file("doc"),
    moduleName := "ox-doc",
    mdocVariables := Map(
      "VERSION" -> version.value
    ),
    mdocOut := file("generated-doc/out"),
    mdocExtraArguments := Seq("--clean-target"),
    publishArtifact := false,
    name := "doc",
    libraryDependencies ++= Seq(logback % Test)
  )
  .dependsOn(
    core,
    kafka,
    mdcLogback,
    flowReactiveStreams,
    cron,
    otelContext
  )
