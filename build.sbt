import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings

lazy val commonSettings = commonSmlBuildSettings ++ Seq(
  organization := "com.softwaremill.effects",
  scalaVersion := "3.2.1"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.14" % Test
val logback = "ch.qos.logback" % "logback-classic" % "1.4.5"

lazy val rootProject = (project in file("."))
  .settings(commonSettings: _*)
  .settings(publishArtifact := false, name := "warp")
  .aggregate(jdk)

lazy val jdk: Project = (project in file("jdk"))
  .settings(commonSettings: _*)
  .settings(
    name := "jdk",
    libraryDependencies ++= Seq(
      logback,
      scalaTest
    )
  )
