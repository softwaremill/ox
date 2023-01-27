import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings

lazy val commonSettings = commonSmlBuildSettings ++ Seq(
  organization := "com.softwaremill.effects",
  scalaVersion := "3.2.1"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.14" % Test
val logback = "ch.qos.logback" % "logback-classic" % "1.4.5"

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false, name := "ox")
  .aggregate(core)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings: _*)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      logback % Test,
      scalaTest
    )
  )
