import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.{ossPublishSettings, updateDocs}
import com.softwaremill.UpdateVersionInDocs

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.ox",
  scalaVersion := "3.3.4",
  updateDocs := Def.taskDyn {
    val files1 = UpdateVersionInDocs(sLog.value, organization.value, version.value)
    Def.task {
      (documentation / mdoc).toTask("").value
      files1 ++ Seq(file("generated-doc/out"))
    }
  }.value
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test
val slf4j = "org.slf4j" % "slf4j-api" % "2.0.16"
val logback = "ch.qos.logback" % "logback-classic" % "1.5.12"

// used during CI to verify that the documentation compiles
val compileDocumentation: TaskKey[Unit] = taskKey[Unit]("Compiles documentation throwing away its output")
compileDocumentation := {
  (documentation / mdoc).toTask(" --out target/ox-doc").value
}

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false, name := "ox")
  .aggregate(core, examples, kafka, mdcLogback)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.jox" % "channels" % "0.3.1",
      scalaTest
    ),
    Test / fork := true
  )

lazy val examples: Project = (project in file("examples"))
  .settings(commonSettings)
  .settings(
    name := "examples",
    libraryDependencies ++= Seq(
      logback % Test,
      scalaTest
    ),
    publishArtifact := false
  )
  .dependsOn(core)

lazy val kafka: Project = (project in file("kafka"))
  .settings(commonSettings)
  .settings(
    name := "kafka",
    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "3.8.0",
      slf4j,
      logback % Test,
      "io.github.embeddedkafka" %% "embedded-kafka" % "3.8.0" % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.1.0" % Test,
      "org.apache.pekko" %% "pekko-stream" % "1.1.2" % Test,
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
    mdcLogback
  )
