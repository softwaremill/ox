import com.softwaremill.SbtSoftwareMillCommon.commonSmlBuildSettings
import com.softwaremill.Publish.{ossPublishSettings, updateDocs}
import com.softwaremill.UpdateVersionInDocs

lazy val commonSettings = commonSmlBuildSettings ++ ossPublishSettings ++ Seq(
  organization := "com.softwaremill.ox",
  scalaVersion := "3.3.3",
  updateDocs := Def.taskDyn {
    val files1 = UpdateVersionInDocs(sLog.value, organization.value, version.value)
    Def.task {
      (documentation / mdoc).toTask("").value
      files1 ++ Seq(file("generated-doc/out"))
    }
  }.value
)

val scalaTest = "org.scalatest" %% "scalatest" % "3.2.19" % Test
val slf4j = "org.slf4j" % "slf4j-api" % "2.0.13"
val logback = "ch.qos.logback" % "logback-classic" % "1.5.6"

// used during CI to verify that the documentation compiles
val compileDocumentation: TaskKey[Unit] = taskKey[Unit]("Compiles documentation throwing away its output")
compileDocumentation := {
  (documentation / mdoc).toTask(" --out target/ox-doc").value
}

val useRequireIOPlugin =
  // Based on:
  // https://stackoverflow.com/questions/54660122/how-to-include-a-scala-compiler-plugin-from-a-local-path
  Compile / scalacOptions ++= {
    val jar = (plugin / Compile / Keys.`package`).value
    System.setProperty("sbt.paths.plugin.jar", jar.getAbsolutePath)

    val addPlugin = "-Xplugin:" + jar.getAbsolutePath
    val dummy = "-Jdummy=" + jar.lastModified
    Seq(addPlugin, dummy)
  }

lazy val rootProject = (project in file("."))
  .settings(commonSettings)
  .settings(publishArtifact := false, name := "ox")
  .aggregate(core, plugin, pluginTest, examples, kafka)

lazy val core: Project = (project in file("core"))
  .settings(commonSettings)
  .settings(
    name := "core",
    libraryDependencies ++= Seq(
      "com.softwaremill.jox" % "core" % "0.2.1",
      scalaTest
    ),
    // Check IO usage in core
    useRequireIOPlugin
  )

lazy val plugin: Project = (project in file("plugin"))
  .settings(commonSettings)
  .settings(
    name := "plugin",
    libraryDependencies ++= Seq("org.scala-lang" %% "scala3-compiler" % scalaVersion.value, scalaTest)
  )

lazy val pluginTest: Project = (project in file("plugin-test"))
  .settings(commonSettings)
  .settings(
    name := "plugin-test",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-compiler" % scalaVersion.value,
      scalaTest
    ),
    publishArtifact := false,
    // Playground testing
    useRequireIOPlugin,
    // Unit testing, based on:
    // https://github.com/xebia-functional/munit-compiler-toolkit/
    Test / javaOptions += {
      val dependencyJars = (Compile / dependencyClasspath).value.files
      val thisJar = (Compile / Keys.`package`).value
      val `scala-compiler-classpath` =
        (dependencyJars :+ thisJar)
          .map(_.toPath.toAbsolutePath.toString())
          .mkString(":")
      s"-Dscala-compiler-classpath=${`scala-compiler-classpath`}"
    },
    Test / javaOptions += Def.taskDyn {
      Def.task {
        val _ = (plugin / Compile / Keys.`package`).value
        val `scala-compiler-options` =
          s"${(plugin / Compile / packageBin).value}"
        s"""-Dscala-compiler-plugin=${`scala-compiler-options`}"""
      }
    }.value,
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
      "org.apache.kafka" % "kafka-clients" % "3.7.1",
      slf4j,
      logback % Test,
      "io.github.embeddedkafka" %% "embedded-kafka" % "3.7.0" % Test,
      "org.apache.pekko" %% "pekko-connectors-kafka" % "1.0.0" % Test,
      "org.apache.pekko" %% "pekko-stream" % "1.0.3" % Test,
      scalaTest
    ),
    useRequireIOPlugin
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
    kafka
  )
