val sbtSoftwareMillVersion = "2.1.0"
addSbtPlugin("com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-common" % sbtSoftwareMillVersion)
addSbtPlugin("com.softwaremill.sbt-softwaremill" % "sbt-softwaremill-publish" % sbtSoftwareMillVersion)
addSbtPlugin("org.scalameta" % "sbt-mdoc" % "2.7.2")
addSbtPlugin("com.typesafe" % "sbt-mima-plugin" % "1.1.4")
