import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.ossPublishSettings

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.ox",
  scalaVersion := "3.2.2",
  javaOptions += "--enable-preview --add-modules jdk.incubator.concurrent"
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.15" % Test
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
