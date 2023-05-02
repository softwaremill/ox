import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.ossPublishSettings

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.ox",
  scalaVersion := "3.2.2"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15" % Test
val logback = "ch.qos.logback" % "logback-classic" % "1.4.6"

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false, name := "ox")
  .aggregate(core, examples)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
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
