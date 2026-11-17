val sbtSoftwareMillVersion = "3.0.2"
addSbtPlugin("com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-common" % sbtSoftwareMillVersion)
addSbtPlugin("com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-publish" % sbtSoftwareMillVersion)
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.9.2")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.2.1")

// used by FrayWorkspace, to prepare the instrumented JDK for Fray tests
libraryDependencies += "org.pastalab.fray" % "fray-plugins-base" % "0.9.2"
