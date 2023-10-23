import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.ossPublishSettings

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.ox",
  scalaVersion := "3.3.1"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15" % Test
val slf4j = "org.slf4j" % "slf4j-api" % "2.0.7"
val logback = "ch.qos.logback" % "logback-classic" % "1.4.7"

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false, name := "ox")
  .aggregate(core, examples, kafka)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "org.jctools" % "jctools-core" % "4.0.1",
      scalaTest
    )
  )

lazy val examples: Project = (project in file("examples"))
  .settings(commonSettings)
  .settings(
    name := "examples",
    libraryDependencies ++= Seq(
      logback % Test,
      scalaTest
    )
  )
  .dependsOn(core)

lazy val kafka: Project = (project in file("kafka"))
  .settings(commonSettings)
  .settings(
    name := "kafka",
    libraryDependencies ++= Seq(
      "org.apache.kafka" % "kafka-clients" % "3.5.0",
      slf4j,
      logback % Test,
      "io.github.embeddedkafka" %% "embedded-kafka" % "3.5.1" % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.0.0",
      "org.apache.pekko" %% "pekko-stream" % "1.0.1",
      scalaTest
    )
  )
  .dependsOn(core)
